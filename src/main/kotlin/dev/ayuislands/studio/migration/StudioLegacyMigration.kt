package dev.ayuislands.studio.migration

import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.rotation.planRotationAppointment
import dev.ayuislands.rotation.rotationIntervalMillis
import dev.ayuislands.settings.migratePanelWidthMode
import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.AuxiliaryPreferences
import dev.ayuislands.studio.model.DocumentHeader
import dev.ayuislands.studio.model.FactoryRecipe
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioCurrents
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.model.matchesLegacyType

internal data class LegacySnapshot(
    val source: MigrationSource,
    val fields: StudioFields,
    val factories: StudioList<ProfileDefinition.Factory>,
    val transactionId: String,
)

internal enum class MigrationIssue { INVALID_IDENTITY, INCOMPLETE_CAPTURE, INCOMPLETE_ARCHIVE, INVALID_FACTORIES }

internal sealed interface MigrationResult {
    data class Ready(
        val document: StudioDocument,
    ) : MigrationResult

    data class Conflict(
        val issue: MigrationIssue,
        val fields: StudioList<LegacyFieldKey>,
    ) : MigrationResult
}

internal fun MigrationSource.hasCompleteArchives(): Boolean =
    archives.size == LegacyStore.entries.size && archives.map { it.store }.toSet() == LegacyStore.entries.toSet()

internal object StudioLegacyMigration {
    private val types = StudioFieldCatalog.fields.associate { LegacyFieldKey(it.source, it.name) to it.type }

    fun plan(capture: LegacySnapshot): MigrationResult {
        if (capture.source.id.isBlank() ||
            capture.transactionId.isBlank()
        ) {
            return conflict(MigrationIssue.INVALID_IDENTITY)
        }
        if (!capture.source.hasCompleteArchives()) return conflict(MigrationIssue.INCOMPLETE_ARCHIVE)
        if (capture.factories.map { it.id } != FactoryRecipe.entries.map { it.id } ||
            capture.factories.any { it.recipeVersion != 1 }
        ) {
            return conflict(MigrationIssue.INVALID_FACTORIES)
        }
        val actual = capture.fields.records.associate { it.key to it.value }
        val invalid =
            (types.keys - actual.keys) +
                actual.filter { (key, value) -> types[key]?.let(value::matchesLegacyType) != true }.keys
        if (invalid.isNotEmpty()) return conflict(MigrationIssue.INCOMPLETE_CAPTURE, invalid)
        return MigrationResult.Ready(document(capture, capture.source.archives.all { it.file == LegacyFile.Absent }))
    }

    private fun document(
        capture: LegacySnapshot,
        fresh: Boolean,
    ): StudioDocument {
        val settings =
            capture.fields.records.filter { it.key.source == LegacyStore.SETTINGS }.associate {
                it.key.name to
                    it.value
            }
        val interpreted = interpretLegacy(capture.fields, settings)
        val appearance = StudioFieldMapping.appearance(capture.fields)
        val custom =
            if (fresh) {
                null
            } else {
                ProfileDefinition.User(
                    ProfileId("migrated-custom:${capture.source.id}"),
                    "Custom",
                    StudioFieldMapping.copyAppearance(appearance),
                    revision = 1,
                )
            }
        val policy =
            when {
                fresh -> SystemAccentPolicy.BY_PROFILE
                settings.getValue("followSystemAccent") == RawValue.BooleanValue(true) -> SystemAccentPolicy.FORCE_ON
                else -> SystemAccentPolicy.FORCE_OFF
            }
        val accepted =
            AcceptedAppearance(appearance, revision = 0, source = custom?.let { ProfileReference(it.id, it.revision) })
        return StudioDocument(
            DocumentHeader(1, 0, capture.transactionId, capture.source),
            StudioList(capture.factories + listOfNotNull(custom)),
            StudioCurrents(accepted, StudioList(emptyList())),
            SharedPreferences(
                StudioFieldMapping.select(interpreted, StudioFieldOwner.PLUGINS),
                StudioFieldMapping.select(interpreted, StudioFieldOwner.WORKSPACE),
                StudioFieldMapping.select(interpreted, StudioFieldOwner.SYSTEM),
                StudioFieldMapping.select(interpreted, StudioFieldOwner.MAPPINGS),
                policy,
            ),
            AuxiliaryPreferences(
                StudioFieldMapping.select(interpreted, StudioFieldOwner.UI),
                StudioFieldMapping.select(capture.fields, StudioFieldOwner.RESOURCES),
                StudioFieldMapping.select(capture.fields, StudioFieldOwner.MIGRATION),
            ),
            StudioRuntimeState(
                StudioFieldMapping.select(capture.fields, StudioFieldOwner.RUNTIME),
                rotation = importRotation(settings, accepted.revision, fresh),
            ),
            sessions = StudioList(emptyList()),
            extensions = capture.fields.extensions,
        )
    }

