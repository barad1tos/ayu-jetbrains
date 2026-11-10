package dev.ayuislands.font

import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.FontAvailability

/** Raw selection remains distinct from its optional supported rendering. */
internal data class FontPanelSelection(
    val rawName: String?,
    val settings: FontSettings?,
)

/** An explicitly observed local sample; constructing this value never resolves a native font. */
internal data class FontPanelPreview(
    val settings: FontSettings,
    val family: String,
    val isAvailable: Boolean,
)

/** Availability belongs to the selected request, independently of the Current/Draft sample. */
internal data class FontPanelResources(
    val availability: FontAvailability,
    val installedFamilies: List<String>,
    val isMac: Boolean,
    val terminal: String?,
    val canManage: Boolean,
)

internal data class FontPanelView(
    val selection: FontPanelSelection,
    val isEnabled: Boolean,
    val preview: FontPanelPreview?,
    val resources: FontPanelResources,
    val isEditable: Boolean,
)

/** Native controls report intent; their owner decides how to stage or execute it. */
internal sealed interface FontPanelEvent {
    data class Enable(
        val value: Boolean,
    ) : FontPanelEvent

    data class Preset(
        val value: FontPreset,
    ) : FontPanelEvent

    data class Family(
        val value: String,
    ) : FontPanelEvent

    data class Size(
        val value: Float,
    ) : FontPanelEvent

    data class Spacing(
        val value: Float,
    ) : FontPanelEvent

    data class Ligatures(
        val value: Boolean,
    ) : FontPanelEvent

    data class Weight(
        val value: FontWeight,
    ) : FontPanelEvent

    data class Console(
        val value: Boolean,
    ) : FontPanelEvent

    data object Reset : FontPanelEvent

    data object Reapply : FontPanelEvent

    data class Resource(
        val preset: FontPreset,
        val action: FontAction,
    ) : FontPanelEvent

    data class Terminal(
        val value: String,
    ) : FontPanelEvent

    data class CopyCommand(
        val preset: FontPreset,
    ) : FontTerminalAction

    data class OpenTerminal(
        val preset: FontPreset,
        val terminal: String?,
    ) : FontTerminalAction
}

/** Explicit clipboard or terminal actions; selecting a destination remains a staged shared preference. */
internal sealed interface FontTerminalAction : FontPanelEvent
