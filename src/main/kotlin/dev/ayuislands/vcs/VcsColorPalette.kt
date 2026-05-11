package dev.ayuislands.vcs

import dev.ayuislands.accent.AyuVariant
import java.awt.Color

/**
 * Phase 40.2 palette — stock 2.6.2 XML hex per (color key × Ayu variant) plus
 * the Whisper / Cyberpunk slider-endpoint colors computed via HSB-saturation
 * offset from those stock values.
 *
 * The applier consumes [entriesFor] to feed [VcsColorBlender.blend], which
 * linearly interpolates between Whisper (slider 0, `stock - 10%` saturation)
 * and Cyberpunk (slider 100, `stock + 20%` saturation). Slider position 33
 * lands on the exact XML stock — that's the Ambient preset's no-op invariant.
 *
 * Two write-mode flavours live in the palette:
 *  - [VcsWriteMode.COLOR_KEY] — top-level
 *    [com.intellij.openapi.editor.colors.ColorKey] values, written via
 *    `scheme.setColor`. Covers Project View file-status markers, gutter
 *    modified-line color, and the top-level diff-modified / inserted /
 *    deleted entries.
 *  - [VcsWriteMode.TEXT_ATTR_BG] — the background slot inside a
 *    [com.intellij.openapi.editor.colors.TextAttributesKey], written via
 *    `scheme.setAttributes` after cloning the existing TextAttributes so
 *    foreground, effect colors, and the error stripe slot stay intact.
 *    Wave 2.5 covers diff-viewer row backgrounds — the biggest visual signal
 *    in the actual diff editor.
 *
 * The TextAttributes error-stripe slot matches the top-level ColorKey values
 * exactly in stock, so the COLOR_KEY tint already moves the right-margin
 * stripe markers in sync. Adding a dedicated TEXT_ATTR_STRIPE write mode is
 * a Wave 2.6+ amendment if a future change forces them out of sync.
 *
 * The Light variant uses 8-char alpha hex for backgrounds (12-13% overlay
 * over the editor surface). [parseHexColor] handles both 6-char opaque and
 * 8-char alpha hex so the blender preserves translucency.
 */
object VcsColorPalette {
    private const val WHISPER_SATURATION_DELTA: Float = -0.10f
    private const val CYBERPUNK_SATURATION_DELTA: Float = 0.20f

    // Shared Ayu blue ramp across modified-state surfaces — diff viewer,
    // file status, and gutter all converge on the same blue per variant in
    // stock 2.6.2 XML, so extracting the hex once keeps the table audit-able.
    private const val BLUE_DARK: String = "#73B8FF"
    private const val BLUE_MIRAGE: String = "#80BFFF"
    private const val BLUE_LIGHT: String = "#478ACC"

