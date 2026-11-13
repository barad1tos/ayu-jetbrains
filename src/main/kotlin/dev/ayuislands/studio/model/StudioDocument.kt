package dev.ayuislands.studio.model

import dev.ayuislands.rotation.RotationRuntimeCheckpoint

internal data class StudioProjectRecord(
    val key: StudioProjectKey,
    val displayName: String,
    val current: AcceptedAppearance,
)

internal data class StudioCurrents(
    val global: AcceptedAppearance,
    val projects: StudioList<StudioProjectRecord>,
)

internal data class SharedPreferences(
    val plugins: StudioFields,
    val workspace: StudioFields,
    val system: StudioFields,
    val mappings: StudioFields,
    val systemAccentPolicy: SystemAccentPolicy,
)

internal data class AuxiliaryPreferences(
    val ui: StudioFields,
    val resources: StudioFields,
    val legacyMarkers: StudioFields,
)

internal sealed interface LegacyFile {
    data object Absent : LegacyFile

    data class Present(
        val bytes: StudioBytes,
    ) : LegacyFile
}

internal data class LegacyArchive(
    val store: LegacyStore,
    val file: LegacyFile,
)

internal data class MigrationSource(
    val id: String,
    val archives: StudioList<LegacyArchive>,
)

internal data class DocumentHeader(
    val schema: Int,
    val revision: Long,
    val transactionId: String,
    val migration: MigrationSource,
)

internal data class StudioRuntimeState(
    val legacy: StudioFields,
    val rotation: RotationRuntimeCheckpoint? = null,
    val effectiveContinuation: StudioEffectiveContinuation? = null,
)

internal data class StudioDocument(
    val header: DocumentHeader,
    val catalog: StudioList<ProfileDefinition>,
    val currents: StudioCurrents,
    val shared: SharedPreferences,
    val auxiliary: AuxiliaryPreferences,
    val runtime: StudioRuntimeState,
    val sessions: StudioList<StudioSessionRecord>,
    val extensions: StudioList<OpaqueRecord> = StudioList(emptyList()),
)
