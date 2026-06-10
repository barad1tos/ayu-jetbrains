package dev.ayuislands.settings

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxOverlayLoader
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyntaxPreviewComponentTest {
    private lateinit var loaderMock: SyntaxOverlayLoader

    @BeforeTest
    fun setUp() {
        loaderMock = mockk(relaxed = true)
        every { loaderMock.loadBaselineForVariant(any()) } returns BASELINE_MAP
        every { loaderMock.loadOverlayForVariant(any()) } returns OVERLAY_MAP

        mockkObject(SyntaxOverlayLoader.Companion)
        every { SyntaxOverlayLoader.getInstance() } returns loaderMock

        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `updatePreview populates categoryColorMap with at least keyword color`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        component.updatePreview(
            AyuVariant.MIRAGE,
            SyntaxPreset.AMBIENT,
            emptyMap(),
            SyntaxPreset.AMBIENT,
            SyntaxReadabilityOptions.DEFAULT,
        )
        val keywordColor = component.categoryColorForTest(PrimitiveCategory.KEYWORD)
        assertNotNull(keywordColor, "KEYWORD category should have a color after updatePreview")
    }

    @Test
    fun `updatePreview populates comment color`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        component.updatePreview(
            AyuVariant.MIRAGE,
            SyntaxPreset.AMBIENT,
            emptyMap(),
            SyntaxPreset.AMBIENT,
            SyntaxReadabilityOptions.DEFAULT,
        )
        val commentColor = component.categoryColorForTest(PrimitiveCategory.COMMENT)
        assertNotNull(commentColor, "COMMENT category should have a color after updatePreview")
    }

    @Test
    fun `updatePreview populates documentation color`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        component.updatePreview(
            AyuVariant.MIRAGE,
            SyntaxPreset.AMBIENT,
            emptyMap(),
            SyntaxPreset.AMBIENT,
            SyntaxReadabilityOptions.DEFAULT,
        )
        val docColor = component.categoryColorForTest(PrimitiveCategory.DOCUMENTATION)
        assertNotNull(docColor, "DOCUMENTATION category should have a color after updatePreview")
    }

    @Test
    fun `colors change when preset changes from AMBIENT to WHISPER`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        component.updatePreview(
            AyuVariant.MIRAGE,
            SyntaxPreset.AMBIENT,
            emptyMap(),
            SyntaxPreset.AMBIENT,
            SyntaxReadabilityOptions.DEFAULT,
        )
        val ambientKeyword = component.categoryColorForTest(PrimitiveCategory.KEYWORD)

        component.updatePreview(
            AyuVariant.MIRAGE,
            SyntaxPreset.WHISPER,
            emptyMap(),
            SyntaxPreset.AMBIENT,
            SyntaxReadabilityOptions.DEFAULT,
        )
        val whisperKeyword = component.categoryColorForTest(PrimitiveCategory.KEYWORD)

        assertNotNull(ambientKeyword)
        assertNotNull(whisperKeyword)
        assertNotEquals(
            ambientKeyword.rgb,
            whisperKeyword.rgb,
            "KEYWORD color should differ between AMBIENT and WHISPER presets",
        )
    }

    @Test
    fun `readability dimComments reduces comment brightness`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        component.updatePreview(
            AyuVariant.MIRAGE,
            SyntaxPreset.AMBIENT,
            emptyMap(),
            SyntaxPreset.AMBIENT,
            SyntaxReadabilityOptions.DEFAULT,
        )
        val normalComment = component.categoryColorForTest(PrimitiveCategory.COMMENT)

        component.updatePreview(
            AyuVariant.MIRAGE,
            SyntaxPreset.AMBIENT,
            emptyMap(),
            SyntaxPreset.AMBIENT,
            SyntaxReadabilityOptions(dimComments = true),
        )
        val dimmedComment = component.categoryColorForTest(PrimitiveCategory.COMMENT)

        assertNotNull(normalComment)
        assertNotNull(dimmedComment)
        assertNotEquals(
            normalComment.rgb,
            dimmedComment.rgb,
            "COMMENT color should change when dimComments is enabled",
        )
    }

    @Test
    fun `variant is stored after updatePreview`() {
        val component = SyntaxPreviewComponent(AyuVariant.DARK)
        component.updatePreview(
            AyuVariant.MIRAGE,
            SyntaxPreset.AMBIENT,
            emptyMap(),
            SyntaxPreset.AMBIENT,
            SyntaxReadabilityOptions.DEFAULT,
        )
        assertEquals(AyuVariant.MIRAGE, component.variantForTest())
    }

    @Test
    fun `preferred size is non-zero`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        val preferred = component.preferredSize
        assertTrue(preferred.width > 0, "Preferred width must be positive")
        assertTrue(preferred.height > 0, "Preferred height must be positive")
    }

    @Test
    fun `minimum size is smaller than preferred size`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        val min = component.minimumSize
        val pref = component.preferredSize
        assertTrue(
            min.width < pref.width,
            "Minimum width (${min.width}) must be less than preferred width (${pref.width})",
        )
    }

    @Test
    fun `paintComponent renders without exception`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        component.updatePreview(
            AyuVariant.MIRAGE,
            SyntaxPreset.AMBIENT,
            emptyMap(),
            SyntaxPreset.AMBIENT,
            SyntaxReadabilityOptions.DEFAULT,
        )
        component.size = Dimension(560, 220)
        val image = BufferedImage(560, 220, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            component.paint(g)
        } finally {
            g.dispose()
        }
        // If we get here without exception, the test passes.
    }

    private companion object {
        /**
         * Minimal baseline map with one key per category we want to test.
         * Keys use externalName patterns that [buildCategoryColorMap] matches.
         */
        private val BASELINE_MAP: Map<TextAttributesKey, TextAttributes> =
            mapOf(
                key("DEFAULT_KEYWORD") to attrs(Color(0xFF719E)),
                key("JAVA_LINE_COMMENT") to attrs(Color(0x5C6773)),
                key("KDOC_TAG_NAME") to attrs(Color(0x5C6773)),
            )

        private val OVERLAY_MAP: Map<TextAttributesKey, TextAttributes> = emptyMap()

        private fun key(externalName: String): TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(externalName)

        private fun attrs(fg: Color): TextAttributes {
            val ta = TextAttributes()
            ta.foregroundColor = fg
            return ta
        }
    }
}
