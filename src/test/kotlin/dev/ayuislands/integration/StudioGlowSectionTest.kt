package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.TitledSeparator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.GlowControls
import dev.ayuislands.settings.GlowGroupPanel
import dev.ayuislands.settings.GlowPanelEvent
import dev.ayuislands.settings.GlowPanelView
import dev.ayuislands.settings.GlowPreview
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioGlowEditing
import dev.ayuislands.studio.session.StudioGlowProjection
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.sessionRecord
import dev.ayuislands.studio.ui.StudioGlowSection
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioGlowSectionTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Before
    fun forbidAmbientEffects() {
        mockkObject(AyuIslandsSettings.Companion, LicenseChecker, GlowOverlayManager.Companion)
        every { AyuIslandsSettings.getInstance() } throws AssertionError("Glow controls must use explicit view data")
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Entitlement must be supplied explicitly")
        every { LicenseChecker.requestLicense(any()) } throws AssertionError("License actions belong to the host")
        every { GlowOverlayManager.getInstance(any()) } throws AssertionError("Native glow effects belong to Apply")
    }

    @After
    fun cleanup() {
        SwingUtilities.invokeAndWait {}
        unmockkAll()
    }

    @Test
    fun `shared controls preserve familiar groups and local contour without dispatching during render`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<GlowPanelEvent>()
            val controls = GlowControls(events::add)
            val initial = view()
            controls.render(initial)
            val sample = descendants(controls).filterIsInstance<GlowGroupPanel>().single()
            val targets = descendants(controls).filterIsInstance<TitledSeparator>().single { it.text == "Targets" }

            assertTrue(button(controls, "Enable glow").isSelected)
            val groups = descendants(controls).filterIsInstance<TitledSeparator>().map { it.text }
            assertTrue(groups.containsAll(listOf("Style", "Placement", "Targets")))
            assertTrue(labels(controls).contains("Shape"))
            assertTrue(labels(controls).containsAll(listOf("Movement", "Trace position", "Loop duration")))
            assertTrue(labels(controls).containsAll(listOf("Workspace", "Output", "VCS")))
            assertEquals(initial.preview.waveformConfig, sample.waveformConfig)
            assertTrue(sample.glowVisible)
            targets.dispatchEvent(
                MouseEvent(targets, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1),
            )
            assertTrue(button(controls, "Editor").isVisible)
            controls.render(initial.copy(settings = initial.settings.copy(enabled = false)))
            controls.render(initial)

            assertSame(sample, descendants(controls).filterIsInstance<GlowGroupPanel>().single())
            assertSame(
                targets,
                descendants(controls).filterIsInstance<TitledSeparator>().single { it.text == "Targets" },
            )
            assertTrue(button(controls, "Editor").isVisible)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `Studio current and draft change the actual contour while native edits only stage fields`() {
        SwingUtilities.invokeAndWait {
            var draft = sessionRecord(sessionDocument()).work.drafts.single()
            for (event in listOf(GlowPanelEvent.Enable(true), GlowPanelEvent.Shape(GlowShape.WAVEFORM))) {
                for (intent in StudioGlowEditing.edits(draft, event)) {
                    val edit = assertIs<SessionIntent.EditField>(intent)
                    draft = StudioDraftPolicy.edit(draft, edit.address, edit.value)
                }
            }
            val staged = mutableListOf<List<SessionIntent>>()
            val actions = mutableListOf<GlowPanelEvent>()
            val section = StudioGlowSection(staged::add, actions::add)
            val initial = view().copy(settings = StudioGlowProjection.settings(draft.appearance))
            val current = initial.preview.copy(color = Color(0xFFCC66), shape = GlowShape.SOLID)
            section.render(draft, initial, current)
            val sample = descendants(section.content.component).filterIsInstance<GlowGroupPanel>().single()
            sample.setSize(540, 360)
            val acceptedPixels = pixels(sample)

            section.content.preview(PreviewMode.DRAFT)
            assertEquals(GlowShape.WAVEFORM, sample.glowShape)
            assertEquals(initial.preview.color, sample.glowColor)
            assertFalse(acceptedPixels.contentEquals(pixels(sample)))
            section.content.preview(PreviewMode.CURRENT)
            assertTrue(acceptedPixels.contentEquals(pixels(sample)))
            assertTrue(staged.isEmpty())
            assertTrue(actions.isEmpty())

            val shape =
                descendants(section.content.component).filterIsInstance<JComboBox<*>>().single {
                    it.itemCount == GlowShape.entries.size && it.getItemAt(0) == GlowShape.entries.first().displayName
                }
            shape.selectedItem = GlowShape.SOLID.displayName
            val edit = assertIs<SessionIntent.EditField>(staged.single().single())
            assertEquals(draft.id, edit.draft)
            assertEquals("glowShape", edit.address.field.name)
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun `locked and readonly controls keep preview visible and route unlocking as an explicit action`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<GlowPanelEvent>()
            val controls = GlowControls(events::add)
            val initial = view()
            controls.render(initial.copy(isEditable = false, isLicensed = false))
            val sample = descendants(controls).filterIsInstance<GlowGroupPanel>().single()
            assertTrue(sample.glowVisible)
            assertFalse(button(controls, "Enable glow").isEnabled)
            button(controls, "Enable glow").doClick()
            assertTrue(events.isEmpty())
            button(controls, "Unlock Pro").doClick()
            assertEquals(listOf<GlowPanelEvent>(GlowPanelEvent.Unlock), events.toList())

            controls.render(initial.copy(isEditable = false))
            assertFalse(button(controls, "Enable glow").isEnabled)
            controls.render(initial)
            button(controls, "Enable glow").doClick()
            assertEquals(GlowPanelEvent.Enable(false), events.last())
            assertSame(sample, descendants(controls).filterIsInstance<GlowGroupPanel>().single())
        }
    }

    private fun view(): GlowPanelView {
        val settings =
            GlowSettings(
                enabled = true,
                shape = GlowShape.WAVEFORM,
                preset = GlowPreset.CUSTOM,
                islandToggles = mapOf("Editor" to true, "Project" to false),
            )
        return GlowPanelView(
            settings = settings,
            preview =
                GlowPreview(
                    shape = GlowShape.WAVEFORM,
                    style = GlowStyle.SHARP_NEON,
                    intensity = 80,
                    width = 10,
                    color = Color(0x5CCFE6),
                    visible = true,
                    waveformConfig = WaveformConfig(amplitude = 16, intensity = 100),
                ),
            isEditable = true,
            isLicensed = true,
            pendingBadges = emptySet(),
        )
    }

    private fun pixels(panel: GlowGroupPanel): IntArray {
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private fun button(
        container: Container,
        title: String,
    ): AbstractButton = descendants(container).filterIsInstance<AbstractButton>().single { it.text == title }

    private fun labels(container: Container): List<String> =
        descendants(container).filterIsInstance<JLabel>().map { it.text.orEmpty() }

    private fun descendants(root: Container): List<Component> =
        buildList {
            for (child in root.components) {
                add(child)
                if (child is Container) addAll(descendants(child))
            }
        }
}
