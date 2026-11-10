package dev.ayuislands.syntax

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.persistence.SyntaxStateView
import java.awt.Font

internal const val SYNTAX_INTENSITY_SCHEMA_VERSION = 4

/**
 * Per-category font style for the Custom drill-down (Part A backend).
 *
 * [fontType] is the `java.awt.Font` bitmask the applicator writes verbatim
 * into `TextAttributes.fontType` (`PLAIN=0`, `BOLD=1`, `ITALIC=2`,
 * `BOLD_ITALIC=3`). The enum [name] is the on-disk token persisted in
 * [SyntaxIntensityBaseState.customStyles]; bold and italic are combinable via
 * the single [BOLD_ITALIC] entry rather than two independent flags so the
 * stored value stays a closed catalog that [fromName] can validate.
 */
enum class FontStyleOverride(
    val fontType: Int,
) {
    PLAIN(Font.PLAIN),
    BOLD(Font.BOLD),
    ITALIC(Font.ITALIC),
    BOLD_ITALIC(Font.BOLD or Font.ITALIC),
    ;

    companion object {
        /**
         * Tamper-safe decode: an unknown / tampered string yields `null` so the
         * caller skips the cell (mirrors the `toIntOrNull` discipline used for
         * the intensity slider in [SyntaxIntensityState.toPresetConfig]). Never
         * throws — a bad XML value degrades to "inherit the source style".
         */
        fun fromName(raw: String?): FontStyleOverride? = entries.firstOrNull { it.name == raw }

        fun fromFlags(
            isBold: Boolean,
            isItalic: Boolean,
        ): FontStyleOverride =
            when {
                isBold && isItalic -> BOLD_ITALIC
                isBold -> BOLD
                isItalic -> ITALIC
                else -> PLAIN
            }
    }
}

/**
 * Additive per-category font emphasis for the Custom drill-down.
 *
 * Unlike [FontStyleOverride], this closed catalog omits [Font.PLAIN]: an
 * absent cell is a strict no-op that preserves the source font style.
 */
enum class FontEmphasis(
    val fontType: Int,
) {
    BOLD(Font.BOLD),
    ITALIC(Font.ITALIC),
    BOLD_ITALIC(Font.BOLD or Font.ITALIC),
    ;

    companion object {
        fun fromName(raw: String?): FontEmphasis? = entries.firstOrNull { it.name == raw }

        fun fromFlags(
            isBold: Boolean,
            isItalic: Boolean,
        ): FontEmphasis? =
            when {
                isBold && isItalic -> BOLD_ITALIC
                isBold -> BOLD
                isItalic -> ITALIC
                else -> null
            }
    }
}

/** Accepted syntax projection; the legacy bean below is migration input only. */
@Service
internal class SyntaxIntensityState {
    val state: SyntaxStateView? get() = StudioService.getInstance().acceptedView?.syntax

    fun toPresetConfig(): SyntaxPresetConfig? = state?.toPresetConfig()

    companion object {
        fun getInstance(): SyntaxIntensityState = service()
    }
}

/**
 * Legacy import data. This bean is not an independent persistence owner.
 *
 * Persisted schema:
 *  - [selectedPreset]: enum name string, default `"AMBIENT"`.
 *  - [subordinatePreset]: enum name string, default `"AMBIENT"`. The named
 *    preset whose curve fills the untouched (sparse) cells while the user is
 *    in the Custom drill-down. Legacy / absent XML deserialises to `"AMBIENT"`
 *    via the `string("AMBIENT")` delegate, so old state stays valid with no
 *    read-time migration.
 *  - [customOverrides]: FLAT composite-key map. Key = `"language|category"`,
 *    value = slider position `"0..100"` as string. Matches the proven
 *    [dev.ayuislands.settings.mappings.AccentMappingsState] flat-map BaseState
 *    shape; the nested `Map<String, Map<String, Int>>` shape consumed by the
 *    applicator and preset detector is reconstructed by
 *    [SyntaxIntensityState.toPresetConfig].
 *  - [customStyles]: sibling FLAT composite-key map for per-category font
 *    style. Key = `"language|category"` (identical shape to [customOverrides]),
 *    value = [FontStyleOverride] enum `name` (`"BOLD"` / `"ITALIC"` /
 *    `"BOLD_ITALIC"` / `"PLAIN"`). Sparse: only cells the user styles
 *    materialise; an absent cell inherits the source attribute's font style.
 *    Independent of the intensity slider — a cell may carry a style override
 *    with no slider, or a slider with no style.
 *  - [customEmphasis]: sibling FLAT composite-key map for per-category font
 *    emphasis. Key = `"language|category"`; value = [FontEmphasis] enum
 *    `name` (`"BOLD"` / `"ITALIC"` / `"BOLD_ITALIC"`). Sparse and additive:
 *    an absent cell is a strict no-op, and this map never rewrites legacy
 *    [customStyles] replacement tokens.
 *  - [dimComments], [softenDocumentation], [quietOperators], and
 *    [emphasizeDeclarations]: global readability modifiers layered on top of
 *    any selected preset. Defaults are false, so existing XML with no elements
 *    stays byte-identical until the user opts in.
 *  - [schemaVersion]: forward-compat sentinel; default `4` since additive
 *    emphasis was introduced. A v3 config without [customEmphasis] keeps its
 *    loaded schema version and deserialises this map as empty, so the bump
 *    needs NO read-time migration.
 *
 * The free tier never writes to [customOverrides] / [customStyles] — the
 * premium Custom drill-down is the only writer.
 */
class SyntaxIntensityBaseState : BaseState() {
    var selectedPreset by string("AMBIENT")
    var subordinatePreset by string("AMBIENT")
    var customOverrides by map<String, String>()
    var customStyles by map<String, String>()
    var customEmphasis by map<String, String>()
    var dimComments by property(false)
    var softenDocumentation by property(false)
    var quietOperators by property(false)
    var emphasizeDeclarations by property(false)
    var schemaVersion by property(SYNTAX_INTENSITY_SCHEMA_VERSION)
}
