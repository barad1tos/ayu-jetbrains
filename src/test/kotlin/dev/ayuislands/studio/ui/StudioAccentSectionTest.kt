package dev.ayuislands.studio.ui

import com.intellij.ui.scale.JBUIScale
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.catalogTestWork
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.session.StudioDraftPolicy
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioAccentSectionTest {
    @Test
    fun `an accent gesture updates Draft immediately while Current and Cancel keep the accepted sample`() {
        SwingUtilities.invokeAndWait {
            val editor = AccentFixture()
            val sample = editor.sample
            val accepted = editor.view.accepted
            val acceptedPixels = pixels(sample)
            val preset = AYU_ACCENT_PRESETS.first()

            editor.section.content.preview(PreviewMode.DRAFT)
            assertContentEquals(acceptedPixels, pixels(sample))
            editor.swatch(preset.hex).doClick()

            assertEquals(RawValue.Text(preset.hex), editor.raw("mirageAccent"))
            val tracking = editor.view.draft.tracking
            assertEquals(listOf("mirageAccent"), tracking.touched.map { it.field.name })
            assertEquals(RotationAcceptanceOrigin.LOCAL_APPEARANCE_EDIT, tracking.rotationOrigin)
            assertEquals(accepted, editor.view.accepted)
            assertFalse(acceptedPixels.contentEquals(pixels(sample)))
            assertTrue(editor.swatch(preset.hex).isSelected)

            editor.section.content.preview(PreviewMode.CURRENT)
            assertContentEquals(acceptedPixels, pixels(sample))
            editor.render(editor.view.copy(draft = StudioDraftPolicy.discard(editor.view.draft)))
            editor.section.content.preview(PreviewMode.DRAFT)
            assertContentEquals(acceptedPixels, pixels(sample))
            assertSame(sample, editor.sample)
            assertEquals(1, editor.edits.size)
            val discarded = editor.view.draft
            assertTrue(discarded.tracking.touched.isEmpty())
        }
    }

    @Test
    fun `host profile selection updates only the draft sample and preserves raw hex when selected again`() {
        SwingUtilities.invokeAndWait {
            val editor = AccentFixture()
            val preset = AYU_ACCENT_PRESETS.first()
            val accepted = pixels(editor.sample)
            val stored = " ${preset.hex.lowercase()} "
            val selected = editor.setColor("mirageAccent", RawValue.Text(stored))
            val profileDraft = selected.copy(tracking = DraftTracking(selected.appearance))
            editor.render(editor.view.copy(draft = profileDraft))
            assertContentEquals(accepted, pixels(editor.sample))
            editor.section.content.preview(PreviewMode.DRAFT)
            assertFalse(accepted.contentEquals(pixels(editor.sample)))
            assertTrue(editor.swatch(preset.hex).isSelected)
            editor.swatch(preset.hex).doClick()
            assertEquals(RawValue.Text(stored), editor.raw("mirageAccent"))
            assertTrue(editor.edits.isEmpty())

            editor.swatch(AYU_ACCENT_PRESETS[1].hex).doClick()
            editor.swatch(preset.hex).doClick()
            assertEquals(RawValue.Text(stored), editor.raw("mirageAccent"))
            assertEquals(selected.appearance, editor.view.draft.appearance)
            assertFalse(StudioDraftPolicy.isDirty(editor.view.draft))
        }
    }

    @Test
    fun `manual palette stays disabled for read-only and system-controlled views`() {
        SwingUtilities.invokeAndWait {
            val editor = AccentFixture()
            val before = pixels(editor.sample)
            editor.render(editor.view.copy(isEditable = false))
            assertTrue(editor.swatches.none { it.isEnabled })
            editor.button("Custom…").doClick()
            assertFalse(editor.button("Shuffle").isEnabled)
            assertTrue(editor.pickerStarts.isEmpty())

            editor.render(editor.view.copy(isEditable = true, systemPolicy = SystemAccentPolicy.FORCE_ON))
            assertTrue(editor.swatches.none { it.isEnabled })
            editor.swatches.first().doClick()
            assertTrue(editor.edits.isEmpty())
            assertContentEquals(before, pixels(editor.sample))

            editor.render(editor.view.copy(systemPolicy = SystemAccentPolicy.FORCE_OFF))
            assertTrue(editor.swatches.all { it.isEnabled })
        }
    }

    @Test
    fun `custom and shuffle edit the selected variant and discard a stale picker result`() {
        SwingUtilities.invokeAndWait {
            val editor = AccentFixture()
            editor.render(editor.view.copy(variant = AyuVariant.DARK))
            val originalMirage = editor.raw("mirageAccent")

            editor.button("Custom…").doClick()
            assertEquals(listOf(AyuVariant.DARK.defaultAccent), editor.pickerStarts.map { it.value })
            assertTrue(editor.edits.isEmpty())

            editor.custom = { AccentHex.require("#123ABC") }
            editor.button("Custom…").doClick()
            assertEquals(RawValue.Text("#123ABC"), editor.raw("darkAccent"))
            assertEquals(originalMirage, editor.raw("mirageAccent"))
            assertEquals(1, editor.edits.size)

            editor.custom = {
                editor.render(editor.view.copy(variant = AyuVariant.LIGHT))
                AccentHex.require("#ABC123")
            }
            editor.button("Custom…").doClick()
            assertEquals(1, editor.edits.size)
            editor.button("Shuffle").doClick()
            assertEquals(RawValue.Text("#596874"), editor.raw("lightAccent"))
            assertEquals(RawValue.Text("#123ABC"), editor.raw("darkAccent"))
            assertEquals(listOf(AyuVariant.LIGHT), editor.shuffled)
        }
    }

    @Test
    fun `the sample falls back safely and every named swatch fits in two rows`() {
        SwingUtilities.invokeAndWait {
            val editor = AccentFixture()
            val fallback = pixels(editor.sample)
            editor.render(editor.view.copy(draft = editor.setColor("mirageAccent", RawValue.Text("unrecognized"))))
            editor.section.content.preview(PreviewMode.DRAFT)
            assertContentEquals(fallback, pixels(editor.sample))
            assertEquals(RawValue.Text("unrecognized"), editor.raw("mirageAccent"))
            assertTrue(editor.edits.isEmpty())
            assertEquals(StudioSettingsSection.ACCENT, editor.section.content.section)
            val content = editor.section.content.component
            for (width in listOf(340, 720)) {
                content.setSize(width, content.preferredSize.height)
                layout(content)
                val swatches = editor.swatches
                val rows = swatches.groupBy { it.y }
                assertEquals(listOf(6, 6), rows.values.map { it.size })
                assertTrue(swatches.all { it.width > 0 && it.x + it.width <= it.parent.width })
                assertTrue(swatches.all { it.isFocusable && it.accessibleContext.accessibleName.isNotBlank() })
                assertTrue(editor.sample.width in 1..width)
                val preview = editor.sample.components.last()
                val swatchX = SwingUtilities.convertPoint(swatches.first(), Point(), content).x
                val previewX = SwingUtilities.convertPoint(preview, Point(), content).x
                assertTrue(abs(swatchX - previewX) <= 2, "Palette and sample should share a left edge")
            }
        }
    }

    @Test
    fun `swatch icons scale once with the native IDE scale`() {
        SwingUtilities.invokeAndWait {
            val originalScale = JBUIScale.scale(1f)
            try {
                JBUIScale.setUserScaleFactorForTest(1f)
                val standardIcon = AccentFixture().swatches.first().icon
                val standardWidth = standardIcon.iconWidth
                JBUIScale.setUserScaleFactorForTest(2f)
                assertEquals(standardWidth * 2, standardIcon.iconWidth)
                val editor = AccentFixture()
                val swatch = editor.swatches.first()
                assertEquals(standardWidth * 2, swatch.icon.iconWidth)
                swatch.doClick()
                val customIcon = editor.button("Custom…").icon
                assertEquals(standardWidth * 2, customIcon.iconWidth)
            } finally {
                JBUIScale.setUserScaleFactorForTest(originalScale)
            }
        }
    }

    private class AccentFixture {
        private val document = catalogTestDocument()
        private val startingDraft = catalogTestWork(document).drafts.single()
        var view =
            StudioAccentView(
                variant = AyuVariant.MIRAGE,
                draft = startingDraft,
                accepted = document.currents.global.appearance,
                systemPolicy = SystemAccentPolicy.BY_PROFILE,
                isEditable = true,
            )
            private set
        val edits = mutableListOf<DraftRecord>()
        val pickerStarts = mutableListOf<AccentHex>()
        val shuffled = mutableListOf<AyuVariant>()
        var custom: () -> AccentHex? = { null }
        val section =
            StudioAccentSection(
                onDraftChanged = {
                    edits += it
                    render(view.copy(draft = it))
                },
                chooseCustom = {
                    pickerStarts += it
                    custom()
                },
                shuffleColor = {
                    shuffled += it
                    AccentHex.require("#596874")
                },
            ).also { it.render(view) }

        val sample: JComponent
            get() =
                descendants(section.content.component)
                    .filterIsInstance<JComponent>()
                    .single { it.accessibleContext?.accessibleName == "Global accent sample" }

        val swatches: List<JToggleButton>
            get() = descendants(section.content.component).filterIsInstance<JToggleButton>()

        fun render(next: StudioAccentView) {
            view = next
            section.render(next)
        }

        fun swatch(hex: String): JToggleButton =
            swatches.single { it.accessibleContext.accessibleName.endsWith("($hex)") }

        fun button(text: String): JButton =
            descendants(section.content.component).filterIsInstance<JButton>().single { it.text == text }

        fun raw(name: String): RawValue = field(name).value

        fun setColor(
            name: String,
            color: RawValue,
        ): DraftRecord = StudioDraftPolicy.edit(view.draft, FieldAddress(field(name).key), FieldValue.Present(color))

        private fun field(name: String): StudioField {
            val appearance = view.draft.appearance
            return appearance.accent.records.single { it.key.name == name }
        }
    }
}

private fun descendants(parent: Container): List<Component> =
    parent.components.flatMap { child -> listOf(child) + if (child is Container) descendants(child) else emptyList() }

private fun layout(parent: Container) {
    parent.doLayout()
    parent.components.filterIsInstance<Container>().forEach(::layout)
}

private fun pixels(component: JComponent): IntArray {
    val width = 360
    val height = component.preferredSize.height
    component.setSize(width, height)
    layout(component)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        component.paint(graphics)
    } finally {
        graphics.dispose()
    }
    return image.getRGB(0, 0, width, height, null, 0, width)
}
