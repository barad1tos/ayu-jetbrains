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
 * Option C — axes-orthogonal-to-mood contract (fix for the
 * `syntax-mood-noop-on-editor` Bug #3 root cause). Validates that
 * [SyntaxModeApplicator.compute] applies axis transforms to baseline keys
 * that are NOT in any mood overlay, independently of the mood whitelist.
 *
 * Before Option C, an axis whose key list referenced a baseline-only entry
 * (e.g. `DEFAULT_LINE_COMMENT`) produced no visible effect — the compute
 * loop only iterated overlay keys, so baseline-only axis targets were
 * dropped silently. After Option C, axes operate on a baseline clone
 * whenever the target key is absent from the overlay, making style axes
 * fully orthogonal to the mood (Minimal / Standard / Rich / Maximum).
 *
 * Revertibility (D-07): baseline-only keys listed in axis-keys.txt are
 * emitted on EVERY compute (pristine baseline clone when their axis is
 * inactive, transformed clone when active). This way a re-compute after the
 * user toggles an axis off restores the original baseline value via
 * setAttributes(key, baselineClone) — no per-axis snapshot tracking
 * required. The trade-off: keys listed under any axis section are always
 * touched on apply, whether or not the user currently has that axis on.
 *
 * Pure-function transforms — loader fully mocked, no platform deps.
 */
class SyntaxModeApplicatorAxisOrthogonalTest {
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

        // Overlay holds ONE mood-managed key (OVERLAY_KEY) that lives in MAXIMUM.
        val overlay =
            mapOf(
                key("OVERLAY_KEY") to attrs(0xFF, 0xCC, 0x66),
            )
        every { loader.loadOverlayForVariant("Mirage") } returns overlay

        // Baseline holds the keys axes will target (BASELINE_COMMENT, BASELINE_DECL,
        // BASELINE_REF) plus one untouched key (BASELINE_UNRELATED) used by the
        // "don't touch unrelated keys" test. These are NOT in any mood overlay —
        // they exist only in the editor scheme baseline.
        val baseline =
            mapOf(
                key("BASELINE_COMMENT") to attrs(0xB8, 0xCF, 0xE6),
                key("BASELINE_DECL") to attrs(0xFF, 0xD1, 0x73),
                key("BASELINE_REF") to attrs(0x73, 0xD0, 0xFF),
                key("BASELINE_UNRELATED") to attrs(0x12, 0x34, 0x56),
            )
        every { loader.loadBaselineForVariant("Mirage") } returns baseline

        // Tier setup: only OVERLAY_KEY is in any tier. Baseline keys are NOT.
        every { loader.tierKeys(SyntaxMood.MINIMAL) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.STANDARD) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.RICH) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.MAXIMUM) } returns setOf(key("OVERLAY_KEY"))

        // Axes target baseline-only keys. The whole point of the test.
        // BASELINE_UNRELATED is NOT in any axis-keys section.
        every { loader.axisKeys(StyleAxis.DIMMED_COMMENTS) } returns setOf(key("BASELINE_COMMENT"))
        every { loader.axisKeys(StyleAxis.ITALIC_DECLARATIONS) } returns setOf(key("BASELINE_DECL"))
        every { loader.axisKeys(StyleAxis.BOLD_TYPE_REFERENCES) } returns setOf(key("BASELINE_REF"))
        every { loader.axisKeys(StyleAxis.ITALIC_DOC_TAGS) } returns emptySet()
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
    fun `DIMMED_COMMENTS axis applies to baseline comment key at MINIMAL mood`() {
        // MINIMAL whitelists nothing — but the axis must still dim baseline comments.
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MINIMAL,
                setOf(StyleAxis.DIMMED_COMMENTS),
                "Mirage",
                loader,
            )
        val dimmed = result[key("BASELINE_COMMENT")]
        assertNotNull(dimmed, "BASELINE_COMMENT must appear in result when DIMMED_COMMENTS axis active")
        val fg = dimmed.foregroundColor
        assertNotNull(fg, "dimmed comment must have a foreground color")
        assertEquals((0xB8 * 0.6).toInt(), fg.red, "DIMMED scales baseline red by 0.6")
        assertEquals((0xCF * 0.6).toInt(), fg.green, "DIMMED scales baseline green by 0.6")
        assertEquals((0xE6 * 0.6).toInt(), fg.blue, "DIMMED scales baseline blue by 0.6")
    }

    @Test
    fun `DIMMED_COMMENTS axis applies to baseline comment key at MAXIMUM mood`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.DIMMED_COMMENTS),
                "Mirage",
                loader,
            )
        val dimmed = result[key("BASELINE_COMMENT")]
        assertNotNull(dimmed)
        assertEquals((0xB8 * 0.6).toInt(), dimmed.foregroundColor.red)
    }

    @Test
    fun `ITALIC_DECLARATIONS axis applies to baseline declaration key independently of mood`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.STANDARD,
                setOf(StyleAxis.ITALIC_DECLARATIONS),
                "Mirage",
                loader,
            )
        val decl = result[key("BASELINE_DECL")]
        assertNotNull(decl, "BASELINE_DECL must appear in result when ITALIC_DECLARATIONS axis active")
        assertTrue(decl.fontType and Font.ITALIC != 0, "baseline decl must receive ITALIC bit")
    }

    @Test
    fun `BOLD_TYPE_REFERENCES axis applies to baseline ref key independently of mood`() {
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.RICH,
                setOf(StyleAxis.BOLD_TYPE_REFERENCES),
                "Mirage",
                loader,
            )
        val ref = result[key("BASELINE_REF")]
        assertNotNull(ref, "BASELINE_REF must appear in result when BOLD_TYPE_REFERENCES axis active")
        assertTrue(ref.fontType and Font.BOLD != 0, "baseline ref must receive BOLD bit")
    }

    @Test
    fun `axis toggle off restores baseline value for axis-only baseline key`() {
        // With axis: dimmed clone.
        val withAxis =
            SyntaxModeApplicator.compute(
                SyntaxMood.MINIMAL,
                setOf(StyleAxis.DIMMED_COMMENTS),
                "Mirage",
                loader,
            )
        // Without axis: pristine baseline clone (revert path — D-07 by re-read).
        val withoutAxis =
            SyntaxModeApplicator.compute(
                SyntaxMood.MINIMAL,
                emptySet(),
                "Mirage",
                loader,
            )

        val dimmed = withAxis[key("BASELINE_COMMENT")]
        assertNotNull(dimmed)
        assertEquals((0xB8 * 0.6).toInt(), dimmed.foregroundColor.red, "axis-on dims to 0.6 * baseline")

        val pristine = withoutAxis[key("BASELINE_COMMENT")]
        assertNotNull(
            pristine,
            "axis-off must STILL emit a baseline clone so writeback restores pristine value (D-07)",
        )
        assertEquals(
            0xB8,
            pristine.foregroundColor.red,
            "axis-off baseline clone must equal pristine baseline RGB (revertibility)",
        )
    }

    @Test
    fun `baseline keys NOT listed in any axis-keys section are NOT emitted`() {
        // Don't-touch-unrelated invariant: BASELINE_UNRELATED is present in the
        // baseline fixture but listed in NO axis-keys section. It must never
        // appear in the result map regardless of which axes are active.
        val withAllAxes =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(
                    StyleAxis.DIMMED_COMMENTS,
                    StyleAxis.ITALIC_DECLARATIONS,
                    StyleAxis.BOLD_TYPE_REFERENCES,
                    StyleAxis.ITALIC_DOC_TAGS,
                ),
                "Mirage",
                loader,
            )
        assertNull(
            withAllAxes[key("BASELINE_UNRELATED")],
            "baseline key NOT in any axis-keys section must NOT appear (don't touch unrelated)",
        )
    }

    @Test
    fun `overlay key still receives mood whitelist treatment alongside baseline axis path`() {
        // Sanity: existing overlay path is not broken by the baseline merge.
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.DIMMED_COMMENTS),
                "Mirage",
                loader,
            )
        val overlayKey = result[key("OVERLAY_KEY")]
        assertNotNull(overlayKey, "OVERLAY_KEY must be in active whitelist at MAXIMUM")
        assertEquals(
            Color(0xFF, 0xCC, 0x66),
            overlayKey.foregroundColor,
            "OVERLAY_KEY not in DIMMED axis — color must remain overlay baseline",
        )
        val baselineComment = result[key("BASELINE_COMMENT")]
        assertNotNull(baselineComment, "BASELINE_COMMENT must also be in result via baseline-axis path")
    }

    @Test
    fun `axis key present in BOTH overlay and baseline uses overlay clone (overlay wins)`() {
        // When the user lists the same key in BOTH overlay and axis-keys, the
        // overlay takes precedence — axis transform is applied on top of the
        // overlay clone, not the baseline clone. This preserves the existing
        // overlay-path contract (axes mutate overlay clones in-place) and
        // matches the Source 2 doc in SyntaxModeApplicator.
        every { loader.axisKeys(StyleAxis.DIMMED_COMMENTS) } returns
            setOf(key("BASELINE_COMMENT"), key("OVERLAY_KEY"))
        val result =
            SyntaxModeApplicator.compute(
                SyntaxMood.MAXIMUM,
                setOf(StyleAxis.DIMMED_COMMENTS),
                "Mirage",
                loader,
            )
        val overlayDimmed = result[key("OVERLAY_KEY")]
        assertNotNull(overlayDimmed)
        // OVERLAY_KEY baseline is (0xFF, 0xCC, 0x66) — dimmed to 0.6 × each channel.
        assertEquals((0xFF * 0.6).toInt(), overlayDimmed.foregroundColor.red, "overlay clone dimmed (overlay wins)")
    }
}
