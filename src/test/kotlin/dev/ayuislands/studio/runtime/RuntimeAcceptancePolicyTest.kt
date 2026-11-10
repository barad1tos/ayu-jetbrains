package dev.ayuislands.studio.runtime

import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationCommand
import dev.ayuislands.rotation.RotationEvent
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RotationLoadOutcome
import dev.ayuislands.rotation.RotationOutput
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationRuntimeMachine
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationSchedule
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.rotation.RuntimeAvailability
import dev.ayuislands.rotation.RuntimeInputs
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.ProInitialization
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioCatalogPolicy
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioTargets
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.sessionInputs
import dev.ayuislands.studio.sessionRecord
import dev.ayuislands.studio.sessionState
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuntimeAcceptancePolicyTest {
    private val operationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val clock = ClockSample(TimeUnit.HOURS.toMillis(1), TimeUnit.HOURS.toNanos(1))
    private val globalFocus = FocusSnapshot(StudioTarget.Global, 1)

    @Test
    fun `catalog acceptance leaves the active rotation checkpoint exact`() {
        val document = document()
        val record = sessionRecord(document)
        val staged =
            assertIs<CatalogStage.Staged>(
                StudioCatalogPolicy.stage(
                    document,
                    record.work,
                    CatalogCommand.Create(record.work.selected, ProfileId("saved"), "Saved"),
                ),
            )
        val request = request(document, record.copy(work = staged.work))

        val result =
            assertIs<RuntimePreparationPlan.Ready>(
                RuntimeAcceptancePolicy.plan(request, snapshot(document), globalFocus, clock),
            )

        assertEquals(request.plan, result.plan)
        assertEquals(document.runtime.rotation, result.plan.candidate.runtime.rotation)
        assertNull(result.plan.rotationStep)
    }

    @Test
    fun `first Pro appearance initialization rebinds rotation without stepping or resetting its appointment`() {
        val settings = freshSettingsState().apply { accentRotationEnabled = true }
        val migrated =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(StudioFixtures.capture(settings, fresh = true)),
            ).document
        val current = snapshot(migrated)
        val captured = migrated.copy(runtime = migrated.runtime.copy(rotation = current.checkpoint))
        val state = sessionState(captured)
        val acceptance =
            AcceptanceRequest(
                requireNotNull(state.record),
                "initial-pro",
                1,
                AcceptanceOrigin.SETTINGS,
                initialization = ProInitialization(captured, StudioFixtures.fields(freshSettingsState())),
            )
        val planned =
            assertIs<AcceptancePlanResult.Ready>(
                StudioAcceptancePlanner.plan(acceptance, captured, sessionInputs(captured, state).context),
            )
        val accepted = assertIs<AcceptancePlan.Commit>(planned.plan)
        val prepared =
            assertIs<RuntimePreparationPlan.Ready>(
                RuntimeAcceptancePolicy.plan(
                    RuntimeAcceptanceRequest(operationId, acceptance, accepted),
                    current,
                    globalFocus,
                    clock,
                ),
            ).plan
        val owner = RuntimeOwner(StudioTarget.Global, accepted.candidate.currents.global.revision)
        assertEquals(
            current.checkpoint.copy(
                owner = owner,
                output = current.checkpoint.output?.copy(owner = owner),
                retryCandidate = current.checkpoint.retryCandidate?.copy(owner = owner),
            ),
            prepared.candidate.runtime.rotation,
        )
        assertNull(prepared.rotationStep)
        assertEquals(captured.currents.global.appearance.accent, prepared.candidate.currents.global.appearance.accent)
    }

    @Test
    fun `first Pro initialization cannot commit shared defaults after its focus is lost`() {
        val settings = freshSettingsState().apply { accentRotationEnabled = true }
        val migrated =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(StudioFixtures.capture(settings, fresh = true)),
            ).document
        val current = snapshot(migrated)
        val captured = migrated.copy(runtime = migrated.runtime.copy(rotation = current.checkpoint))
        val state = sessionState(captured)
        val acceptance =
            AcceptanceRequest(
                requireNotNull(state.record),
                "initial-pro",
                1,
                AcceptanceOrigin.SETTINGS,
                initialization = ProInitialization(captured, StudioFixtures.fields(freshSettingsState())),
            )
        val commit =
            assertIs<AcceptancePlan.Commit>(
                assertIs<AcceptancePlanResult.Ready>(
                    StudioAcceptancePlanner.plan(acceptance, captured, sessionInputs(captured, state).context),
                ).plan,
            )
        val otherFocus = FocusSnapshot(StudioTarget.Project(StudioProjectKey("/work/other")), 2)

        val blocked =
            assertIs<RuntimePreparationPlan.Blocked>(
                RuntimeAcceptancePolicy.plan(
                    RuntimeAcceptanceRequest(operationId, acceptance, commit),
                    current,
                    otherFocus,
                    clock,
                ),
            )

        assertEquals("activate-target", blocked.failure.code)
    }

    @Test
    fun `local unrelated acceptance rebinds output and retry ownership without changing the appointment`() {
        val document = document()
        val request = request(document, editGlow(sessionRecord(document)))
        val current = snapshot(document)
        val result =
            assertIs<RuntimePreparationPlan.Ready>(RuntimeAcceptancePolicy.plan(request, current, globalFocus, clock))
        val owner = RuntimeOwner(StudioTarget.Global, request.plan.candidate.currents.global.revision)
        val expected =
            current.checkpoint.copy(
                owner = owner,
                output = current.checkpoint.output?.copy(owner = owner),
                retryCandidate = current.checkpoint.retryCandidate?.copy(owner = owner),
            )

        assertEquals(expected, result.plan.candidate.runtime.rotation)
        assertEquals(request.plan.candidate.copy(runtime = result.plan.candidate.runtime), result.plan.candidate)
        assertNull(result.plan.rotationStep)
    }

    @Test
    fun `continuous profile activation keeps the old deadline and discards outgoing generated color`() {
        val document = document()
        val record = profileRecord(document)
        val request = request(document, record)
        val current = snapshot(document)

        val result =
            assertIs<RuntimePreparationPlan.Ready>(RuntimeAcceptancePolicy.plan(request, current, globalFocus, clock))
        val checkpoint = assertNotNull(result.plan.candidate.runtime.rotation)

        assertEquals(current.checkpoint.schedule, checkpoint.schedule)
        assertEquals(current.checkpoint.sequenceCursorRaw, checkpoint.sequenceCursorRaw)
        assertEquals(current.checkpoint.successfulStepSerial, checkpoint.successfulStepSerial)
        assertEquals(
            RuntimeOwner(StudioTarget.Global, request.plan.candidate.currents.global.revision),
            checkpoint.owner,
        )
        assertNull(checkpoint.output)
        assertNull(checkpoint.retryCandidate)
        assertNull(result.plan.rotationStep)
        assertEquals(request.plan.candidate.currents, result.plan.candidate.currents)
    }

    @Test
    fun `inactive captured appearance accepts without changing another focused owner`() {
        val project = StudioTarget.Project(StudioProjectKey("focused"))
        val initial = document()
        val document =
            initial.copy(
                currents =
                    initial.currents.copy(
                        projects =
                            StudioList(
                                listOf(StudioProjectRecord(project.key, "Focused", initial.currents.global)),
                            ),
                    ),
            )
        val current = snapshot(document, project)
        val stored = document.copy(runtime = document.runtime.copy(rotation = current.checkpoint))
        val request = request(stored, profileRecord(stored), TargetPresence.INACTIVE)

        val result =
            assertIs<RuntimePreparationPlan.Ready>(
                RuntimeAcceptancePolicy.plan(request, current, FocusSnapshot(project, 2), clock),
            )

        assertEquals(request.plan, result.plan)
        assertEquals(current.checkpoint, result.plan.candidate.runtime.rotation)
        assertNull(result.plan.rotationStep)
    }

    @Test
    fun `focus change blocks a captured acceptance containing shared policy changes`() {
        val document = document()
        val edited = editGlow(sessionRecord(document))
        val record =
            edited.copy(
                work =
                    edited.work.copy(
                        shared =
                            StudioList(
                                listOf(
                                    SharedIntent.SystemPolicy(
                                        document.shared.systemAccentPolicy,
                                        SystemAccentPolicy.FORCE_ON,
                                    ),
                                ),
                            ),
                    ),
            )
        val request = request(document, record)
        val focus = FocusSnapshot(StudioTarget.Project(StudioProjectKey("other")), 2)

        val blocked =
            assertIs<RuntimePreparationPlan.Blocked>(
                RuntimeAcceptancePolicy.plan(request, snapshot(document), focus, clock),
            )

        assertEquals("activate-target", blocked.failure.code)
        assertEquals(document.runtime.rotation, request.plan.candidate.runtime.rotation)
    }

    @Test
    fun `eligible enable prepares one preset step without advancing accepted history`() {
        val document = document(enabled = false)
        val record =
            edit(
                sessionRecord(document),
                "accentRotationEnabled",
                RawValue.BooleanValue(true),
                RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
            )
        val request = request(document, record)
        val current = snapshot(document)

        val result =
            assertIs<RuntimePreparationPlan.Ready>(RuntimeAcceptancePolicy.plan(request, current, globalFocus, clock))
        val step = assertNotNull(result.plan.rotationStep)
        val checkpoint = assertNotNull(result.plan.candidate.runtime.rotation)
        val next = (current.checkpoint.sequenceCursorRaw + 1) % AYU_ACCENT_PRESETS.size

        assertEquals(operationId, step.operationId)
        assertEquals(next, step.nextCursor)
        assertEquals(AYU_ACCENT_PRESETS[next].hex, step.hex)
        assertEquals(checkpoint.owner, step.owner)
        assertEquals(current.checkpoint.successfulStepSerial, checkpoint.successfulStepSerial)
        assertEquals(current.checkpoint.sequenceCursorRaw, checkpoint.sequenceCursorRaw)
        assertEquals(current.checkpoint.lastSuccessfulStepEpochMillis, checkpoint.lastSuccessfulStepEpochMillis)
        assertNull(checkpoint.retryCandidate)
        assertEquals(request.plan.candidate.currents, result.plan.candidate.currents)
    }

    @Test
    fun `local random configuration requests a color before returning a frozen prepared candidate`() {
        val document = document()
        val record =
            edit(
                sessionRecord(document),
                "accentRotationMode",
                RawValue.Text("RANDOM"),
                RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT,
            )
        val request = request(document, record)
        val current = snapshot(document)

        val pending =
            assertIs<RuntimePreparationPlan.RandomRequired>(
                RuntimeAcceptancePolicy.plan(request, current, globalFocus, clock),
            )
        assertEquals(operationId, pending.request.operationId)
        assertEquals(AyuVariant.MIRAGE, pending.request.variant)
        val result =
            assertIs<RuntimePreparationPlan.Ready>(
                RuntimeAcceptancePolicy.plan(request, current, globalFocus, clock, randomHex = "#123456"),
            )

        assertEquals("#123456", assertNotNull(result.plan.rotationStep).hex)
        assertEquals(current.checkpoint.sequenceCursorRaw, result.plan.rotationStep.nextCursor)
        assertEquals(result, RuntimeAcceptancePolicy.plan(request, current, globalFocus, clock, randomHex = "#123456"))
        assertEquals(request.plan.candidate.currents, result.plan.candidate.currents)
    }

    @Test
    fun `effective Follow masks an explicit enable without changing raw rotation choices`() {
        val initial = document(enabled = false)
        val document = initial.copy(shared = initial.shared.copy(systemAccentPolicy = SystemAccentPolicy.FORCE_ON))
        val record =
            edit(
                sessionRecord(document),
                "accentRotationEnabled",
                RawValue.BooleanValue(true),
                RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
            )
        val request = request(document, record)
        val current =
            snapshot(document).let {
                it.copy(
                    inputs =
                        it.inputs.copy(availability = it.inputs.availability.copy(effectiveFollowSystem = true)),
                )
            }

        val result =
            assertIs<RuntimePreparationPlan.Ready>(RuntimeAcceptancePolicy.plan(request, current, globalFocus, clock))
        val checkpoint = assertNotNull(result.plan.candidate.runtime.rotation)

        assertNull(result.plan.rotationStep)
        assertEquals(RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM), checkpoint.resume)
        assertEquals(current.checkpoint.sequenceCursorRaw, checkpoint.sequenceCursorRaw)
        assertEquals(current.checkpoint.successfulStepSerial, checkpoint.successfulStepSerial)
        assertEquals(request.plan.candidate.currents, result.plan.candidate.currents)
        assertEquals(request.plan.candidate.shared, result.plan.candidate.shared)
    }

    @Test
    fun `explicit enable without Pro persists deferred intent instead of rejecting the user Apply`() {
        val document = document(enabled = false)
        val record =
            edit(
                sessionRecord(document),
                "accentRotationEnabled",
                RawValue.BooleanValue(true),
                RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
            )
        val request = request(document, record)
        val observed = snapshot(document)
        val current =
            observed.copy(
                inputs =
                    observed.inputs.copy(availability = observed.inputs.availability.copy(lastDefinitivePro = false)),
            )

        val prepared =
            assertIs<RuntimePreparationPlan.Ready>(RuntimeAcceptancePolicy.plan(request, current, globalFocus, clock))
        val checkpoint = assertNotNull(prepared.plan.candidate.runtime.rotation)

        assertNull(prepared.plan.rotationStep)
        assertEquals(RotationResume.ImmediateEnable, checkpoint.resume)
        assertEquals(current.checkpoint.sequenceCursorRaw, checkpoint.sequenceCursorRaw)
        assertEquals(current.checkpoint.successfulStepSerial, checkpoint.successfulStepSerial)
        assertEquals(request.plan.candidate.currents, prepared.plan.candidate.currents)
        val restored =
            assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(prepared.plan.candidate)))
        assertEquals(prepared.plan.candidate, restored.document)
    }

    @Test
    fun `configuration without Pro defers its step while profile activation keeps handoff precedence`() {
        val document = document()
        val changed =
            edit(
                sessionRecord(document),
                "accentRotationIntervalHours",
                RawValue.Integer(1),
                RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT,
            )
        val observed = snapshot(document)
        val current =
            observed.copy(
                inputs =
                    observed.inputs.copy(availability = observed.inputs.availability.copy(lastDefinitivePro = false)),
            )
        val configuration =
            assertIs<RuntimePreparationPlan.Ready>(
                RuntimeAcceptancePolicy.plan(request(document, changed), current, globalFocus, clock),
            )
        val profile =
            assertIs<RuntimePreparationPlan.Ready>(
                RuntimeAcceptancePolicy.plan(request(document, profileRecord(document)), current, globalFocus, clock),
            )

        assertNull(configuration.plan.rotationStep)
        assertEquals(
            RotationResume.ImmediateConfiguration,
            configuration.plan.candidate.runtime.rotation
                ?.resume,
        )
        assertNull(profile.plan.rotationStep)
        assertEquals(
            RotationResume.FullInterval(FullIntervalReason.INTERRUPTED_HANDOFF),
            profile.plan.candidate.runtime.rotation
                ?.resume,
        )
        val restored =
            assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(configuration.plan.candidate)))
        assertEquals(configuration.plan.candidate, restored.document)
    }

    @Test
    fun `accepted Follow off starts the full interval only when Pro is already available`() {
        val initial = document()
        val document = initial.copy(shared = initial.shared.copy(systemAccentPolicy = SystemAccentPolicy.FORCE_ON))
        val record = sessionRecord(document)
        val intent =
            record.copy(
                work =
                    record.work.copy(
                        shared =
                            StudioList(
                                listOf(
                                    SharedIntent.SystemPolicy(
                                        SystemAccentPolicy.FORCE_ON,
                                        SystemAccentPolicy.FORCE_OFF,
                                    ),
                                ),
                            ),
                    ),
            )
        val request = request(document, intent)
        val observed = snapshot(document)
        val paused =
            observed.copy(
                checkpoint =
                    observed.checkpoint.copy(resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM)),
                inputs =
                    observed.inputs.copy(
                        availability = observed.inputs.availability.copy(effectiveFollowSystem = true),
                    ),
            )
        val acceptedAt = ClockSample(TimeUnit.HOURS.toMillis(2), TimeUnit.HOURS.toNanos(2))
        val allowed =
            assertIs<RuntimePreparationPlan.Ready>(
                RuntimeAcceptancePolicy.plan(request, paused, globalFocus, acceptedAt),
            )
        val blocked =
            paused.copy(
                inputs = paused.inputs.copy(availability = paused.inputs.availability.copy(lastDefinitivePro = false)),
            )
        val waiting =
            assertIs<RuntimePreparationPlan.Ready>(
                RuntimeAcceptancePolicy.plan(request, blocked, globalFocus, acceptedAt),
            )
        val expectedSchedule =
            RotationSchedule(acceptedAt.epochMillis, TimeUnit.HOURS.toMillis(8), TimeUnit.HOURS.toMillis(6), 6)

        assertNull(allowed.plan.rotationStep)
        assertEquals(
            expectedSchedule,
            allowed.plan.candidate.runtime.rotation
                ?.schedule,
        )
        assertEquals(
            RotationResume.KeepDeadline,
            allowed.plan.candidate.runtime.rotation
                ?.resume,
        )
        assertNull(waiting.plan.rotationStep)
        assertEquals(
            paused.checkpoint.schedule,
            waiting.plan.candidate.runtime.rotation
                ?.schedule,
        )
        assertEquals(
            paused.checkpoint.resume,
            waiting.plan.candidate.runtime.rotation
                ?.resume,
        )
    }

    @Test
    fun `persisted deferred enable restores paused and prepares one step when Pro returns`() {
        val document = document(enabled = false)
        val record =
            edit(
                sessionRecord(document),
                "accentRotationEnabled",
                RawValue.BooleanValue(true),
                RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
            )
        val observed = snapshot(document)
        val current =
            observed.copy(
                inputs =
                    observed.inputs.copy(availability = observed.inputs.availability.copy(lastDefinitivePro = false)),
            )
        val prepared =
            assertIs<RuntimePreparationPlan.Ready>(
                RuntimeAcceptancePolicy.plan(request(document, record), current, globalFocus, clock),
            )
        val candidate =
            assertIs<DecodeResult.Ready>(
                StudioStateCodec.decode(StudioStateCodec.encode(prepared.plan.candidate)),
            ).document
        val checkpoint = assertNotNull(candidate.runtime.rotation)
        val accepted =
            current.copy(
                checkpoint = checkpoint,
                inputs =
                    current.inputs.copy(owner = checkpoint.owner, appearance = candidate.currents.global.appearance),
            )
        val loadId = UUID.fromString("40000000-0000-0000-0000-000000000001")
        val loading =
            RotationRuntimeMachine.transition(
                RotationRuntimeState.Loading(),
                RotationEvent.Initialize(loadId),
                clock,
            )
        val loaded =
            RotationRuntimeMachine.transition(
                loading.state,
                RotationEvent.Loaded(loadId, RotationLoadOutcome.Ready(accepted)),
                clock,
            )
        assertIs<RotationRuntimeState.Paused>(loaded.state)
        assertEquals(0, loaded.commands.size)
        val restored =
            RotationRuntimeMachine.transition(
                loaded.state,
                RotationEvent.AvailabilityObserved(
                    1,
                    accepted.inputs.availability.copy(lastDefinitivePro = true),
                    operationId,
                ),
                clock,
            )

        val step = assertIs<RotationCommand.PrepareStep>(restored.commands.single())
        val next = (checkpoint.sequenceCursorRaw + 1) % AYU_ACCENT_PRESETS.size
        assertEquals(AYU_ACCENT_PRESETS[next].hex, step.candidate.hex)
        assertEquals(operationId, step.candidate.operationId)
        assertEquals(
            checkpoint.successfulStepSerial,
            assertIs<RotationRuntimeState.Loaded>(restored.state).snapshot.checkpoint.successfulStepSerial,
        )
        val duplicate =
            RotationRuntimeMachine.transition(
                restored.state,
                RotationEvent.AvailabilityObserved(
                    1,
                    accepted.inputs.availability.copy(lastDefinitivePro = true),
                    operationId,
                ),
                clock,
            )
        assertEquals(0, duplicate.commands.size)
    }

    private fun document(enabled: Boolean = true): StudioDocument {
        val initial = sessionDocument()
        val current = initial.currents.global
        val field = StudioAppearanceFields.field(current.appearance, address("accentRotationEnabled").field)
        val appearance =
            StudioAppearanceFields.replace(
                current.appearance,
                field.copy(value = RawValue.BooleanValue(enabled)),
            )
        val accepted = initial.copy(currents = initial.currents.copy(global = current.copy(appearance = appearance)))
        return accepted.copy(runtime = accepted.runtime.copy(rotation = snapshot(accepted).checkpoint))
    }

    private fun snapshot(
        document: StudioDocument,
        target: StudioTarget = StudioTarget.Global,
    ): RotationSnapshot {
        val accepted = requireNotNull(StudioTargets.current(document, target))
        val owner = RuntimeOwner(target, accepted.revision)
        val checkpoint =
            RotationRuntimeCheckpoint(
                owner,
                3,
                4,
                lastSuccessfulStepEpochMillis = 0,
                output = RotationOutput(owner, AyuVariant.MIRAGE, "#5CCFE6"),
                schedule = RotationSchedule(0, TimeUnit.HOURS.toMillis(6), TimeUnit.HOURS.toMillis(6), 5),
                retryCandidate =
                    RotationCandidate(
                        UUID.fromString("30000000-0000-0000-0000-000000000001"),
                        owner,
                        RotationIntent(enabled = true, modeRaw = "PRESET", intervalHoursRaw = 6),
                        AccentRotationMode.PRESET,
                        AyuVariant.MIRAGE,
                        "#ABCDEF",
                        4,
                    ),
            )
        return RotationSnapshot(
            checkpoint,
            RuntimeInputs(
                owner,
                accepted.appearance,
                RuntimeAvailability(
                    lastDefinitivePro = true,
                    effectiveFollowSystem = false,
                    variant = AyuVariant.MIRAGE,
                    targetValid = true,
                    recoveryPending = false,
                ),
            ),
        )
    }

    private fun request(
        document: StudioDocument,
        record: StudioSessionRecord,
        presence: TargetPresence = TargetPresence.ACTIVE,
    ): RuntimeAcceptanceRequest {
        val stored = document.copy(sessions = StudioList(listOf(record)))
        val acceptance = AcceptanceRequest(record, "raw-store-transaction", 1, AcceptanceOrigin.SETTINGS)
        val observed = sessionInputs(stored, sessionState(stored).copy(record = record)).context
        val selected = record.work.drafts.single { it.id == record.work.selected }
        val context =
            observed.copy(
                target = observed.target.copy(presence = presence),
                font = observed.font.copy(requested = selected.appearance.font),
            )
        val planned = assertIs<AcceptancePlanResult.Ready>(StudioAcceptancePlanner.plan(acceptance, stored, context))
        return RuntimeAcceptanceRequest(operationId, acceptance, assertIs<AcceptancePlan.Commit>(planned.plan))
    }

    private fun profileRecord(document: StudioDocument): StudioSessionRecord {
        val original = sessionRecord(document)
        val work =
            StudioDraftPolicy.select(
                original.work,
                document.catalog.first(),
                DraftId("profile"),
                document.currents.global,
            )
        val selected = original.copy(work = work)
        val enabled =
            edit(
                selected,
                "accentRotationEnabled",
                RawValue.BooleanValue(true),
                RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
            )
        return edit(
            enabled,
            "accentRotationIntervalHours",
            RawValue.Integer(1),
            RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT,
        )
    }

    private fun editGlow(record: StudioSessionRecord): StudioSessionRecord {
        val draft = record.work.drafts.single { it.id == record.work.selected }
        val value =
            draft.appearance.glow.records
                .single { it.key.name == "glowEnabled" }
                .value as RawValue.BooleanValue
        return edit(record, "glowEnabled", RawValue.BooleanValue(!value.value))
    }

    private fun edit(
        record: StudioSessionRecord,
        field: String,
        value: RawValue,
        origin: RotationAcceptanceOrigin? = null,
    ): StudioSessionRecord {
        val draft = record.work.drafts.single { it.id == record.work.selected }
        val changed = StudioDraftPolicy.edit(draft, address(field), FieldValue.Present(value), origin)
        return record.copy(
            generation = record.generation + 1,
            work =
                record.work.copy(
                    drafts =
                        StudioList(
                            record.work.drafts.map {
                                if (it.id ==
                                    draft.id
                                ) {
                                    changed
                                } else {
                                    it
                                }
                            },
                        ),
                ),
        )
    }

    private fun address(field: String): FieldAddress = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, field))
}
