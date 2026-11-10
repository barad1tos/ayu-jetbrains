package dev.ayuislands.settings

import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxReadabilityOptions

/** Requested values remain raw so unavailable cells still expose their existing reset actions. */
internal data class SyntaxPanelSettings(
    val selectedPreset: String?,
    val subordinatePreset: String?,
    val customOverrides: Map<String, String>,
    val customStyles: Map<String, String>,
    val customEmphasis: Map<String, String>,
    val readability: SyntaxReadabilityOptions,
)

internal data class SyntaxPanelView(
    val settings: SyntaxPanelSettings,
    val language: String,
    val actuatedByLanguage: Map<String, Set<PrimitiveCategory>>?,
    val capability: SyntaxCapabilityState?,
    val isEditable: Boolean,
    val isLicensed: Boolean,
    val status: SyntaxPanelStatus? = null,
)

internal data class SyntaxPanelStatus(
    val message: String,
    val detail: String? = null,
)

internal sealed interface SyntaxPanelEvent {
    data class Preset(
        val value: SyntaxPreset,
    ) : SyntaxPanelEvent

    data class DimComments(
        val value: Boolean,
    ) : SyntaxPanelEvent

    data class SoftenDocumentation(
        val value: Boolean,
    ) : SyntaxPanelEvent

    data class QuietOperators(
        val value: Boolean,
    ) : SyntaxPanelEvent

    data class EmphasizeDeclarations(
        val value: Boolean,
    ) : SyntaxPanelEvent

    data class Intensity(
        val language: String,
        val category: PrimitiveCategory,
        val value: Int,
    ) : SyntaxPanelEvent

    data class Style(
        val language: String,
        val category: PrimitiveCategory,
        val value: FontStyleOverride?,
    ) : SyntaxPanelEvent

    data class Emphasis(
        val language: String,
        val category: PrimitiveCategory,
        val value: FontEmphasis?,
    ) : SyntaxPanelEvent

    data class ResetCell(
        val language: String,
        val category: PrimitiveCategory,
    ) : SyntaxPanelEvent

    data class ResetLanguage(
        val language: String,
        val categories: Set<PrimitiveCategory>,
    ) : SyntaxPanelEvent

    data class Language(
        val value: String,
    ) : SyntaxPanelEvent

    data class ShowPrimitive(
        val category: PrimitiveCategory,
    ) : SyntaxPanelEvent

    data object SliderReleased : SyntaxPanelEvent

    data object RecoverSupport : SyntaxPanelEvent

    data object OpenHelp : SyntaxPanelEvent

    data object Unlock : SyntaxPanelEvent
}
