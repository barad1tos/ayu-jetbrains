package dev.ayuislands.studio.session

import dev.ayuislands.licensing.LicenseDefaults
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.ProInitialization
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.syntax.SyntaxIntensityBaseState

/** Initializes only proven untouched preferences; confirmed guards share the ordinary acceptance candidate. */
internal object StudioInitializationPolicy {
    fun apply(
        request: AcceptanceRequest,
        latest: StudioDocument,
        candidate: StudioDocument,
    ): StudioDocument {
        val initialization = request.initialization ?: return candidate
        val baseline = initialization.baseline
        if (baseline.header.migration != latest.header.migration || !guardsMatch(baseline, latest)) return candidate
        val captured = StudioTargets.current(baseline, request.session.target) ?: return candidate
        val before = StudioTargets.current(latest, request.session.target) ?: return candidate
        val current = StudioTargets.current(candidate, request.session.target) ?: return candidate
        val defaults = defaults(baseline)
        val fresh =
            latest.header.migration.archives
                .all { it.file == LegacyFile.Absent }
        val canInitializeGlow = before == captured && before.revision == 0L && current.source == null
        val glow =
            if (fresh && canInitializeGlow) {
                mergeUntouched(current.appearance.glow, captured.appearance.glow, initialization, defaults)
            } else {
                current.appearance.glow
            }
        val workspace =
            if (fresh) {
                mergeUntouched(candidate.shared.workspace, baseline.shared.workspace, initialization, defaults)
            } else {
                candidate.shared.workspace
            }
        val initialized =
            candidate.copy(
                shared = candidate.shared.copy(workspace = workspace),
                auxiliary =
                    candidate.auxiliary.copy(
                        legacyMarkers = applyDefaults(candidate.auxiliary.legacyMarkers, defaults),
                    ),
                runtime = candidate.runtime.copy(legacy = applyDefaults(candidate.runtime.legacy, defaults)),
            )
        if (glow == current.appearance.glow) return initialized
        return StudioTargets.replace(
            initialized,
            request.session.target,
            current.copy(appearance = current.appearance.copy(glow = glow), revision = before.revision + 1),
        )
    }

    private fun guardsMatch(
        baseline: StudioDocument,
        latest: StudioDocument,
    ): Boolean =
        baseline.auxiliary.legacyMarkers.records == latest.auxiliary.legacyMarkers.records &&
            field(baseline.runtime.legacy, "everBeenPro") == field(latest.runtime.legacy, "everBeenPro")

    /** Derives differences from the existing recipe, with no parallel defaults or setter catalog. */
    private fun defaults(baseline: StudioDocument): Map<LegacyFieldKey, StudioField> {
        val state =
            freshSettingsState().apply {
                everBeenPro = enabled(baseline.runtime.legacy, "everBeenPro")
                proDefaultsApplied = enabled(baseline.auxiliary.legacyMarkers, "proDefaultsApplied")
                workspaceDefaultsApplied = enabled(baseline.auxiliary.legacyMarkers, "workspaceDefaultsApplied")
            }
        val syntax = SyntaxIntensityBaseState()
        val mappings = AccentMappingsState()
        val before = StudioLegacyFields.capture(state, syntax, mappings).records.associateBy { it.key }
        if (!state.proDefaultsApplied) LicenseDefaults.enablePro(state)
        if (!state.workspaceDefaultsApplied) LicenseDefaults.applyWorkspace(state)
        return StudioLegacyFields
            .capture(state, syntax, mappings)
            .records
            .filter { it.value != before.getValue(it.key).value }
            .associateBy { it.key }
    }

    private fun mergeUntouched(
        fields: StudioFields,
        captured: StudioFields,
        initialization: ProInitialization,
        defaults: Map<LegacyFieldKey, StudioField>,
    ): StudioFields {
        val previous = captured.records.associateBy { it.key }
        val fresh = initialization.fresh.records.associateBy { it.key }
        return fields.copy(
            records =
                StudioList(
                    fields.records.map { current ->
                        val desired = defaults[current.key]
                        if (desired != null && current == previous[current.key] && current == fresh[current.key]) {
                            StudioFieldEdits.edit(current, FieldPart.Value, FieldValue.Present(desired.value))
                        } else {
                            current
                        }
                    },
                ),
        )
    }

    private fun applyDefaults(
        fields: StudioFields,
        defaults: Map<LegacyFieldKey, StudioField>,
    ): StudioFields =
        fields.copy(
            records =
                StudioList(
                    fields.records.map { current ->
                        val desired = defaults[current.key]
                        if (desired == null) {
                            current
                        } else {
                            StudioFieldEdits.edit(current, FieldPart.Value, FieldValue.Present(desired.value))
                        }
                    },
                ),
        )

    private fun enabled(
        fields: StudioFields,
        name: String,
    ): Boolean = field(fields, name).value == RawValue.BooleanValue(true)

    private fun field(
        fields: StudioFields,
        name: String,
    ): StudioField = fields.records.single { it.key.name == name }
}
