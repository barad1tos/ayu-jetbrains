package dev.ayuislands.studio.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.accessibility.ScreenReader
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.VcsColorPreviewComponent
import dev.ayuislands.settings.VcsSection
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.catalogTestWork
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioVcsEdit
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioVcsEditing
import dev.ayuislands.studio.session.StudioVcsProjection
import dev.ayuislands.studio.session.vcsField
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPalette
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsIntensity
import dev.ayuislands.vcs.VcsWriteMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicHTML
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioVcsSectionTest {
    @Before
    fun forbidAmbientEffects() {
        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns mockk(relaxed = true)
        mockkObject(AyuIslandsSettings.Companion, LicenseChecker)
        every { AyuIslandsSettings.getInstance() } throws AssertionError("VCS controls must use explicit facts")
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Entitlement belongs to the host")
        every { LicenseChecker.requestLicense(any()) } throws AssertionError("Unlock belongs to the host")
    }

    @After
    fun cleanup() {
        SwingUtilities.invokeAndWait {}
        unmockkObject(AyuIslandsSettings.Companion, LicenseChecker)
        unmockkStatic(ActionManager::class)
    }

    @Test
    fun `Current and Draft compare the native VCS colors without editing or remounting controls`() {
        SwingUtilities.invokeAndWait {
            val initial = draft()
            val working = setDiffIntensity(initial, 91)
            val section = section()
            val root = section.content.component
            section.render(view(initial, working))
            val sample = descendants(root).filterIsInstance<VcsColorPreviewComponent>().single()
            val sliders = descendants(root).filterIsInstance<JSlider>()
            assertEquals(18, sample.intensityForTest(VcsColorCategory.DIFF_VIEWER))
            val acceptedColor = diffColor(sample)
            section.content.preview(PreviewMode.DRAFT)
            assertEquals(91, sample.intensityForTest(VcsColorCategory.DIFF_VIEWER))
            assertNotEquals(acceptedColor, diffColor(sample))
            section.content.preview(PreviewMode.CURRENT)
            assertEquals(acceptedColor, diffColor(sample))
            assertEquals(sliders, descendants(root).filterIsInstance<JSlider>())
            assertSame(sample, descendants(root).filterIsInstance<VcsColorPreviewComponent>().single())
            assertTrue(master(root).isSelected)
        }
    }

    @Test
    fun `each group keeps its disclosure state when VCS is disabled and re-enabled`() {
        SwingUtilities.invokeAndWait {
            val opened = mutableListOf<Pair<VcsSection, Boolean>>()
            var working = draft()
            val section =
                section(
                    stage = { working = it(working) },
                    disclose = { group, expanded -> opened += group to expanded },
                )
            val root = section.content.component
            section.render(view(working))
            val groups = descendants(root).filterIsInstance<TitledSeparator>()
            assertEquals(VcsSection.entries.map { it.title }, groups.map { it.text })
            assertEquals(5, visibleSliders(root).size)
            val merge = groups.single { it.text == VcsSection.MERGE.title }
            merge.dispatchEvent(
                MouseEvent(merge, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1),
            )
            assertEquals(4, visibleSliders(root).size)
            assertEquals(listOf(VcsSection.MERGE to false), opened)
            val before = working
            master(root).doClick()
            section.render(view(before, working))
            assertTrue(visibleSliders(root).isEmpty())
            section.content.preview(PreviewMode.DRAFT)
            val sample = descendants(root).filterIsInstance<VcsColorPreviewComponent>().single()
            assertEquals(VcsColorPalette.stock("DIFF_INSERTED", AyuVariant.MIRAGE), diffColor(sample))
            master(root).doClick()
            section.render(view(before, working))
            assertEquals(before.appearance, working.appearance)
            assertEquals(18, sample.intensityForTest(VcsColorCategory.DIFF_VIEWER))
            assertEquals(4, visibleSliders(root).size)
            assertEquals(groups, descendants(root).filterIsInstance<TitledSeparator>())
            assertEquals(listOf(VcsSection.MERGE to false), opened)
        }
    }

    @Test
    fun `successive slider edits update the working draft and preserve an unavailable preset`() {
        SwingUtilities.invokeAndWait {
            val rawName = "<html>Future VCS preset</html>"
            val initial =
                StudioDraftPolicy.edit(
                    draft(),
                    FieldAddress(vcsField("vcsBlamePreset")),
                    FieldValue.Present(RawValue.Text(rawName)),
                )
            var working = initial
            val section = section(stage = { working = it(working) })
            val root = section.content.component
            section.render(view(initial))
            val sliders = visibleSliders(root)
            assertEquals(4, sliders.size)
            sliders[0].value = 84
            sliders[3].value = 75
            val requested = StudioVcsProjection.settings(working.appearance)
            assertEquals(84, requested.intensities.getValue(VcsColorCategory.DIFF_VIEWER))
            assertEquals(75, requested.intensities.getValue(VcsColorCategory.CONFLICT_MARKERS))
            assertEquals(rawName, requested.presets.getValue(VcsSection.BLAME))
            assertEquals(initial.appearance.accent, working.appearance.accent)
            val presetHelp = descendants(root).filterIsInstance<JLabel>().single { rawName in it.toolTipText.orEmpty() }
            assertFalse(BasicHTML.isHTMLString(presetHelp.toolTipText), "The complete raw name must stay literal")
        }
    }

    @Test
    fun `the intensity readout matches the slider while preserving the saved value`() {
        SwingUtilities.invokeAndWait {
            val raw = -19
            val initial =
                StudioDraftPolicy.edit(
                    draft(),
                    FieldAddress(vcsField("vcsDiffIntensity")),
                    FieldValue.Present(RawValue.Integer(raw)),
                )
            val section = section()
            val root = section.content.component
            section.render(view(initial))
            val slider = visibleSliders(root).first()
            assertEquals(0, slider.value)
            val readout = descendants(root).filterIsInstance<JLabel>().single { it.text == slider.value.toString() }
            assertEquals("Saved intensity: $raw", readout.toolTipText)
            val stored = StudioVcsProjection.settings(initial.appearance)
            assertEquals(raw, stored.intensities.getValue(VcsColorCategory.DIFF_VIEWER))
        }
    }

    @Test
    fun `locked controls expose the existing groups and delegate Unlock to the host`() {
        SwingUtilities.invokeAndWait {
            val disabled = StudioVcsEditing.edit(draft(), StudioVcsEdit.Enable(false))
            var unlockRequests = 0
            val section = section(unlock = { unlockRequests++ })
            val root = section.content.component
            section.render(view(disabled).copy(isLicensed = false))
            assertFalse(master(root).isEnabled)
            val sliders = visibleSliders(root)
            assertEquals(5, sliders.size)
            assertTrue(sliders.none { it.isEnabled })
            sliders.first().value = 92
            val button = descendants(root).filterIsInstance<AbstractButton>().single { it.text == "Unlock" }
            button.doClick()
            assertEquals(1, unlockRequests)
            section.render(view(draft()).copy(isEditable = false))
            assertFalse(master(root).isEnabled)
            assertTrue(sliders.none { it.isEnabled })
            assertFalse(visibleWithin(button, root))
        }
    }

    @Test
    fun `screen reader preset edits remain independent and locked choices cannot change the draft`() {
        mockkStatic(ScreenReader::class)
        try {
            every { ScreenReader.isActive() } returns true
            SwingUtilities.invokeAndWait {
                val initial = draft()
                var working = initial
                val section = section(stage = { working = it(working) })
                val root = section.content.component
                section.render(view(initial))
                val presets = descendants(root).filterIsInstance<JComboBox<*>>().filter { visibleWithin(it, root) }
                assertEquals(3, presets.size)
                assertTrue(presets.all { it.selectedItem == VcsColorPreset.CUSTOM })
                presets[1].selectedItem = VcsColorPreset.NEON
                val requested = StudioVcsProjection.settings(working.appearance)
                assertEquals("NEON", requested.presets.getValue(VcsSection.MERGE))
                assertEquals("CUSTOM", requested.presets.getValue(VcsSection.DIFF))
                assertEquals("CUSTOM", requested.presets.getValue(VcsSection.BLAME))
                section.render(view(initial, working).copy(isLicensed = false))
                assertTrue(presets.none { it.isEnabled })
                presets[1].selectedItem = VcsColorPreset.WHISPER
                assertEquals(requested, StudioVcsProjection.settings(working.appearance))
            }
        } finally {
            unmockkStatic(ScreenReader::class)
        }
    }

    @Test
    fun `slider labels and fields fit the narrow tab in one consistent column`() {
        SwingUtilities.invokeAndWait {
            val section = section()
            val root = section.content.component
            section.render(view(draft()))
            val titles =
                VcsSection.entries
                    .flatMap { it.sliders }
                    .map { it.second }
                    .toSet()
            val fields = descendants(root).filterIsInstance<JLabel>().filter { it.text in titles }
            assertEquals(5, fields.size)
            for (width in listOf(480, 720, 1080)) {
                root.setSize(width, root.preferredSize.height)
                layout(root)
                val sliders = visibleSliders(root)
                val positions = sliders.map { SwingUtilities.convertPoint(it.parent, it.location, root).x }
                assertEquals(1, positions.distinct().size)
                for (field in fields) {
                    val left = SwingUtilities.convertPoint(field.parent, field.location, root).x
                    assertTrue(field.width >= field.getFontMetrics(field.font).stringWidth(field.text), field.text)
                    assertTrue(left >= 0 && left + field.width <= width, field.text)
                }
            }
        }
    }

    private fun draft(): DraftRecord {
        val initial = catalogTestWork(catalogTestDocument()).drafts.single()
        val enabled = StudioVcsEditing.edit(initial, StudioVcsEdit.Enable(true))
        val custom =
            VcsSection.entries.fold(enabled) { pending, group ->
                StudioVcsEditing.edit(pending, StudioVcsEdit.Preset(group, VcsColorPreset.CUSTOM))
            }
        return setDiffIntensity(custom, 18)
    }

    private fun setDiffIntensity(
        draft: DraftRecord,
        value: Int,
    ): DraftRecord =
        StudioVcsEditing.edit(
            draft,
            StudioVcsEdit.Intensity(VcsColorCategory.DIFF_VIEWER, VcsIntensity.of(value)),
        )

    private fun view(
        current: DraftRecord,
        draft: DraftRecord = current,
    ): StudioVcsView =
        StudioVcsView(
            AyuVariant.MIRAGE,
            current.appearance,
            draft.appearance,
            isEditable = true,
            isLicensed = true,
        )

    private fun section(
        stage: ((DraftRecord) -> DraftRecord) -> Unit = { error("Unexpected draft edit") },
        disclose: (VcsSection, Boolean) -> Unit = { _, _ -> error("Unexpected disclosure edit") },
        unlock: () -> Unit = { error("Unexpected unlock request") },
    ): StudioVcsSection = StudioVcsSection(VcsSection.entries.associateWith { true }, stage, disclose, unlock)

    private fun master(root: Container): JCheckBox = descendants(root).filterIsInstance<JCheckBox>().single()

    private fun diffColor(sample: VcsColorPreviewComponent): Color =
        sample.colorForTest(VcsColorCategory.DIFF_VIEWER, "DIFF_INSERTED", VcsWriteMode.COLOR_KEY)

    private fun visibleSliders(root: Container): List<JSlider> =
        descendants(root).filterIsInstance<JSlider>().filter { visibleWithin(it, root) }

    private fun visibleWithin(
        component: Component,
        root: Component,
    ): Boolean = component.isVisible && (component === root || visibleWithin(checkNotNull(component.parent), root))

    private fun layout(root: Container) {
        root.doLayout()
        root.components.filterIsInstance<Container>().forEach(::layout)
    }

    private fun descendants(root: Container): List<Component> =
        buildList {
            for (child in root.components) {
                add(child)
                if (child is Container) addAll(descendants(child))
            }
        }
}
