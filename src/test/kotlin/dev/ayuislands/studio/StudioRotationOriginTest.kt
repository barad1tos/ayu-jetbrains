package dev.ayuislands.studio

import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StudioRotationOriginTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `explicit enable origin survives autosave reopen and unrelated edits for either entrypoint`() {
        lateinit var expected: StudioSessionRecord
        StudioApplyFixture(directory).use { fixture ->
            val enabled =
                edit(
                    fixture.initial,
                    sessionState(fixture.initial),
                    "accentRotationEnabled",
                    RawValue.BooleanValue(true),
                    RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
                )
            val changed = edit(fixture.initial, enabled, "glowEnabled", RawValue.BooleanValue(true))
            val previewed = reduce(fixture.initial, changed, SessionIntent.Preview(PreviewMode.CURRENT))
            expected = requireNotNull(previewed.record)
            assertIs<DraftWriteResult.Saved>(StudioDraftStore(fixture.store).save(expected, "rotation-draft"))
            assertEquals(fixture.initial.currents, fixture.document().currents)
            assertEquals(fixture.initial.runtime, fixture.document().runtime)
        }
        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val document = assertIs<StoreLoad.Ready>(reopened.load()).document
            val record = document.sessions.single()
            assertEquals(expected, record)
            for (entrypoint in AcceptanceOrigin.entries) {
                val request = AcceptanceRequest(record, "origin-${entrypoint.name}", 1, entrypoint)
                assertEquals(entrypoint, request.origin)
                assertEquals(
                    RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
                    selected(request.session).tracking.rotationOrigin,
                )
            }
        }
    }

    @Test
    fun `profile activation provenance survives later rotation edits and draft persistence`() {
        lateinit var expected: StudioSessionRecord
        StudioApplyFixture(directory).use { fixture ->
            val profileSelected =
                reduce(
                    fixture.initial,
                    sessionState(fixture.initial),
                    SessionIntent.Browse(
                        fixture.initial.catalog
                            .first()
                            .id,
                        DraftId("selected-profile"),
                    ),
                )
            val edited =
                edit(
                    fixture.initial,
                    profileSelected,
                    "accentRotationIntervalHours",
                    RawValue.Integer(1),
                    RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT,
                )
            expected = requireNotNull(edited.record)
            assertEquals(RotationAcceptanceOrigin.PROFILE_ACTIVATION, selected(expected).tracking.rotationOrigin)
            assertIs<DraftWriteResult.Saved>(StudioDraftStore(fixture.store).save(expected, "profile-draft"))
        }
        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val document = assertIs<StoreLoad.Ready>(reopened.load()).document
            assertEquals(expected, document.sessions.single())
            assertEquals(
                RotationAcceptanceOrigin.PROFILE_ACTIVATION,
                selected(document.sessions.single()).tracking.rotationOrigin,
            )
        }
    }

    @Test
    fun `explicit enable remains the command origin after a local mode edit`() {
        val document = sessionDocument()
        val enabled =
            edit(
                document,
                sessionState(document),
                "accentRotationEnabled",
                RawValue.BooleanValue(true),
                RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
            )
        val configured =
            edit(
                document,
                enabled,
                "accentRotationMode",
                RawValue.Text("RANDOM"),
                RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT,
            )
        val draft = selected(requireNotNull(configured.record))

        assertEquals(RawValue.Text("RANDOM"), draft.appearance.accent.value("accentRotationMode"))
        assertEquals(RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE, draft.tracking.rotationOrigin)
    }

    @Test
    fun `selecting an existing discarded profile records a new profile activation`() {
        val document = sessionDocument()
        val profile = document.catalog.first()
        val browsed =
            reduce(
                document,
                sessionState(document),
                SessionIntent.Browse(profile.id, DraftId("profile-draft")),
            )
        val profileDraft = selected(requireNotNull(browsed.record))
        val discarded = reduce(document, browsed, SessionIntent.Discard(profileDraft.id))
        assertNull(selected(requireNotNull(discarded.record)).tracking.rotationOrigin)

        val reselected = reduce(document, discarded, SessionIntent.Browse(profile.id, DraftId("unused-new-id")))
        val restored = selected(requireNotNull(reselected.record))

        assertEquals(profileDraft.id, restored.id)
        assertEquals(profile.appearance, restored.appearance)
        assertEquals(RotationAcceptanceOrigin.PROFILE_ACTIVATION, restored.tracking.rotationOrigin)
    }

    @Test
    fun `explicit discard clears the recorded command and persists the unchanged accepted baseline`() {
        StudioApplyFixture(directory).use { fixture ->
            val enabled =
                edit(
                    fixture.initial,
                    sessionState(fixture.initial),
                    "accentRotationEnabled",
                    RawValue.BooleanValue(true),
                    RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
                )
            val draft = selected(requireNotNull(enabled.record))
            assertEquals(RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE, draft.tracking.rotationOrigin)
            val discarded = reduce(fixture.initial, enabled, SessionIntent.Discard(draft.id))
            val record = requireNotNull(discarded.record)
            assertNull(selected(record).tracking.rotationOrigin)
            assertEquals(fixture.initial.currents.global.appearance, selected(record).appearance)
            assertIs<DraftWriteResult.Saved>(StudioDraftStore(fixture.store).save(record, "discard-command"))
        }
        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val record = assertIs<StoreLoad.Ready>(reopened.load()).document.sessions.single()
            assertNull(selected(record).tracking.rotationOrigin)
        }
    }

    @Test
    fun `rejected Apply retains origin and acceptance clears it in the stored session`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                val record = requireNotNull(fixture.controller.state.record)
                fixture.controller.dispatch(
                    SessionEvent.Intent(
                        SessionIntent.EditField(
                            selected(record).id,
                            address("accentRotationEnabled"),
                            FieldValue.Present(RawValue.BooleanValue(true)),
                            rotationOrigin = RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
                        ),
                    ),
                )
                fixture.native.canAcquire = false
                assertIs<AcceptanceResult.NotAccepted>(fixture.apply("rejected-enable"))
                assertEquals(
                    RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
                    selected(requireNotNull(fixture.controller.state.record)).tracking.rotationOrigin,
                )
                assertEquals(
                    RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
                    selected(
                        fixture.native
                            .document()
                            .sessions
                            .single(),
                    ).tracking.rotationOrigin,
                )
                fixture.native.canAcquire = true
                assertIs<AcceptanceResult.Accepted>(fixture.apply("accepted-enable"))
                assertNull(selected(requireNotNull(fixture.controller.state.record)).tracking.rotationOrigin)
                assertNull(
                    selected(
                        fixture.native
                            .document()
                            .sessions
                            .single(),
                    ).tracking.rotationOrigin,
                )
                fixture.awaitWrites()
            }
            StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
                assertNull(
                    selected(
                        assertIs<StoreLoad.Ready>(reopened.load()).document.sessions.single(),
                    ).tracking.rotationOrigin,
                )
            }
        }

    private fun edit(
        document: StudioDocument,
        state: StudioSessionState,
        field: String,
        value: RawValue,
        origin: RotationAcceptanceOrigin? = null,
    ): StudioSessionState =
        reduce(
            document,
            state,
            SessionIntent.EditField(
                selected(requireNotNull(state.record)).id,
                address(field),
                FieldValue.Present(value),
                rotationOrigin = origin,
            ),
        )

    private fun reduce(
        document: StudioDocument,
        state: StudioSessionState,
        intent: SessionIntent,
    ): StudioSessionState =
        StudioSessionReducer.reduce(state, SessionEvent.Intent(intent), sessionInputs(document, state)).state

    private fun address(field: String): FieldAddress = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, field))

    private fun selected(record: StudioSessionRecord): DraftRecord =
        record.work.drafts.single {
            it.id ==
                record.work.selected
        }
}
