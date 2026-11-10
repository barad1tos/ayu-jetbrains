package dev.ayuislands.studio.migration

import dev.ayuislands.settings.freshSettingsState
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
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.runtime.StudioRotationProjection

internal data class LegacySnapshot(
    val source: MigrationSource,
    val fields: StudioFields,
    val factories: StudioList<ProfileDefinition.Factory>,
    val transactionId: String,
)

internal sealed interface MigrationResult {
    data class Ready(
        val document: StudioDocument,
    ) : MigrationResult

    data class Conflict(
        val issue: MigrationIssue,
        val fields: StudioList<LegacyFieldKey>,
    ) : MigrationResult
}

internal enum class MigrationIssue { INCOMPLETE_CAPTURE, INCOMPLETE_ARCHIVE, INVALID_FACTORIES }

internal object StudioLegacyMigration {
    fun plan(capture: LegacySnapshot): MigrationResult {
        val expected = StudioFieldCatalog.fields.map { LegacyFieldKey(it.source, it.name) }.toSet()
        val actual =
            capture.fields.records
                .map { it.key }
                .toSet()
        if (actual != expected) {
            return MigrationResult.Conflict(
                MigrationIssue.INCOMPLETE_CAPTURE,
                StudioList((expected - actual) + (actual - expected)),
            )
        }
        val archives = capture.source.archives
        if (archives.size != LegacyStore.entries.size ||
            archives.map { it.store }.toSet() != LegacyStore.entries.toSet()
        ) {
            return MigrationResult.Conflict(MigrationIssue.INCOMPLETE_ARCHIVE, StudioList(emptyList()))
        }
        if (capture.factories.map { it.id } != FactoryRecipe.entries.map { it.id }) {
            return MigrationResult.Conflict(MigrationIssue.INVALID_FACTORIES, StudioList(emptyList()))
        }
        val fresh = archives.all { it.file == LegacyFile.Absent }
        val interpreted = interpretLegacy(capture.fields)
        val appearance = StudioFieldMapping.appearance(capture.fields)
        val custom =
            if (fresh) {
                emptyList()
            } else {
                listOf(
                    ProfileDefinition.User(
                        ProfileId("migrated-custom:${capture.source.id}"),
                        "Custom",
                        StudioFieldMapping.copyAppearance(appearance),
                        revision = 1,
                    ),
                )
            }
        val follow =
            capture.fields.records.single {
                it.key ==
                    LegacyFieldKey(LegacyStore.SETTINGS, "followSystemAccent")
            }
        val policy =
            when {
                fresh -> SystemAccentPolicy.BY_PROFILE
                follow.value == RawValue.BooleanValue(true) -> SystemAccentPolicy.FORCE_ON
                else -> SystemAccentPolicy.FORCE_OFF
            }
        val document =
            StudioDocument(
                header = DocumentHeader(schema = 1, revision = 0, capture.transactionId, capture.source),
                catalog = StudioList(capture.factories + custom),
                currents =
                    StudioCurrents(
                        AcceptedAppearance(
                            appearance,
                            revision = 0,
                            source = custom.singleOrNull()?.let { ProfileReference(it.id, it.revision) },
                        ),
                        StudioList(emptyList()),
                    ),
                shared = sharedPreferences(interpreted, policy),
                auxiliary =
                    AuxiliaryPreferences(
                        StudioFieldMapping.select(interpreted, StudioFieldOwner.UI),
                        StudioFieldMapping.select(capture.fields, StudioFieldOwner.RESOURCES),
                        StudioFieldMapping.select(capture.fields, StudioFieldOwner.MIGRATION),
                    ),
                runtime = StudioRuntimeState(StudioFieldMapping.select(capture.fields, StudioFieldOwner.RUNTIME)),
                sessions = StudioList(emptyList()),
                extensions = StudioList(capture.fields.extensions),
            )
        return MigrationResult.Ready(
            document.copy(
                runtime = document.runtime.copy(rotation = StudioRotationProjection.importLegacy(document, fresh)),
            ),
        )
    }

    private fun interpretLegacy(fields: StudioFields): StudioFields {
        val settings = fields.records.filter { it.key.source == LegacyStore.SETTINGS }.associateBy { it.key.name }
        val widths =
            freshSettingsState().apply {
                autoFitProjectPanelWidth =
                    (settings.getValue("autoFitProjectPanelWidth").value as RawValue.BooleanValue).value
                autoFitCommitPanelWidth =
                    (settings.getValue("autoFitCommitPanelWidth").value as RawValue.BooleanValue).value
                projectPanelWidthMode = (settings.getValue("projectPanelWidthMode").value as? RawValue.Text)?.value
                commitPanelWidthMode = (settings.getValue("commitPanelWidthMode").value as? RawValue.Text)?.value
                migrateWidthModes()
            }
        val welcomeShown = settings.getValue("trialWelcomeShown").value == RawValue.BooleanValue(true)
        val replacements =
            mapOf(
                "projectPanelWidthMode" to (widths.projectPanelWidthMode?.let(RawValue::Text) ?: RawValue.Null),
                "commitPanelWidthMode" to (widths.commitPanelWidthMode?.let(RawValue::Text) ?: RawValue.Null),
                "premiumOnboardingShown" to
                    if (welcomeShown) {
                        RawValue.BooleanValue(true)
                    } else {
                        settings.getValue("premiumOnboardingShown").value
                    },
            )
        return fields.copy(
            records =
                StudioList(
                    fields.records.map { field ->
                        if (field.key.source == LegacyStore.SETTINGS && field.key.name in replacements) {
                            field.copy(value = replacements.getValue(field.key.name))
                        } else {
                            field
                        }
                    },
                ),
        )
    }

    private fun sharedPreferences(
        fields: StudioFields,
        policy: SystemAccentPolicy,
    ): SharedPreferences =
        SharedPreferences(
            plugins = StudioFieldMapping.select(fields, StudioFieldOwner.PLUGINS),
            workspace = StudioFieldMapping.select(fields, StudioFieldOwner.WORKSPACE),
            system = StudioFieldMapping.select(fields, StudioFieldOwner.SYSTEM),
            mappings = StudioFieldMapping.select(fields, StudioFieldOwner.MAPPINGS),
            systemAccentPolicy = policy,
        )
}