    private val PALETTE: Map<VcsColorCategory, List<VcsPaletteEntry>> =
        mapOf(
            VcsColorCategory.DIFF_VIEWER to
                listOf(
                    entry(
                        "DIFF_MODIFIED",
                        VcsWriteMode.COLOR_KEY,
                        dark = BLUE_DARK,
                        mirage = BLUE_MIRAGE,
                        light = BLUE_LIGHT,
                    ),
                    entry(
                        "DIFF_INSERTED",
                        VcsWriteMode.COLOR_KEY,
                        dark = "#70BF56",
                        mirage = "#87D96C",
                        light = "#6CBF43",
                    ),
                    entry(
                        "DIFF_DELETED",
                        VcsWriteMode.COLOR_KEY,
                        dark = "#F26D78",
                        mirage = "#F27983",
                        light = "#FF7383",
                    ),
                    entry(
                        "DIFF_MODIFIED",
                        VcsWriteMode.TEXT_ATTR_BG,
                        dark = "#2E4560",
                        mirage = "#405672",
                        light = "#478ACC1F",
                    ),
                    entry(
                        "DIFF_INSERTED",
                        VcsWriteMode.TEXT_ATTR_BG,
                        dark = "#2D482B",
                        mirage = "#405E43",
                        light = "#6CBF4320",
                    ),
                    entry(
                        "DIFF_DELETED",
                        VcsWriteMode.TEXT_ATTR_BG,
                        dark = "#562E36",
                        mirage = "#623F4B",
                        light = "#FF738314",
                    ),
                ),
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS to
                listOf(
                    entry(
                        "FILESTATUS_MODIFIED",
                        VcsWriteMode.COLOR_KEY,
                        dark = BLUE_DARK,
                        mirage = BLUE_MIRAGE,
                        light = BLUE_LIGHT,
                    ),
                    entry(
                        "FILESTATUS_ADDED",
                        VcsWriteMode.COLOR_KEY,
                        dark = "#AAD94C",
                        mirage = "#C3E887",
                        light = "#86B300",
                    ),
                    entry(
                        "FILESTATUS_DELETED",
                        VcsWriteMode.COLOR_KEY,
                        dark = "#F07178",
                        mirage = "#F77669",
                        light = "#F07171",
                    ),
                    entry(
                        "FILESTATUS_IDEA_FILESTATUS_IGNORED",
                        VcsWriteMode.COLOR_KEY,
                        dark = "#9B7700",
                        mirage = "#B58900",
                        light = "#9EA3A9",
                    ),
                ),
            VcsColorCategory.EDITOR_GUTTER to
                listOf(
                    entry(
                        "MODIFIED_LINES_COLOR",
                        VcsWriteMode.COLOR_KEY,
                        dark = BLUE_DARK,
                        mirage = BLUE_MIRAGE,
                        light = BLUE_LIGHT,
                    ),
                    entry(
                        "IGNORED_MODIFIED_LINES_BORDER_COLOR",
                        VcsWriteMode.COLOR_KEY,
                        dark = BLUE_DARK,
                        mirage = BLUE_MIRAGE,
                        light = BLUE_LIGHT,
                    ),
                ),
            // Wave 3 — Merge & Conflict. Only DIFF_CONFLICT is themable via
            // EditorColorsScheme; the merge 3-way viewer reuses DIFF_MODIFIED /
            // INSERTED / DELETED from Wave 2, and the inline diff popup is a
            // JBPopup UIManager surface that lives outside this palette.
            VcsColorCategory.CONFLICT_MARKERS to
                listOf(
                    entry(
                        "DIFF_CONFLICT",
                        VcsWriteMode.COLOR_KEY,
                        dark = "#D95757",
                        mirage = "#FF6666",
                        light = "#E65050",
                    ),
                    entry(
                        "DIFF_CONFLICT",
                        VcsWriteMode.TEXT_ATTR_BG,
                        dark = "#701015",
                        mirage = "#80202A",
                        light = "#E6505020",
                    ),
                ),
        )

    /** Returns every palette entry for [category]; empty list if the category isn't in Wave 2's scope. */
    fun entriesFor(category: VcsColorCategory): List<VcsPaletteEntry> = PALETTE[category].orEmpty()

    /**
     * Returns the full category-to-entries map. Tests use this to walk every
     * (variant × entry) combination without re-hardcoding the category list.
     */
    fun allCategoriesAndEntries(): Map<VcsColorCategory, List<VcsPaletteEntry>> = PALETTE

    /**
     * Returns the (base, target) blend endpoints for [entry] under [variant].
     * Base sits at slider 0 (Whisper, `-10%` saturation); target sits at slider 100
     * (Cyberpunk, `+20%` saturation). The applier passes both to
     * [VcsColorBlender.blend] along with the active slider position.
     */
    fun endpoints(
        entry: VcsPaletteEntry,
        variant: AyuVariant,
    ): Pair<Color, Color> {
        val stockColor = entry.stockFor(variant)
        val base = withSaturationDelta(stockColor, WHISPER_SATURATION_DELTA)
        val target = withSaturationDelta(stockColor, CYBERPUNK_SATURATION_DELTA)
        return base to target
    }

    /**
     * Convenience overload for backward compat with the Wave 2 ColorKey-only test
     * suite: looks up the first entry matching [keyName] with [VcsWriteMode.COLOR_KEY]
     * across all categories and returns its stock color for [variant].
     */
    fun stock(
        keyName: String,
        variant: AyuVariant,
    ): Color {
        val entry =
            PALETTE.values.asSequence().flatten().firstOrNull {
                it.keyName == keyName && it.mode == VcsWriteMode.COLOR_KEY
            } ?: error("VcsColorPalette: no COLOR_KEY entry for $keyName")
        return entry.stockFor(variant)
    }

