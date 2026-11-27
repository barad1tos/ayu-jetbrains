package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.components.ActionLink
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontStatus
import dev.ayuislands.font.FontWeight
import dev.ayuislands.settings.FontPreviewComponent
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.catalogTestWork
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.StudioFontEdit
import dev.ayuislands.studio.session.StudioFontEditing
import dev.ayuislands.studio.session.StudioFontProjection
import dev.ayuislands.studio.ui.StudioCuratedFont
import dev.ayuislands.studio.ui.StudioFontAction
import dev.ayuislands.studio.ui.StudioFontAppearance
import dev.ayuislands.studio.ui.StudioFontAvailability
import dev.ayuislands.studio.ui.StudioFontOperation
import dev.ayuislands.studio.ui.StudioFontSection
import dev.ayuislands.studio.ui.StudioFontView
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioFontSectionTest {
    @get:Rule
    val app = ApplicationRule()

    @Test
    fun `current and draft show different typography in the same sample before apply`() =
        inSection { fixture ->
            val manager = EditorColorsManager.getInstance()
            val scheme = manager.globalScheme
            val originalName = scheme.editorFontName
            val originalSize = scheme.editorFontSize
            fixture.edit(StudioFontEdit.Size(28f))
            fixture.edit(StudioFontEdit.Spacing(1.9f))
            fixture.render()

            val sample = fixture.preview
            val currentHeight = sample.preferredSize.height
            val currentPixels = paint(sample)
            fixture.section.content.preview(PreviewMode.DRAFT)
            assertSame(sample, fixture.preview)
            assertTrue(sample.preferredSize.height > currentHeight)
            assertFalse(currentPixels.contentEquals(paint(sample)))
            fixture.section.content.preview(PreviewMode.CURRENT)
            assertEquals(currentHeight, sample.preferredSize.height)
            assertEquals(0, fixture.emittedEdits)
            assertSame(scheme, manager.globalScheme)
            assertEquals(originalName, scheme.editorFontName)
            assertEquals(originalSize, scheme.editorFontSize)
        }

    @Test
    fun `native controls edit the latest draft and keep the console choice independent`() =
        inSection { fixture ->
            fixture.render()
            fixture.selectPreset(FontPreset.CUSTOM)
            fixture.render()
            fixture.combo("Font").selectedItem = "Monospaced"
            fixture.spinner("Size").value = 17.5
            fixture.spinner("Spacing").value = 1.6
            fixture.combo("Weight").selectedItem = FontWeight.MEDIUM
            fixture.toggle("Ligatures").doClick()
            fixture.toggle("Also apply to console").doClick()

            val settings = checkNotNull(StudioFontProjection.settings(fixture.draft.appearance).resolved)
            assertEquals(FontPreset.CUSTOM, settings.preset)
            assertEquals("Monospaced", settings.fontFamily)
            assertEquals(17.5f, settings.fontSize)
            assertEquals(1.6f, settings.lineSpacing)
            assertEquals(FontWeight.MEDIUM, settings.weight)
            assertFalse(settings.enableLigatures)
            assertTrue(settings.applyToConsole)
            fixture.link("Reapply preset").doClick()
            assertEquals(1, fixture.draft.tracking.reapply.size)

            fixture.render(editable = false)
            assertFalse(fixture.spinner("Size").isEnabled)
            assertFalse(fixture.toggle("Apply font preset").isEnabled)
            assertEquals(8, fixture.emittedEdits)
        }

    @Test
    fun `installation gestures describe the observed health and leave the draft unchanged`() =
        inSection { fixture ->
            fixture.render()
            assertTrue(fixture.showsSummary("Maple Mono NF"))
            assertTrue(fixture.link("Reinstall").isShowingInSection())
            assertTrue(fixture.link("Delete").isShowingInSection())
            fixture.link("Delete").doClick()

            fixture.edit(StudioFontEdit.Preset(FontPreset.NEON))
            fixture.render()
            assertTrue(fixture.link("Install font").isShowingInSection())
            fixture.link("Install font").doClick()

            fixture.edit(StudioFontEdit.Preset(FontPreset.WHISPER))
            fixture.render()
            assertTrue(fixture.link("Reinstall").isShowingInSection())
            assertFalse(fixture.link("Delete").isShowingInSection())
            fixture.link("Reinstall").doClick()

            assertEquals(
                listOf<StudioFontAction>(
                    StudioFontAction.ManageFont(FontPreset.AMBIENT, StudioFontOperation.DELETE),
                    StudioFontAction.ManageFont(FontPreset.NEON, StudioFontOperation.INSTALL),
                    StudioFontAction.ManageFont(FontPreset.WHISPER, StudioFontOperation.REINSTALL),
                ),
                fixture.actions,
            )
            assertEquals(0, fixture.emittedEdits)
        }

    private fun inSection(test: (Fixture) -> Unit) {
        SwingUtilities.invokeAndWait { test(Fixture()) }
    }

    private fun paint(sample: FontPreviewComponent): IntArray {
        val image = BufferedImage(560, 320, BufferedImage.TYPE_INT_ARGB)
        sample.setSize(image.width, image.height)
        val graphics = image.createGraphics()
        try {
            sample.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private class Fixture {
        private val initial = catalogTestWork(catalogTestDocument()).drafts.single()
        var draft = StudioFontEditing.edit(initial, StudioFontEdit.Enable(true))
            private set
        private val accepted = draft.appearance
        var emittedEdits = 0
            private set
        val actions = mutableListOf<StudioFontAction>()
        val section =
            StudioFontSection(
                customizeExpanded = true,
                stage = { update: (DraftRecord) -> DraftRecord ->
                    draft = update(draft)
                    emittedEdits++
                },
                onAction = { actions.add(it) },
            )

        private val components get() = descendants(section.content.component)
        val preview get() = components.filterIsInstance<FontPreviewComponent>().single()

        fun render(editable: Boolean = true) {
            section.render(
                StudioFontView(
                    current = StudioFontAppearance(accepted, Color(0xFFCC66)),
                    draft = StudioFontAppearance(draft.appearance, Color(0x5CCFE6)),
                    availability =
                        StudioFontAvailability(
                            curated =
                                mapOf(
                                    FontPreset.AMBIENT to StudioCuratedFont(FontStatus.HEALTHY, "Maple Mono NF"),
                                    FontPreset.NEON to StudioCuratedFont(FontStatus.NOT_INSTALLED, null),
                                    FontPreset.WHISPER to StudioCuratedFont(FontStatus.CORRUPTED, null),
                                ),
                            families = listOf("Monospaced", "Dialog"),
                        ),
                    editable = editable,
                ),
            )
        }

        fun edit(edit: StudioFontEdit) {
            draft = StudioFontEditing.edit(draft, edit)
        }

        fun selectPreset(preset: FontPreset) {
            val segment = components.single { it.accessibleContext?.accessibleName == preset.displayName }
            assertTrue(segment.accessibleContext.accessibleAction.doAccessibleAction(0))
        }

        fun toggle(text: String) = components.filterIsInstance<JCheckBox>().single { it.text == text }

        fun link(text: String) = components.filterIsInstance<ActionLink>().single { it.text == text }

        fun showsSummary(family: String): Boolean =
            components.filterIsInstance<JLabel>().any { it.text?.startsWith("$family · ") == true }

        fun spinner(label: String) = field(label) as JSpinner

        fun combo(label: String) = field(label) as JComboBox<*>

        private fun field(text: String): Component =
            checkNotNull(components.filterIsInstance<JLabel>().single { it.text == text }.labelFor)
    }
}

private fun descendants(component: Component): Sequence<Component> =
    sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }

private fun Component.isShowingInSection(): Boolean = generateSequence(this) { it.parent }.all(Component::isVisible)
