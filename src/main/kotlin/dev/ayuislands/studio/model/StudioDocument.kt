package dev.ayuislands.studio.model

import dev.ayuislands.rotation.RotationRuntimeCheckpoint

@JvmInline
internal value class ProfileId(
    val value: String,
)

@JvmInline
internal value class StudioProjectKey(
    val value: String,
)

internal sealed interface StudioTarget {
    data object Global : StudioTarget

    data class Project(
        val key: StudioProjectKey,
    ) : StudioTarget
}

internal enum class SourcePresence { ABSENT, NULL, VALUE }

internal data class FieldOrigin(
    val presence: SourcePresence,
    val lexeme: StudioBytes? = null,
)

internal data class LegacyFieldKey(
    val source: LegacyStore,
    val name: String,
)

internal data class StudioField(
    val key: LegacyFieldKey,
    val value: RawValue,
    val origin: FieldOrigin,
)

internal data class OpaqueRecord(
    val key: String,
    val bytes: StudioBytes,
)

/** Ordered, complete values; source evidence is independent of the interpreted value. */
internal data class StudioFields(
    val records: StudioList<StudioField>,
    val extensions: StudioList<OpaqueRecord> = StudioList(emptyList()),
) {
    init {
        require(records.map { it.key }.toSet().size == records.size) { "Duplicate field identity" }
    }
}

internal data class AppearanceSnapshot(
    val accent: StudioFields,
    val font: StudioFields,
    val glow: StudioFields,
    val syntax: StudioFields,
    val vcs: StudioFields,
) {
    init {
        requireDomain(accent, StudioFieldOwner.ACCENT)
        requireDomain(font, StudioFieldOwner.FONT)
        requireDomain(glow, StudioFieldOwner.GLOW)
        requireDomain(syntax, StudioFieldOwner.SYNTAX)
        requireDomain(vcs, StudioFieldOwner.VCS)
    }
}

private fun requireDomain(
    fields: StudioFields,
    owner: StudioFieldOwner,
) {
    val expected =
        StudioFieldCatalog.fields
            .filter { it.owner == owner }
            .map {
                LegacyFieldKey(
                    it.source,
                    it.name,
                )
            }.toSet()
    require(fields.records.map { it.key }.toSet() == expected) { "Incomplete or misplaced $owner fields" }
}

internal data class ProfileReference(
    val id: ProfileId,
    val revision: Long,
)

internal sealed interface ProfileDefinition {
    val id: ProfileId
    val name: String
    val appearance: AppearanceSnapshot

    data class Factory(
        override val id: ProfileId,
        override val name: String,
        override val appearance: AppearanceSnapshot,
        val recipeVersion: Int,
    ) : ProfileDefinition

    data class User(
        override val id: ProfileId,
        override val name: String,
        override val appearance: AppearanceSnapshot,
        val revision: Long,
    ) : ProfileDefinition
}

internal data class AcceptedAppearance(
    val appearance: AppearanceSnapshot,
    val revision: Long,
    val source: ProfileReference?,
)

internal data class StudioProjectRecord(
    val key: StudioProjectKey,
    val displayName: String,
    val current: AcceptedAppearance,
)

internal data class StudioCurrents(
    val global: AcceptedAppearance,
    val projects: StudioList<StudioProjectRecord>,
)

internal enum class SystemAccentPolicy { BY_PROFILE, FORCE_ON, FORCE_OFF }

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
