package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import java.awt.Font
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Axis-transform behavior for [SyntaxModeApplicator]. Verifies the exact
 * formulas from CONTEXT.md `<specifics>`:
 *
 * | Axis                  | Transform                                                  |
 * | --------------------- | ---------------------------------------------------------- |
 * | ITALIC_DECLARATIONS   | clone.fontType = baseline.fontType or Font.ITALIC          |
 * | BOLD_TYPE_REFERENCES  | clone.fontType = baseline.fontType or Font.BOLD            |
 * | DIMMED_COMMENTS       | clone.foregroundColor = Color(r*0.6, g*0.6, b*0.6, alpha)  |
 * | ITALIC_DOC_TAGS       | clone.fontType = baseline.fontType or Font.ITALIC          |
 *
 * Pure-function transforms — no platform deps. Loader mocked.
 */
class SyntaxModeApplicatorAxisTransformTest {
    private lateinit var loader: SyntaxOverlayLoader
    private lateinit var keyCache: MutableMap<String, TextAttributesKey>

    @BeforeTest
    fun setUp() {
        mockkStatic(TextAttributesKey::class)
        keyCache = mutableMapOf()
        every { TextAttributesKey.find(any<String>()) } answers {
            val name = firstArg<String>()
            keyCache.getOrPut(name) { mockk(relaxed = true) { every { externalName } returns name } }
        }

        loader = mockk(relaxed = true)
        val overlay =
            mapOf(
                key("DECL_KEY") to attrs(0xFF, 0xCC, 0x66, fontType = 0),
                key("REF_KEY") to attrs(0x80, 0xD0, 0xFF, fontType = 0),
                key("COMMENT_KEY") to attrs(0xAA, 0xBB, 0xCC, alpha = 0x80),
                key("DOC_KEY") to attrs(0x99, 0x66, 0xCC, fontType = 0),
                key("NULL_FG_KEY") to TextAttributes().apply { fontType = 0 },
            )
        every { loader.loadOverlayForVariant("Mirage") } returns overlay
        // All overlay keys live in MAXIMUM whitelist by default for these tests
        every { loader.tierKeys(SyntaxMood.MINIMAL) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.STANDARD) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.RICH) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.MAXIMUM) } returns overlay.keys
        // Axis-to-keys map
        every { loader.axisKeys(StyleAxis.ITALIC_DECLARATIONS) } returns setOf(key("DECL_KEY"))
        every { loader.axisKeys(StyleAxis.BOLD_TYPE_REFERENCES) } returns setOf(key("REF_KEY"))
        every { loader.axisKeys(StyleAxis.DIMMED_COMMENTS) } returns
            setOf(key("COMMENT_KEY"), key("NULL_FG_KEY"))
        every { loader.axisKeys(StyleAxis.ITALIC_DOC_TAGS) } returns setOf(key("DOC_KEY"))
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun key(name: String): TextAttributesKey = TextAttributesKey.find(name)

    private fun attrs(
        r: Int,
        g: Int,
        b: Int,
        alpha: Int = 0xFF,
        fontType: Int = 0,
    ): TextAttributes =
        TextAttributes().apply {
            foregroundColor = Color(r, g, b, alpha)
            this.fontType = fontType
        }

    @Test
    fun `ITALIC_DECLARATIONS sets ITALIC bit on declaration keys only`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.ITALIC_DECLARATIONS),
                "Mirage",
                loader,
            )
        val decl = result[key("DECL_KEY")]
        assertNotNull(decl)
        assertTrue(
            decl.fontType and Font.ITALIC != 0,
            "DECL_KEY must have ITALIC bit set when ITALIC_DECLARATIONS active",
        )
        val ref = result[key("REF_KEY")]
        assertNotNull(ref)
        assertEquals(0, ref.fontType and Font.ITALIC, "REF_KEY must NOT receive ITALIC (different axis target)")
    }

    @Test
    fun `BOLD_TYPE_REFERENCES sets BOLD bit on type-reference keys only`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.BOLD_TYPE_REFERENCES),
                "Mirage",
                loader,
            )
        val ref = result[key("REF_KEY")]
        assertNotNull(ref)
        assertTrue(ref.fontType and Font.BOLD != 0, "REF_KEY must have BOLD bit")
        val decl = result[key("DECL_KEY")]
        assertNotNull(decl)
        assertEquals(0, decl.fontType and Font.BOLD, "DECL_KEY must NOT receive BOLD")
    }

    @Test
    fun `DIMMED_COMMENTS scales foreground RGB by 0_6 preserving alpha`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.DIMMED_COMMENTS),
                "Mirage",
                loader,
            )
        val dimmed = result[key("COMMENT_KEY")]
        assertNotNull(dimmed)
        val fg = dimmed.foregroundColor
        assertNotNull(fg)
        assertEquals((0xAA * 0.6).toInt(), fg.red, "red must be scaled by 0.6")
        assertEquals((0xBB * 0.6).toInt(), fg.green, "green must be scaled by 0.6")
        assertEquals((0xCC * 0.6).toInt(), fg.blue, "blue must be scaled by 0.6")
        assertEquals(0x80, fg.alpha, "alpha must be preserved")
    }

    @Test
    fun `DIMMED_COMMENTS skips keys with null foreground (no NPE, no mutation)`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.DIMMED_COMMENTS),
                "Mirage",
                loader,
            )
        val nullFg = result[key("NULL_FG_KEY")]
        assertNotNull(nullFg, "key must still be in result map even with null foreground")
        assertNull(
            nullFg.foregroundColor,
            "null foreground must remain null after DIMMED_COMMENTS (no NPE, no mutation)",
        )
    }

    @Test
    fun `ITALIC_DOC_TAGS sets ITALIC bit on doc-tag keys only`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.ITALIC_DOC_TAGS),
                "Mirage",
                loader,
            )
        val doc = result[key("DOC_KEY")]
        assertNotNull(doc)
        assertTrue(doc.fontType and Font.ITALIC != 0, "DOC_KEY must have ITALIC bit")
        val decl = result[key("DECL_KEY")]
        assertNotNull(decl)
        assertEquals(
            0,
            decl.fontType and Font.ITALIC,
            "DECL_KEY must NOT receive ITALIC when only ITALIC_DOC_TAGS active",
        )
    }

    @Test
    fun `multiple axes stack additively (ITALIC + BOLD on same key sets both bits)`() {
        // Reassign the loader so DECL_KEY is in BOTH ITALIC_DECLARATIONS and BOLD_TYPE_REFERENCES
        every { loader.axisKeys(StyleAxis.BOLD_TYPE_REFERENCES) } returns setOf(key("DECL_KEY"))
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.ITALIC_DECLARATIONS, StyleAxis.BOLD_TYPE_REFERENCES),
                "Mirage",
                loader,
            )
        val decl = result[key("DECL_KEY")]
        assertNotNull(decl)
        assertTrue(decl.fontType and Font.ITALIC != 0, "DECL_KEY must have ITALIC bit set")
        assertTrue(decl.fontType and Font.BOLD != 0, "DECL_KEY must have BOLD bit set")
    }

    @Test
    fun `axis toggle off + re-compute restores baseline (no italic remnant)`() {
        val withItalic =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.ITALIC_DECLARATIONS),
                "Mirage",
                loader,
            )
        val withoutItalic =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                emptySet(),
                "Mirage",
                loader,
            )
        assertTrue(withItalic[key("DECL_KEY")]!!.fontType and Font.ITALIC != 0)
        assertEquals(
            0,
            withoutItalic[key("DECL_KEY")]!!.fontType and Font.ITALIC,
            "re-compute with no axes must clear the ITALIC bit (D-07 revertibility without snapshot tracking)",
        )
    }

    @Test
    fun `no axes: declaration key fontType is exactly the overlay baseline`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.MAXIMUM, emptySet(), "Mirage", loader)
        val decl = result[key("DECL_KEY")]
        assertNotNull(decl)
        assertEquals(0, decl.fontType, "no axes: fontType must match overlay baseline (0)")
    }
}
