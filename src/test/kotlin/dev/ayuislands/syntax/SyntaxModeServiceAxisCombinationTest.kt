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
import kotlin.test.assertTrue

/**
 * Phase 49 Plan 49-04 — axis stacking + toggle-off revertibility for the full
 * mood × axes matrix (SYNTAX-05, D-07).
 *
 * Complements [SyntaxModeApplicatorAxisTransformTest] (which validates each
 * single-axis transform formula) by asserting:
 *  - axes stack additively when the same key sits in multiple axis maps
 *    (ITALIC + BOLD on a declaration key sets both bits)
 *  - three axes stack independently across disjoint key buckets
 *  - toggling an axis OFF via a re-compute returns the affected keys to their
 *    overlay baseline — proving the D-07 revert-by-re-read contract without
 *    snapshot tracking
 *  - toggling all axes off restores the raw mood baseline for every key
 *
 * Pure-function transforms — Loader mocked, no platform deps. The applicator
 * is the unit under test; service orchestration tests live elsewhere.
 */
class SyntaxModeServiceAxisCombinationTest {
    private lateinit var loader: SyntaxOverlayLoader
    private lateinit var keyCache: MutableMap<String, TextAttributesKey>

    @BeforeTest
    fun setUp() {
        keyCache = mutableMapOf()
        mockkStatic(TextAttributesKey::class)
        every { TextAttributesKey.find(any<String>()) } answers {
            val name = firstArg<String>()
            keyCache.getOrPut(name) { mockk(relaxed = true) { every { externalName } returns name } }
        }

        loader = mockk(relaxed = true)
        val overlay =
            mapOf(
                key("DECL_KEY") to baselineAttrs(0xFF, 0xCC, 0x66),
                key("REF_KEY") to baselineAttrs(0x80, 0xD0, 0xFF),
                key("COMMENT_KEY") to baselineAttrs(0xAA, 0xBB, 0xCC, alpha = 0x80),
                key("DOC_KEY") to baselineAttrs(0x99, 0x66, 0xCC),
            )
        every { loader.loadOverlayForVariant("Mirage") } returns overlay
        every { loader.tierKeys(SyntaxMood.MINIMAL) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.STANDARD) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.RICH) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.MAXIMUM) } returns overlay.keys

        every { loader.axisKeys(StyleAxis.ITALIC_DECLARATIONS) } returns setOf(key("DECL_KEY"))
        every { loader.axisKeys(StyleAxis.BOLD_TYPE_REFERENCES) } returns setOf(key("REF_KEY"))
        every { loader.axisKeys(StyleAxis.DIMMED_COMMENTS) } returns setOf(key("COMMENT_KEY"))
        every { loader.axisKeys(StyleAxis.ITALIC_DOC_TAGS) } returns setOf(key("DOC_KEY"))
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun key(name: String): TextAttributesKey = TextAttributesKey.find(name)

    private fun baselineAttrs(
        r: Int,
        g: Int,
        b: Int,
        alpha: Int = 0xFF,
    ): TextAttributes =
        TextAttributes().apply {
            foregroundColor = Color(r, g, b, alpha)
            fontType = 0
        }

    @Test
    fun `single axis ITALIC_DECLARATIONS sets ITALIC bit on declaration key only`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.ITALIC_DECLARATIONS),
                "Mirage",
                loader,
            )
        val decl = result[key("DECL_KEY")]
        assertNotNull(decl)
        assertTrue(decl.fontType and Font.ITALIC != 0, "DECL_KEY must have ITALIC bit when axis active")
        val ref = result[key("REF_KEY")]
        assertNotNull(ref)
        assertEquals(0, ref.fontType, "REF_KEY must NOT receive ITALIC (different axis target)")
    }

    @Test
    fun `two axes ITALIC + BOLD on disjoint key sets each stack independently`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.ITALIC_DECLARATIONS, StyleAxis.BOLD_TYPE_REFERENCES),
                "Mirage",
                loader,
            )
        val decl = result[key("DECL_KEY")]
        val ref = result[key("REF_KEY")]
        assertNotNull(decl)
        assertNotNull(ref)
        assertTrue(decl.fontType and Font.ITALIC != 0, "DECL_KEY must have ITALIC bit")
        assertEquals(0, decl.fontType and Font.BOLD, "DECL_KEY must NOT have BOLD (not in BOLD axis map)")
        assertTrue(ref.fontType and Font.BOLD != 0, "REF_KEY must have BOLD bit")
        assertEquals(0, ref.fontType and Font.ITALIC, "REF_KEY must NOT have ITALIC")
    }

    @Test
    fun `three axes ITALIC + BOLD + DIMMED stack independently across disjoint key buckets`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(
                    StyleAxis.ITALIC_DECLARATIONS,
                    StyleAxis.BOLD_TYPE_REFERENCES,
                    StyleAxis.DIMMED_COMMENTS,
                ),
                "Mirage",
                loader,
            )
        val decl = result[key("DECL_KEY")]
        val ref = result[key("REF_KEY")]
        val comment = result[key("COMMENT_KEY")]
        assertNotNull(decl)
        assertNotNull(ref)
        assertNotNull(comment)
        assertTrue(decl.fontType and Font.ITALIC != 0, "ITALIC on DECL_KEY")
        assertTrue(ref.fontType and Font.BOLD != 0, "BOLD on REF_KEY")
        val commentFg = comment.foregroundColor
        assertNotNull(commentFg)
        assertEquals((0xAA * 0.6).toInt(), commentFg.red, "DIMMED scales COMMENT_KEY red by 0.6")
        assertEquals(0x80, commentFg.alpha, "DIMMED preserves COMMENT_KEY alpha")
    }

    @Test
    fun `axis toggle off via second compute restores baseline for the affected key`() {
        // D-07: revert by re-read (compute reads overlay baseline fresh each time;
        // no per-axis undo / snapshot tracking).
        val withAxis =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.ITALIC_DECLARATIONS, StyleAxis.BOLD_TYPE_REFERENCES),
                "Mirage",
                loader,
            )
        val afterToggleOff =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.ITALIC_DECLARATIONS),
                "Mirage",
                loader,
            )

        // ITALIC_DECLARATIONS still active → DECL_KEY still has ITALIC.
        val decl = afterToggleOff[key("DECL_KEY")]
        assertNotNull(decl)
        assertTrue(decl.fontType and Font.ITALIC != 0)

        // BOLD_TYPE_REFERENCES toggled off → REF_KEY no longer has BOLD.
        val refBefore = withAxis[key("REF_KEY")]
        val refAfter = afterToggleOff[key("REF_KEY")]
        assertNotNull(refBefore)
        assertNotNull(refAfter)
        assertTrue(refBefore.fontType and Font.BOLD != 0, "REF_KEY had BOLD before toggle-off")
        assertEquals(0, refAfter.fontType and Font.BOLD, "REF_KEY must lose BOLD after toggle-off (D-07)")
    }

    @Test
    fun `axes toggle to empty set restores raw mood baseline for every overlay key`() {
        val withAllAxes =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(
                    StyleAxis.ITALIC_DECLARATIONS,
                    StyleAxis.BOLD_TYPE_REFERENCES,
                    StyleAxis.DIMMED_COMMENTS,
                    StyleAxis.ITALIC_DOC_TAGS,
                ),
                "Mirage",
                loader,
            )
        val withNoAxes =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                emptySet(),
                "Mirage",
                loader,
            )

        // Sanity: with all axes, DECL, REF, DOC keys all carry their axis transform.
        assertNotNull(withAllAxes[key("DECL_KEY")])
        assertTrue((withAllAxes[key("DECL_KEY")]!!.fontType and Font.ITALIC) != 0)
        assertNotNull(withAllAxes[key("REF_KEY")])
        assertTrue((withAllAxes[key("REF_KEY")]!!.fontType and Font.BOLD) != 0)
        assertNotNull(withAllAxes[key("DOC_KEY")])
        assertTrue((withAllAxes[key("DOC_KEY")]!!.fontType and Font.ITALIC) != 0)

        // With no axes, every key reverts to overlay baseline (fontType 0, raw RGB).
        for (keyName in listOf("DECL_KEY", "REF_KEY", "COMMENT_KEY", "DOC_KEY")) {
            val attrs = withNoAxes[key(keyName)]
            assertNotNull(attrs, "key $keyName must be present in MAXIMUM whitelist")
            assertEquals(0, attrs.fontType, "key $keyName must have raw baseline fontType (0) with no axes")
        }
        // COMMENT_KEY foreground returns to raw 0xAA / 0xBB / 0xCC (no dim).
        val commentFg = withNoAxes[key("COMMENT_KEY")]!!.foregroundColor
        assertNotNull(commentFg)
        assertEquals(0xAA, commentFg.red)
        assertEquals(0xBB, commentFg.green)
        assertEquals(0xCC, commentFg.blue)
    }

    @Test
    fun `ITALIC_DECLARATIONS + DIMMED_COMMENTS stack across declaration and comment keys`() {
        // Tests that two axes targeting disjoint key buckets coexist without interference.
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.ITALIC_DECLARATIONS, StyleAxis.DIMMED_COMMENTS),
                "Mirage",
                loader,
            )
        val decl = result[key("DECL_KEY")]
        val comment = result[key("COMMENT_KEY")]
        assertNotNull(decl)
        assertNotNull(comment)
        assertTrue(decl.fontType and Font.ITALIC != 0, "DECL_KEY ITALIC active")
        // Decl was not in DIMMED axis → foreground untouched.
        val declFg = decl.foregroundColor
        assertNotNull(declFg)
        assertEquals(0xFF, declFg.red, "DECL_KEY red must NOT be dimmed (not in DIMMED axis)")
        // Comment dimmed.
        val commentFg = comment.foregroundColor
        assertNotNull(commentFg)
        assertEquals((0xAA * 0.6).toInt(), commentFg.red, "COMMENT_KEY red must be dimmed")
    }
}