    private fun withSaturationDelta(
        color: Color,
        delta: Float,
    ): Color {
        val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
        val adjusted = (hsb[1] + delta).coerceIn(0f, 1f)
        val tinted = Color.getHSBColor(hsb[0], adjusted, hsb[2])
        // Preserve the original alpha — Color.getHSBColor always returns alpha=255,
        // so a translucent stock (Light DIFF_MODIFIED #478ACC1F) would lose its
        // alpha here without this re-wrap. Critical for the Light variant
        // TEXT_ATTR_BG entries that ship as 12-13% overlays.
        return Color(tinted.red, tinted.green, tinted.blue, color.alpha)
    }

    private fun entry(
        keyName: String,
        mode: VcsWriteMode,
        dark: String,
        mirage: String,
        light: String,
    ): VcsPaletteEntry =
        VcsPaletteEntry(
            keyName = keyName,
            mode = mode,
            stockDark = parseHexColor(dark),
            stockMirage = parseHexColor(mirage),
            stockLight = parseHexColor(light),
        )

    /**
     * Parses a `#RRGGBB` or `#RRGGBBAA` hex string into a [Color]. Java's
     * [Color.decode] silently truncates 8-char hex to 24-bit RGB, losing the
     * alpha channel — Light-variant translucent backgrounds require explicit
     * 8-char handling so the alpha bits survive the parse.
     */
    private fun parseHexColor(hex: String): Color {
        val clean = hex.removePrefix("#")
        require(clean.length == HEX_LEN_OPAQUE || clean.length == HEX_LEN_ALPHA) {
            "VcsColorPalette: invalid hex length ${clean.length} for '$hex'"
        }
        val red = clean.substring(RED_HEX_START, GREEN_HEX_START).toInt(HEX_RADIX)
        val green = clean.substring(GREEN_HEX_START, BLUE_HEX_START).toInt(HEX_RADIX)
        val blue = clean.substring(BLUE_HEX_START, ALPHA_HEX_START).toInt(HEX_RADIX)
        val alpha =
            if (clean.length == HEX_LEN_ALPHA) {
                clean.substring(ALPHA_HEX_START, HEX_LEN_ALPHA).toInt(HEX_RADIX)
            } else {
                ALPHA_OPAQUE
            }
        return Color(red, green, blue, alpha)
    }

    private const val HEX_LEN_OPAQUE: Int = 6
    private const val HEX_LEN_ALPHA: Int = 8
    private const val HEX_RADIX: Int = 16
    private const val ALPHA_OPAQUE: Int = 0xFF
    private const val RED_HEX_START: Int = 0
    private const val GREEN_HEX_START: Int = 2
    private const val BLUE_HEX_START: Int = 4
    private const val ALPHA_HEX_START: Int = 6
}

/**
 * How a [VcsPaletteEntry] is committed to the live
 * [com.intellij.openapi.editor.colors.EditorColorsScheme].
 */
internal enum class VcsWriteMode {
    /** Top-level ColorKey via `scheme.setColor(ColorKey.find(name), color)`. */
    COLOR_KEY,

    /**
     * BACKGROUND slot of a TextAttributesKey — read existing attributes, clone
     * with new background, preserve the other slots (foreground, effect, error
     * stripe, font type), write back via `scheme.setAttributes`.
     */
    TEXT_ATTR_BG,
}

/**
 * One palette entry — a single color key write target plus its 2.6.2 XML stock
 * value per Ayu variant. The applier reads [stockFor] to derive Whisper/Cyberpunk
 * endpoints via HSB saturation offset, then blends between them at the active
 * slider position.
 *
 * Plain class (not `data class`) so the internal constructor is genuinely
 * encapsulated — a `data class` here would expose the internal constructor
 * through the generated `copy()` method and let external callers fabricate
 * palette entries the applier was never designed to consume.
 */
class VcsPaletteEntry internal constructor(
    val keyName: String,
    internal val mode: VcsWriteMode,
    private val stockDark: Color,
    private val stockMirage: Color,
    private val stockLight: Color,
) {
    fun stockFor(variant: AyuVariant): Color =
        when (variant) {
            AyuVariant.DARK -> stockDark
            AyuVariant.MIRAGE -> stockMirage
            AyuVariant.LIGHT -> stockLight
        }
}
