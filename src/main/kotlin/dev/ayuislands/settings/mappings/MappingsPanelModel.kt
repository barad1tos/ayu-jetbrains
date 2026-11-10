package dev.ayuislands.settings.mappings

import javax.swing.Icon

internal enum class MappingsTable { PROJECTS, LANGUAGES }

/** Raw presentation retains unknown colors, exact keys, and missing display names without normalization. */
internal data class MappingRow(
    val key: String,
    val displayName: String?,
    val hex: String,
)

internal data class MappingsPanelSettings(
    val projects: List<MappingRow>,
    val languages: List<MappingRow>,
)

/** Observations supplied by the host; rendering never warms detection or consults the filesystem. */
internal data class MappingsContext(
    val projectKey: String?,
    val currentAccentHex: String?,
    val canPinCurrent: Boolean,
    val resolution: ProjectLanguageResolutionPanel.State,
)

internal data class MappingsObservations(
    val languageNames: Map<String, String> = emptyMap(),
    val languageIcons: Map<String, Icon> = emptyMap(),
    val orphanProjectKeys: Set<String> = emptySet(),
)

internal data class MappingsPanelView(
    val settings: MappingsPanelSettings,
    val context: MappingsContext,
    val observations: MappingsObservations,
    val isEditable: Boolean,
    val isLicensed: Boolean,
)

internal sealed interface MappingsPanelAction {
    data class Add(
        val table: MappingsTable,
    ) : MappingsPanelAction

    data class EditColor(
        val table: MappingsTable,
        val key: String,
    ) : MappingsPanelAction

    data object PinCurrent : MappingsPanelAction

    data object Rescan : MappingsPanelAction

    data object Unlock : MappingsPanelAction

    data class Expanded(
        val value: Boolean,
    ) : MappingsPanelAction
}

/** Completed explicit edits; requesting a dialog or a scan is not a persisted mapping change. */
internal sealed interface MappingsEdit {
    data class AddProject(
        val mapping: ProjectMapping,
    ) : MappingsEdit

    data class AddLanguage(
        val mapping: LanguageMapping,
    ) : MappingsEdit

    data class Color(
        val table: MappingsTable,
        val key: String,
        val hex: String,
    ) : MappingsEdit

    data class Remove(
        val table: MappingsTable,
        val key: String,
    ) : MappingsEdit

    data class Fallback(
        val projectKey: String,
        val hex: String?,
    ) : MappingsEdit

    data class ForcedLanguage(
        val projectKey: String,
        val languageId: String?,
    ) : MappingsEdit
}
