package dev.ayuislands.studio

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIdentity
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.profiles.StudioProjectState
import dev.ayuislands.studio.session.QuickCommandPreparation
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioQuickCommand
import dev.ayuislands.studio.session.StudioQuickCommands
import dev.ayuislands.studio.session.StudioSessionRecords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioIconPinTest {
    private val key = StudioProjectKey("/work/icon")

    @Test
    fun `icon pin carries captured key and appends only the paired project rows`() {
        val document = document()
        val request = request(document)
        assertEquals(key, request.iconPin)
        val plan = assertIs<AcceptancePlanResult.Ready>(plan(request, document)).plan
        val accepted = assertIs<AcceptancePlan.Commit>(plan).candidate
        assertEquals(
            listOf("/work/older", key.value),
            assertIs<RawValue.OrderedMap>(accepted.shared.mappings.value("projectAccents")).entries.map { it.key },
        )
        assertEquals(
            RawValue.Text("Icon project"),
            assertIs<RawValue.OrderedMap>(accepted.shared.mappings.value("projectDisplayNames")).entries.last().value,
        )
        assertEquals(document.currents, accepted.currents)
        assertEquals(document.catalog, accepted.catalog)
    }

    @Test
    fun `icon extracted before auto-pin is disabled cannot enter later acceptance`() {
        val original = document()
        val request = request(original)
        val latest =
            original.copy(
                shared =
                    original.shared.copy(
                        system =
                            original.shared.system.copy(
                                records =
                                    StudioList(
                                        original.shared.system.records.map {
                                            if (it.key.name ==
                                                "projectIconAccentEnabled"
                                            ) {
                                                it.copy(value = RawValue.BooleanValue(false))
                                            } else {
                                                it
                                            }
                                        },
                                    ),
                            ),
                    ),
            )
        assertIs<AcceptancePlanResult.Blocked>(plan(request, latest))
        assertEquals(original.currents, latest.currents)
    }

    @Test
    fun `icon extracted before an explicit user pin cannot overwrite the accepted pin or its name`() {
        val original = document()
        val request = request(original)
        val latest =
            original.copy(
                shared =
                    original.shared.copy(
                        mappings =
                            original.shared.mappings.copy(
                                records =
                                    StudioList(
                                        original.shared.mappings.records.map {
                                            when (it.key.name) {
                                                "projectAccents", "projectDisplayNames" -> {
                                                    val value =
                                                        if (it.key.name ==
                                                            "projectAccents"
                                                        ) {
                                                            "#112233"
                                                        } else {
                                                            "User name"
                                                        }
                                                    it.copy(
                                                        value =
                                                            RawValue.OrderedMap(
                                                                StudioList(
                                                                    (it.value as RawValue.OrderedMap).entries +
                                                                        RawValue.Entry(key.value, RawValue.Text(value)),
                                                                ),
                                                            ),
                                                    )
                                                }
                                                else -> it
                                            }
                                        },
                                    ),
                            ),
                    ),
            )
        assertIs<AcceptancePlanResult.Blocked>(plan(request, latest))
        assertEquals(
            RawValue.Text("#112233"),
            (latest.shared.mappings.value("projectAccents") as RawValue.OrderedMap).entries.last().value,
        )
    }

    private fun document(): StudioDocument {
        val state = AyuIslandsState().apply { projectIconAccentEnabled = true }
        val mappings =
            dev.ayuislands.settings.mappings.AccentMappingsState().apply {
                projectAccents["/work/older"] = "#445566"
                projectDisplayNames["/work/older"] = "Older"
            }
        val capture = StudioFixtures.capture(state).copy(fields = StudioFixtures.fields(state, mappings = mappings))
        val global = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture)).document
        return StudioProjectState.initialize(global, key, "Icon project", global.currents.global)
    }

    private fun state(document: StudioDocument): StudioSessionState {
        val record =
            requireNotNull(StudioSessionRecords.current(document, StudioTarget.Project(key), "icon", DraftId("icon")))
        return StudioSessionState(SessionIdentity(record.id, record.target, 1), record, SessionPhase.Editing)
    }

    private fun request(document: StudioDocument): AcceptanceRequest {
        val state = state(document)
        return assertIs<QuickCommandPreparation.Ready>(
            StudioQuickCommands.prepare(
                StudioQuickCommand.PinIfAbsent("#AABBCC", "Icon project"),
                state,
                sessionInputs(document, state),
                "icon-pin",
            ),
        ).request
    }

    private fun plan(
        request: AcceptanceRequest,
        latest: StudioDocument,
    ): AcceptancePlanResult =
        StudioAcceptancePlanner.plan(request, latest, sessionInputs(latest, state(latest)).context)
}
