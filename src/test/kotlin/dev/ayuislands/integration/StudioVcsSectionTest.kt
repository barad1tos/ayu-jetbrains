package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.TitledSeparator
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.VcsColorPreviewComponent
import dev.ayuislands.settings.VcsControls
import dev.ayuislands.settings.VcsPanelEvent
import dev.ayuislands.settings.VcsPanelView
import dev.ayuislands.settings.VcsPreviewInput
import dev.ayuislands.settings.VcsPreviewIntensities
import dev.ayuislands.settings.VcsSection
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioVcsEditing
import dev.ayuislands.studio.session.StudioVcsProjection
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.sessionRecord
import dev.ayuislands.studio.ui.StudioVcsSection
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioVcsSectionTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Before
    fun forbidAmbientEffects() {
        mockkObject(AyuIslandsSettings.Companion, LicenseChecker)
        every { AyuIslandsSettings.getInstance() } throws AssertionError("VCS controls require explicit input")
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Entitlement belongs to the host")
        every { LicenseChecker.requestLicense(any()) } throws AssertionError("License actions belong to the host")
    }

    @After
    fun cleanup() = unmockkAll()

    @Test
    fun `unsupported raw presets stay unselected with literal full descriptions and no edits`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<VcsPanelEvent>()
            val input = preview(72)
            val controls = VcsControls(input, expanded(), events::add)
            val known = view()
            val rawName = "<html>Future VCS preset with a complete user-defined name</html>"
            val unknown =
                known.copy(
                    settings = known.settings.copy(diffPreset = rawName, mergePreset = null, blamePreset = ""),
                )
            controls.render(unknown, input)
            val field = VcsControls::class.java.getDeclaredField("presets").apply { isAccessible = true }
            val selectors = (field.get(controls) as Map<*, *>).values.map { it as SegmentedButton<*> }
            assertEquals(3, selectors.size)
            selectors.forEach { assertNull(it.selectedItem, "Unknown input must not claim that Ambient is selected") }
            val labels = descendants(controls).filterIsInstance<JLabel>()
            val description = labels.single { it.text.orEmpty().contains(rawName) }
            assertTrue(visibleWithin(description, controls))
            assertTrue(description.toolTipText.orEmpty().contains(rawName))
            assertTrue(
                description.accessibleContext.accessibleName
                    .orEmpty()
                    .contains(rawName),
            )
            assertNull(description.getClientProperty("html"), "A raw preset name must remain literal text")
            assertTrue(
                labels.any { visibleWithin(it, controls) && it.text.orEmpty().contains("No preset is selected") },
            )
            assertTrue(labels.any { visibleWithin(it, controls) && it.text.orEmpty().contains("Preset ''") })
            assertTrue(events.isEmpty())
            for (contentWidth in listOf(360, 760, 1200)) {
                controls.setSize(contentWidth, 1200)
                layoutTree(controls)
                val location = SwingUtilities.convertPoint(description, 0, 0, controls)
                assertTrue(description.width > 0)
                assertTrue(location.x >= 0 && location.x + description.width <= contentWidth)
                assertTrue(description.text.contains(rawName), "Visual elision must preserve the complete raw name")
            }

            controls.render(known, input)
            selectors.forEach { assertEquals(VcsColorPreset.CUSTOM, it.selectedItem) }
            assertFalse(visibleWithin(description, controls))
            assertTrue(events.isEmpty())
            assertEquals(rawName, unknown.settings.diffPreset)
            assertEquals(null, unknown.settings.mergePreset)
            assertEquals("", unknown.settings.blamePreset)
        }
    }

    @Test
    fun `shared controls retain familiar groups expansion identity and raw readouts without render events`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<VcsPanelEvent>()
            val input = preview(72)
            val controls = VcsControls(input, expanded(), events::add)
            val initial = view()
            val raw =
                initial.copy(
                    settings =
                        initial.settings.copy(
                            intensities =
                                initial.settings.intensities +
                                    (VcsColorCategory.DIFF_VIEWER to -19),
                        ),
                )
            controls.render(raw, input)
            val groups = descendants(controls).filterIsInstance<TitledSeparator>()
            val sliders = descendants(controls).filterIsInstance<JSlider>()
            assertEquals(VcsSection.entries.map { it.title }, groups.map { it.text })
            assertEquals(5, sliders.size)
            assertTrue(sliders.all { it.isVisible && it.isEnabled })
            assertTrue(descendants(controls).filterIsInstance<JLabel>().any { it.text == "-19" })
            assertTrue(events.isEmpty())
            val merge = groups.single { it.text == VcsSection.MERGE.title }
            toggle(merge)
            assertEquals(listOf<VcsPanelEvent>(VcsPanelEvent.Expanded(VcsSection.MERGE, false)), events)
            events.clear()
            controls.render(raw.copy(settings = raw.settings.copy(enabled = false)), input.copy(isVisible = false))
            controls.render(raw, input)
            assertEquals(groups, descendants(controls).filterIsInstance<TitledSeparator>())
            assertEquals(sliders, descendants(controls).filterIsInstance<JSlider>())
            assertFalse(visibleWithin(sliders[3], controls), "A profile render must preserve the collapsed Merge group")
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `Current and Draft update the actual VCS sample and disabled Current describes intent separately`() {
        SwingUtilities.invokeAndWait {
            val draft = draft()
            val stage = mutableListOf<List<SessionIntent>>()
            val actions = mutableListOf<VcsPanelEvent>()
            val current = preview(18)
            val requested = preview(91)
            val section = StudioVcsSection(current, expanded(), stage::add, actions::add)
            val view =
                VcsPanelView(StudioVcsProjection.settings(draft.appearance), isEditable = true, isLicensed = true)
            section.render(draft, view, current, requested)
            val root = section.content.component
            val sample = descendants(root).filterIsInstance<VcsColorPreviewComponent>().single()
            assertEquals(18, sample.intensityForTest(VcsColorCategory.DIFF_VIEWER))
            section.content.preview(PreviewMode.DRAFT)
            assertEquals(91, sample.intensityForTest(VcsColorCategory.DIFF_VIEWER))
            section.content.preview(PreviewMode.CURRENT)
            assertEquals(18, sample.intensityForTest(VcsColorCategory.DIFF_VIEWER))
            section.render(draft, view, current.copy(isEnabled = false), requested)
            val disabled = descendants(root).filterIsInstance<JLabel>().single { it.text == "VCS customization is off" }
            assertTrue(visibleWithin(disabled, root))
            assertFalse(sample.isVisible)
            assertTrue(
                master(root).isSelected,
                "The Draft controls remain independent from the disabled Current sample",
            )
            section.content.preview(PreviewMode.DRAFT)
            assertTrue(sample.isVisible)
            assertFalse(visibleWithin(disabled, root))
            assertSame(sample, descendants(root).filterIsInstance<VcsColorPreviewComponent>().single())
            assertTrue(stage.isEmpty())
            assertTrue(actions.isEmpty())
            master(root).doClick()
            assertEquals(StudioVcsEditing.edits(draft, VcsPanelEvent.Enabled(false)), stage.single())
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun `native controls fit narrow and wide panels and lock without hiding the explicit premium sample`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<VcsPanelEvent>()
            val input = preview(74)
            val controls = VcsControls(input, expanded(), events::add)
            val initial = view()
            controls.render(initial, input)
            val sliders = descendants(controls).filterIsInstance<JSlider>()
            val labels =
                descendants(controls).filterIsInstance<JLabel>().filter { label ->
                    VcsSection.entries.any { section -> section.sliders.any { it.second == label.text } }
                }
            assertEquals(5, labels.size)
            for (width in listOf(360, 760, 1200)) {
                controls.setSize(width, 1500)
                repeat(3) { layoutTree(controls) }
                (labels + sliders).forEach { field ->
                    val location = SwingUtilities.convertPoint(field, 0, 0, controls)
                    assertTrue(location.x >= 0 && location.x + field.width <= width, "Whole VCS field must fit $width")
                    assertTrue(field.width >= field.minimumSize.width)
                }
                labels.forEach { assertTrue(it.width >= it.getFontMetrics(it.font).stringWidth(it.text)) }
            }
            controls.render(initial.copy(isEditable = false), input)
            assertFalse(master(controls).isEnabled)
            assertTrue(sliders.all { !it.isEnabled })
            controls.render(initial.copy(isLicensed = false, settings = initial.settings.copy(enabled = false)), input)
            assertTrue(descendants(controls).filterIsInstance<VcsColorPreviewComponent>().single().isVisible)
            assertTrue(sliders.all { visibleWithin(it, controls) && !it.isEnabled })
            assertTrue(events.isEmpty())
        }
    }

    private fun draft() =
        listOf(VcsPanelEvent.Enabled(true))
            .plus(VcsSection.entries.map { VcsPanelEvent.Preset(it, VcsColorPreset.CUSTOM) })
            .fold(sessionRecord(sessionDocument()).work.drafts.single()) { current, event ->
                StudioVcsEditing.edits(current, event).fold(current) { pending, intent ->
                    val edit = assertIs<SessionIntent.EditField>(intent)
                    StudioDraftPolicy.edit(pending, edit.address, edit.value)
                }
            }

    private fun view() =
        VcsPanelView(StudioVcsProjection.settings(draft().appearance), isEditable = true, isLicensed = true)

    private fun expanded() = VcsSection.entries.associateWith { true }

    private fun preview(value: Int) =
        VcsPreviewInput(
            AyuVariant.MIRAGE,
            VcsPreviewIntensities(value, value, value, value, value),
            isEnabled = true,
            isVisible = true,
        )

    private fun master(root: Container): AbstractButton =
        descendants(root).filterIsInstance<AbstractButton>().single { it.text == "Enable VCS color customization" }

    private fun toggle(separator: TitledSeparator) {
        separator.dispatchEvent(
            MouseEvent(separator, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1),
        )
    }

    private fun visibleWithin(
        component: Component,
        root: Container,
    ): Boolean {
        var current: Component? = component
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return current === root && root.isVisible
    }

    private fun descendants(root: Container): List<Component> =
        buildList {
            for (child in root.components) {
                add(child)
                if (child is Container) addAll(descendants(child))
            }
        }

    private fun layoutTree(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutTree)
    }
}
