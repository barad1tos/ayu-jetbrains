package dev.ayuislands.studio

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.ProInitialization
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioProInitializationTest {
    @Test
    fun `explicit first Pro initialization accepts effects and guards in one candidate`() {
        val document = document()
        val plan = commit(document, document)
        val accepted = plan.candidate
        assertEquals(listOf(EffectOwner.GLOW, EffectOwner.WORKSPACE), plan.effects)
        val glow = accepted.currents.global.appearance.glow
        assertEquals(RawValue.BooleanValue(true), glow.value("glowEnabled"))
        assertEquals(RawValue.Text("SHARP_NEON"), glow.value("glowStyle"))
        assertEquals(RawValue.Text("CUSTOM"), glow.value("glowPreset"))
        assertEquals(RawValue.Text("BREATHE"), glow.value("glowAnimation"))
        assertEquals(RawValue.Integer(100), glow.value("sharpNeonIntensity"))
        assertEquals(RawValue.Integer(2), glow.value("sharpNeonWidth"))
        assertEquals(
            document.currents.global.appearance.glow
                .value("glowShape"),
            glow.value("glowShape"),
        )
        assertEquals(
            document.currents.global.appearance.glow
                .value("waveformTraceLength"),
            glow.value("waveformTraceLength"),
        )
        assertEquals(RawValue.Text("AUTO_FIT"), accepted.shared.workspace.value("projectPanelWidthMode"))
        assertEquals(RawValue.BooleanValue(true), accepted.shared.workspace.value("hideProjectRootPath"))
        assertEquals(RawValue.BooleanValue(true), accepted.runtime.legacy.value("everBeenPro"))
        assertEquals(RawValue.BooleanValue(true), accepted.auxiliary.legacyMarkers.value("proDefaultsApplied"))
        assertEquals(RawValue.BooleanValue(true), accepted.auxiliary.legacyMarkers.value("workspaceDefaultsApplied"))
        assertEquals(document.currents.global.appearance.accent, accepted.currents.global.appearance.accent)
        assertEquals(document.currents.global.appearance.font, accepted.currents.global.appearance.font)
        assertEquals(document.currents.global.appearance.syntax, accepted.currents.global.appearance.syntax)
        assertEquals(document.currents.global.appearance.vcs, accepted.currents.global.appearance.vcs)
        assertEquals(document.catalog, accepted.catalog)
        assertEquals(document.auxiliary.resources, accepted.auxiliary.resources)
        assertEquals(document.auxiliary.ui, accepted.auxiliary.ui)
        assertEquals(document.header.revision + 1, accepted.header.revision)
        assertEquals(document.currents.global.revision + 1, accepted.currents.global.revision)
        assertTrue(plan.glowSelection.fields.isNotEmpty())
        assertTrue(plan.workspaceFields.isNotEmpty())
    }

    @Test
    fun `ordinary acceptance never initializes Pro preferences`() {
        val document = document()
        val state = sessionState(document)
        val request = AcceptanceRequest(requireNotNull(state.record), "ordinary", 1, AcceptanceOrigin.SETTINGS)
        val result = StudioAcceptancePlanner.plan(request, document, sessionInputs(document, state).context)
        assertEquals(AcceptancePlan.Unchanged, assertIs<AcceptancePlanResult.Ready>(result).plan)
    }

    @Test
    fun `initialization cannot persist shared defaults while its captured target is inactive`() {
        val document = document()
        val state = sessionState(document)
        val context = sessionInputs(document, state).context
        val request =
            AcceptanceRequest(
                requireNotNull(state.record),
                "inactive-pro",
                1,
                AcceptanceOrigin.SETTINGS,
                initialization = ProInitialization(document, StudioFixtures.fields(freshSettingsState())),
            )
        val result =
            StudioAcceptancePlanner.plan(
                request,
                document,
                context.copy(target = context.target.copy(presence = TargetPresence.INACTIVE)),
            )
        assertEquals(listOf(AcceptanceBlock.ACTIVATE_TARGET), assertIs<AcceptancePlanResult.Blocked>(result).reasons)
    }

    @Test
    fun `imported user preferences remain exact even when legacy guards are false`() {
        val settings =
            AyuIslandsState().apply {
                glowEnabled = false
                glowStyle = "GRADIENT"
                glowShape = "WAVEFORM"
                waveformTraceLength = 193
                accentRotationEnabled = true
                projectPanelWidthMode = "FIXED"
                projectPanelFixedWidth = 417
                hideProjectRootPath = false
            }
        val document = document(settings, fresh = false)
        val plan = commit(document, document)
        assertEquals(document.currents, plan.candidate.currents)
        assertEquals(document.shared, plan.candidate.shared)
        assertEquals(document.catalog, plan.candidate.catalog)
        assertTrue(plan.effects.isEmpty())
        assertEquals(
            RawValue.BooleanValue(true),
            plan.candidate.runtime.legacy
                .value("everBeenPro"),
        )
    }

    @Test
    fun `customized values before first Pro take precedence over fresh defaults`() {
        val initial = document()
        val appearance = initial.currents.global.appearance
        val document =
            initial.copy(
                currents =
                    initial.currents.copy(
                        global =
                            initial.currents.global.copy(
                                appearance =
                                    appearance.copy(
                                        glow = edit(appearance.glow, "glowStyle", RawValue.Text("GRADIENT")),
                                    ),
                                revision = 1,
                            ),
                    ),
                shared =
                    initial.shared.copy(
                        workspace = edit(initial.shared.workspace, "projectPanelWidthMode", RawValue.Text("FIXED")),
                    ),
            )
        val plan = commit(document, document)
        assertEquals(
            RawValue.Text("GRADIENT"),
            plan.candidate.currents.global.appearance.glow
                .value("glowStyle"),
        )
        assertEquals(
            RawValue.Text("FIXED"),
            plan.candidate.shared.workspace
                .value("projectPanelWidthMode"),
        )
        assertEquals(
            RawValue.BooleanValue(true),
            plan.candidate.runtime.legacy
                .value("everBeenPro"),
        )
    }

    @Test
    fun `a newer accepted target and Workspace choice cannot be overwritten by an older initialization`() {
        val captured = document()
        val appearance = captured.currents.global.appearance
        val latest =
            captured.copy(
                header = captured.header.copy(revision = 5, transactionId = "user-edit"),
                currents =
                    captured.currents.copy(
                        global =
                            captured.currents.global.copy(
                                appearance =
                                    appearance.copy(
                                        glow = edit(appearance.glow, "glowStyle", RawValue.Text("GRADIENT")),
                                    ),
                                revision = 1,
                            ),
                    ),
                shared =
                    captured.shared.copy(
                        workspace = edit(captured.shared.workspace, "projectPanelWidthMode", RawValue.Text("FIXED")),
                    ),
            )
        val plan = commit(captured, latest)
        assertEquals(latest.currents, plan.candidate.currents)
        assertEquals(
            RawValue.Text("FIXED"),
            plan.candidate.shared.workspace
                .value("projectPanelWidthMode"),
        )
        assertEquals(6, plan.candidate.header.revision)
        assertEquals(
            RawValue.BooleanValue(true),
            plan.candidate.runtime.legacy
                .value("everBeenPro"),
        )
    }

    @Test
    fun `named profile activation protects all of its Glow choices`() {
        val initial = document()
        val profile = initial.catalog.first()
        val document =
            initial.copy(
                currents =
                    initial.currents.copy(
                        global =
                            initial.currents.global.copy(
                                appearance = profile.appearance,
                                revision = 1,
                                source = ProfileReference(profile.id, 1),
                            ),
                    ),
            )
        val plan = commit(document, document)
        assertEquals(document.currents, plan.candidate.currents)
        assertEquals(document.catalog, plan.candidate.catalog)
        assertTrue(EffectOwner.GLOW !in plan.effects)
    }

    @Test
    fun `changed guard snapshot makes stale initialization inert`() {
        val captured = document()
        val latest =
            captured.copy(
                auxiliary =
                    captured.auxiliary.copy(
                        legacyMarkers =
                            edit(
                                captured.auxiliary.legacyMarkers,
                                "proDefaultsApplied",
                                RawValue.BooleanValue(true),
                            ),
                    ),
            )
        assertEquals(AcceptancePlan.Unchanged, ready(captured, latest))
    }

    @Test
    fun `re-purchase only repairs the Pro latch without replaying appearance defaults`() {
        val settings =
            freshSettingsState().apply {
                everBeenPro = true
                proDefaultsApplied = false
                workspaceDefaultsApplied = true
                glowEnabled = false
                glowShape = "WAVEFORM"
                glowStyle = "GRADIENT"
            }
        val document = document(settings)
        val plan = commit(document, document)
        assertEquals(document.currents, plan.candidate.currents)
        assertEquals(document.shared, plan.candidate.shared)
        assertTrue(plan.effects.isEmpty())
        assertEquals(
            RawValue.BooleanValue(true),
            plan.candidate.auxiliary.legacyMarkers
                .value("proDefaultsApplied"),
        )
        assertEquals(document.runtime, plan.candidate.runtime)
    }

    @Test
    fun `repeated successful initialization creates no additional generation`() {
        val document = document()
        val accepted = commit(document, document).candidate
        assertEquals(AcceptancePlan.Unchanged, ready(accepted, accepted))
    }

    private fun ready(
        captured: StudioDocument,
        latest: StudioDocument,
    ): AcceptancePlan {
        val state = sessionState(latest)
        val request =
            AcceptanceRequest(
                requireNotNull(state.record),
                "first-pro",
                1,
                AcceptanceOrigin.SETTINGS,
                initialization = ProInitialization(captured, StudioFixtures.fields(freshSettingsState())),
            )
        return assertIs<AcceptancePlanResult.Ready>(
            StudioAcceptancePlanner.plan(request, latest, sessionInputs(latest, state).context),
        ).plan
    }

    private fun commit(
        captured: StudioDocument,
        latest: StudioDocument,
    ): AcceptancePlan.Commit = assertIs<AcceptancePlan.Commit>(ready(captured, latest))

    private fun document(
        settings: AyuIslandsState = freshSettingsState(),
        fresh: Boolean = true,
    ): StudioDocument =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture(settings, fresh))).document

    private fun edit(
        fields: StudioFields,
        name: String,
        value: RawValue,
    ): StudioFields =
        fields.copy(
            records =
                StudioList(
                    fields.records.map { field ->
                        if (field.key.name ==
                            name
                        ) {
                            StudioFieldEdits.edit(field, FieldPart.Value, FieldValue.Present(value))
                        } else {
                            field
                        }
                    },
                ),
        )
}