    private fun interpretLegacy(
        fields: StudioFields,
        settings: Map<String, RawValue>,
    ): StudioFields {
        fun width(
            mode: String,
            autoFit: String,
        ): RawValue =
            migratePanelWidthMode(
                settings.getValue(autoFit) == RawValue.BooleanValue(true),
                (settings.getValue(mode) as? RawValue.Text)?.value,
            )?.let(RawValue::Text) ?: RawValue.Null

        val replacements =
            mapOf(
                "projectPanelWidthMode" to width("projectPanelWidthMode", "autoFitProjectPanelWidth"),
                "commitPanelWidthMode" to width("commitPanelWidthMode", "autoFitCommitPanelWidth"),
                "premiumOnboardingShown" to
                    if (settings.getValue("trialWelcomeShown") == RawValue.BooleanValue(true)) {
                        RawValue.BooleanValue(true)
                    } else {
                        settings.getValue("premiumOnboardingShown")
                    },
            )
        return fields.copy(
            records =
                StudioList(
                    fields.records.map { field ->
                        val replacement =
                            replacements[field.key.name].takeIf {
                                field.key.source == LegacyStore.SETTINGS
                            }
                        if (replacement == null) field else field.copy(value = replacement)
                    },
                ),
        )
    }

    private fun importRotation(
        settings: Map<String, RawValue>,
        revision: Long,
        fresh: Boolean,
    ): RotationRuntimeCheckpoint {
        val lastSwitch = (settings.getValue("accentRotationLastSwitchMs") as RawValue.LongValue).value
        val checkpoint =
            RotationRuntimeCheckpoint(
                RuntimeOwner(StudioTarget.Global, revision),
                sequenceCursorRaw = (settings.getValue("accentRotationPresetIndex") as RawValue.Integer).value,
                successfulStepSerial = 0,
                lastSuccessfulStepEpochMillis = lastSwitch.takeIf { it > 0 },
            )
        if (fresh || settings.getValue("accentRotationEnabled") != RawValue.BooleanValue(true)) return checkpoint
        if (lastSwitch == 0L) return checkpoint.copy(resume = RotationResume.NoHistory)
        val hours = (settings.getValue("accentRotationIntervalHours") as RawValue.Integer).value
        val schedule =
            if (lastSwitch >
                0
            ) {
                planRotationAppointment(lastSwitch, rotationIntervalMillis(hours), 0)
            } else {
                null
            }
        return if (schedule != null) {
            checkpoint.copy(schedule = schedule)
        } else {
            checkpoint.copy(
                resume = RotationResume.FullInterval(FullIntervalReason.REPAIRED_SCHEDULE),
            )
        }
    }

    private fun conflict(
        issue: MigrationIssue,
        fields: Collection<LegacyFieldKey> = emptyList(),
    ): MigrationResult.Conflict = MigrationResult.Conflict(issue, StudioList(fields))
}
