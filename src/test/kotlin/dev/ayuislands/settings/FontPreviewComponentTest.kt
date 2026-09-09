package dev.ayuislands.settings

import com.intellij.util.ui.JBUI
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontWeight
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FontPreviewComponentTest {
    @Test
    fun `unavailable presets show the family the user selected`() =
        onEdt {
            withDialogResolution {
                val preview = FontPreviewComponent()
                preview.updateFontFamily("Previous Custom Family")
                preview.updatePreset(FontPreset.WHISPER, installed = false)
                assertEquals(
                    listOf("Install Victor Mono to preview"),
                    record(preview).fallbackTexts,
                    "A curated preset must advertise its canonical family, not stale custom state",
                )

                preview.updatePreset(FontPreset.CUSTOM, installed = false)
                preview.updateFontFamily("Iosevka Custom")
                assertEquals(listOf("Install Iosevka Custom to preview"), record(preview).fallbackTexts)

                preview.updateFontFamily("  ")
                assertEquals(listOf("Choose a font family to preview"), record(preview).fallbackTexts)
            }
        }

    @Test
    fun `install status replaces fallback with code and restores the selected fallback`() =
        onEdt {
            val preview = FontPreviewComponent()
            preview.updatePreset(FontPreset.CUSTOM, installed = false)
            preview.updateFontFamily("Iosevka Custom")
            assertEquals(listOf("Install Iosevka Custom to preview"), record(preview).fallbackTexts)

            preview.updatePreset(FontPreset.CUSTOM, installed = true)
            val installed = record(preview)
            assertEquals(SAMPLE_CODE, installed.codeTexts)
            assertTrue(installed.fallbackTexts.isEmpty(), "Installed state must remove the stale install message")

            preview.updatePreset(FontPreset.CUSTOM, installed = false)
            assertEquals(listOf("Install Iosevka Custom to preview"), record(preview).fallbackTexts)
        }

    @Test
    fun `typography updates code font attributes and removes disabled ligatures`() =
        onEdt {
            val preview = FontPreviewComponent()
            preview.updatePreset(FontPreset.CUSTOM, installed = true)
            preview.updateFontFamily(Font.MONOSPACED)
            preview.updateSettings(30f, 1.4f, true, FontWeight.SEMI_BOLD)

            val enabledFont = record(preview).codeDraws.first().font
            assertEquals(Font.MONOSPACED, enabledFont.attributes[TextAttribute.FAMILY])
            assertEquals(30f, enabledFont.attributes[TextAttribute.SIZE])
            assertEquals(FontWeight.SEMI_BOLD.textAttributeValue, enabledFont.attributes[TextAttribute.WEIGHT])
            assertEquals(TextAttribute.LIGATURES_ON, enabledFont.attributes[TextAttribute.LIGATURES])

            preview.updateSettings(30f, 1.4f, false, FontWeight.LIGHT)
            val disabledFont = record(preview).codeDraws.first().font
            assertEquals(FontWeight.LIGHT.textAttributeValue, disabledFont.attributes[TextAttribute.WEIGHT])
            assertFalse(
                disabledFont.attributes.containsKey(TextAttribute.LIGATURES),
                "Disabling ligatures must remove the enabled font attribute",
            )
        }

    @Test
    fun `curated and custom switches preserve their respective family sources`() =
        onEdt {
            withDialogResolution {
                val preview = FontPreviewComponent()
                preview.updatePreset(FontPreset.WHISPER, installed = true)
                assertEquals(Font.DIALOG, record(preview).codeFamily)

                preview.updatePreset(FontPreset.CUSTOM, installed = true)
                preview.updateFontFamily(Font.MONOSPACED)
                assertEquals(Font.MONOSPACED, record(preview).codeFamily)

                every { FontDetector.resolveFamily(any()) } returns null
                preview.updatePreset(FontPreset.NEON, installed = true)
                assertEquals(FontPreset.NEON.fontFamily, record(preview).codeFamily)
            }
        }

    @Test
    fun `large typography expands both layout sizes and keeps code rows separated`() =
        onEdt {
            val preview = FontPreviewComponent()
            val defaultPreferred = preview.preferredSize
            val defaultMinimum = preview.minimumSize
            preview.updateFontFamily(Font.MONOSPACED)
            preview.updateSettings(30f, 0.2f, true, FontWeight.SEMI_BOLD)

            assertTrue(preview.preferredSize.height > defaultPreferred.height)
            assertTrue(preview.minimumSize.height > defaultMinimum.height)
            assertEquals(preview.preferredSize.height, preview.minimumSize.height)

            val draws = record(preview).codeDraws
            val metrics = realMetrics(draws.first().font)
            val baselines = draws.map { it.y }
            assertTrue(
                baselines.zipWithNext().all { (first, second) -> second - first >= metrics.height },
                "Line spacing below one must still keep code rows at least one font-metric height apart",
            )
            assertTrue(baselines.last() + metrics.descent <= preview.preferredSize.height)

            val flooredHeight = preview.preferredSize.height
            val flooredGap = baselines[1] - baselines[0]
            preview.updateSettings(30f, 2f, true, FontWeight.SEMI_BOLD)
            val expandedDraws = record(preview).codeDraws
            assertTrue(preview.preferredSize.height > flooredHeight, "Larger spacing must increase layout height")
            assertTrue(
                expandedDraws[1].y - expandedDraws[0].y > flooredGap,
                "Larger spacing must increase the visible gap between code rows",
            )
        }

    @Test
    fun `narrow preview hides project labels while wide preview separates both columns`() =
        onEdt {
            val preview = FontPreviewComponent()
            preview.updateFontFamily(Font.MONOSPACED)

            val narrow = record(preview, Dimension(360, preview.preferredSize.height))
            assertTrue(narrow.projectTexts.isEmpty())
            assertEquals(SAMPLE_CODE, narrow.codeTexts)

            val wide = record(preview, preview.preferredSize)
            assertEquals(PROJECT_LABELS, wide.projectTexts)
            assertEquals(SAMPLE_CODE, wide.codeTexts)
            assertTrue(
                wide.projectDraws.maxOf { it.x } < wide.codeDraws.minOf { it.x },
                "Project and code text must occupy separate columns",
            )
        }

    @Test
    fun `narrow large code is clipped without changing caller graphics state`() =
        onEdt {
            val preview = FontPreviewComponent()
            preview.updateFontFamily(Font.MONOSPACED)
            preview.updateSettings(72f, 1f, true, FontWeight.REGULAR)
            val size = Dimension(360, preview.preferredSize.height)
            val recording = record(preview, size)
            val codeClip = recording.codeDraws.first().clip
            requireNotNull(codeClip)
            val padding = JBUI.scale(PREVIEW_PADDING)
            val expectedClip =
                Rectangle(
                    padding + JBUI.scale(GUTTER_WIDTH),
                    padding,
                    size.width - JBUI.scale(GUTTER_WIDTH) - padding * 2,
                    size.height - padding * 2,
                )

            assertTrue(recording.codeDraws.all { it.clip == codeClip })
            assertEquals(expectedClip, codeClip, "Code must be clipped exactly between the gutter and right padding")

            val installedImage = renderRaster(preview, size)
            val unavailablePreview = FontPreviewComponent()
            unavailablePreview.updatePreset(FontPreset.CUSTOM, installed = false)
            unavailablePreview.updateFontFamily(Font.MONOSPACED)
            unavailablePreview.updateSettings(72f, 1f, true, FontWeight.REGULAR)
            val unavailableImage = renderRaster(unavailablePreview, size)
            val gutter = Rectangle(padding, padding, JBUI.scale(GUTTER_WIDTH), size.height - padding * 2)
            val rightPadding = Rectangle(size.width - padding, padding, padding, size.height - padding * 2)
            assertEquals(
                unavailableImage.pixels(gutter),
                installedImage.pixels(gutter),
                "Large code must not paint over the editor gutter",
            )
            assertEquals(
                unavailableImage.pixels(rightPadding),
                installedImage.pixels(rightPadding),
                "Long code must not paint over the right padding and border",
            )
            assertNotEquals(
                unavailableImage.pixels(expectedClip),
                installedImage.pixels(expectedClip),
                "Installed code must still render inside its content area",
            )

            val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            val originalClip = Rectangle(3, 4, size.width - 8, size.height - 9)
            val originalFont = Font(Font.DIALOG, Font.BOLD, 17)
            graphics.clip = originalClip
            graphics.color = Color.MAGENTA
            graphics.font = originalFont
            try {
                preview.size = size
                preview.paint(graphics)
                assertEquals(originalClip, graphics.clipBounds)
                assertEquals(Color.MAGENTA, graphics.color)
                assertEquals(originalFont, graphics.font)
                assertTrue(
                    image.distinctRgbCount() > MIN_RENDER_COLORS,
                    "Real raster must contain the preview surfaces",
                )
                assertNotEquals(image.getRGB(8, 8), image.getRGB(size.width / 2, size.height / 2))
                assertEquals(0, image.getRGB(0, 0), "Painting must remain inside the caller clip")
            } finally {
                graphics.dispose()
            }
        }

    @Test
    fun `equivalent update replay produces equivalent unavailable and installed output`() =
        onEdt {
            fun configuredPreview(installed: Boolean): FontPreviewComponent =
                FontPreviewComponent().apply {
                    updatePreset(FontPreset.CUSTOM, installed = installed)
                    updateFontFamily(Font.MONOSPACED)
                    updateSettings(18f, 1.6f, true, FontWeight.MEDIUM)
                }

            val firstInstalled = record(configuredPreview(installed = true))
            val replayedInstalled = record(configuredPreview(installed = true))
            assertEquals(firstInstalled.draws, replayedInstalled.draws)

            val firstUnavailable = record(configuredPreview(installed = false))
            val replayedUnavailable = record(configuredPreview(installed = false))
            assertEquals(firstUnavailable.draws, replayedUnavailable.draws)
            assertEquals(listOf("Install Monospaced to preview"), firstUnavailable.fallbackTexts)
        }

    private fun record(
        preview: FontPreviewComponent,
        size: Dimension = preview.preferredSize,
    ): Recording {
        preview.size = size
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        val delegate = image.createGraphics()
        val draws = mutableListOf<TextDraw>()
        var activeFont = delegate.font
        var activeClip: Rectangle? = Rectangle(0, 0, size.width, size.height)
        val graphics = mockk<Graphics2D>(relaxed = true)
        every { graphics.create() } returns graphics
        every { graphics.font = any() } answers {
            activeFont = firstArg()
            delegate.font = activeFont
        }
        every { graphics.font } answers { activeFont }
        every { graphics.getFontMetrics(any()) } answers { delegate.getFontMetrics(firstArg()) }
        every { graphics.fontMetrics } answers { delegate.getFontMetrics(activeFont) }
        every { graphics.clip } answers { activeClip?.let(::Rectangle) }
        every { graphics.clip = any() } answers { activeClip = firstArg<Rectangle?>()?.let(::Rectangle) }
        every { graphics.clipRect(any(), any(), any(), any()) } answers {
            val requested = Rectangle(firstArg(), secondArg(), thirdArg(), arg(3))
            activeClip = activeClip?.intersection(requested) ?: requested
        }
        every { graphics.drawString(any<String>(), any<Int>(), any<Int>()) } answers {
            draws += TextDraw(firstArg(), secondArg(), thirdArg(), activeFont, activeClip?.let(::Rectangle))
        }
        try {
            preview.paint(graphics)
        } finally {
            delegate.dispose()
        }
        return Recording(draws)
    }

    private fun withDialogResolution(block: () -> Unit) {
        mockkObject(FontDetector)
        try {
            every { FontDetector.resolveFamily(any()) } returns Font.DIALOG
            block()
        } finally {
            unmockkObject(FontDetector)
        }
    }

    private fun realMetrics(font: Font): FontMetrics =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().let { graphics ->
            try {
                graphics.getFontMetrics(font)
            } finally {
                graphics.dispose()
            }
        }

    private fun BufferedImage.distinctRgbCount(): Int =
        (0 until width step RASTER_SAMPLE_STEP)
            .flatMap { x ->
                (0 until height step RASTER_SAMPLE_STEP).map { y -> getRGB(x, y) }
            }.toSet()
            .size

    private fun BufferedImage.pixels(bounds: Rectangle): List<Int> =
        (bounds.x until bounds.x + bounds.width).flatMap { x ->
            (bounds.y until bounds.y + bounds.height).map { y -> getRGB(x, y) }
        }

    private fun renderRaster(
        preview: FontPreviewComponent,
        size: Dimension,
    ): BufferedImage {
        preview.size = size
        return BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics()
            try {
                preview.paint(graphics)
            } finally {
                graphics.dispose()
            }
        }
    }

    private data class TextDraw(
        val text: String,
        val x: Int,
        val y: Int,
        val font: Font,
        val clip: Rectangle?,
    )

    private data class Recording(
        val draws: List<TextDraw>,
    ) {
        val codeDraws: List<TextDraw> get() = draws.filter { it.text in SAMPLE_CODE }
        val projectDraws: List<TextDraw> get() = draws.filter { it.text in PROJECT_LABELS }
        val codeTexts: List<String> get() = codeDraws.map(TextDraw::text)
        val projectTexts: List<String> get() = projectDraws.map(TextDraw::text)
        val fallbackTexts: List<String>
            get() = draws.map(TextDraw::text).filter { it.startsWith("Install ") || it.startsWith("Choose ") }
        val codeFamily: String? get() = codeDraws.first().font.attributes[TextAttribute.FAMILY] as? String
    }

    private companion object {
        private const val MIN_RENDER_COLORS = 5
        private const val PREVIEW_PADDING = 8
        private const val GUTTER_WIDTH = 42
        private const val RASTER_SAMPLE_STEP = 4
        private val PROJECT_LABELS = listOf("PresetPreview.kt", "FontTokens.xml", "editor.icls", "build/")
        private val SAMPLE_CODE =
            listOf(
                "fun renderPreset(font: EditorFont): Preview {",
                "  val selected = font.family != fallback",
                "  val glyphs = sample.map { char -> shape(char) }",
                "  return Preview(glyphs, selected && font.ligatures)",
                "}",
            )
    }
}

private fun onEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        SwingUtilities.invokeAndWait(block)
    }
}
