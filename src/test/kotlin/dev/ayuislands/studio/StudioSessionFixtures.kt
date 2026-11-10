package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.CapabilitySnapshot
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.FontObservation
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIdentity
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioSessionRecords
import kotlin.test.assertIs

internal fun sessionDocument(): StudioDocument =
    assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture())).document

internal fun sessionRecord(document: StudioDocument): StudioSessionRecord =
    requireNotNull(
        StudioSessionRecords.current(document, StudioTarget.Global, "session", DraftId("current-draft")),
    )

internal fun sessionState(document: StudioDocument): StudioSessionState {
    val record = sessionRecord(document)
    return StudioSessionState(SessionIdentity(record.id, record.target, 1), record, SessionPhase.Editing)
}

internal fun sessionInputs(
    document: StudioDocument,
    state: StudioSessionState,
): SessionInputs =
    SessionInputs(
        document,
        AcceptanceContext(
            AuthorityStatus.READY,
            TargetObservation(state.identity.target, 1, TargetPresence.ACTIVE),
            CapabilitySnapshot(1, StudioList(emptyList())),
            FontObservation(
                requireNotNull(state.record)
                    .work.drafts
                    .first()
                    .appearance.font,
                FontAvailability.AVAILABLE,
            ),
        ),
    )

internal fun editedSession(document: StudioDocument): StudioSessionState {
    val state = sessionState(document)
    val record = requireNotNull(state.record)
    val draft = record.work.drafts.single()
    val key = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
    val value = draft.appearance.glow.value("glowEnabled") as RawValue.BooleanValue
    val changed = StudioDraftPolicy.edit(draft, key, FieldValue.Present(RawValue.BooleanValue(!value.value)))
    return state.copy(
        record = record.copy(generation = 1, work = record.work.copy(drafts = StudioList(listOf(changed)))),
        journal = SessionJournalStatus.Pending(1),
    )
}
