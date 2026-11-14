package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.ApplyCandidate
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyOrigin
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.StudioApplyCandidate
import dev.ayuislands.studio.session.StudioCatalogPolicy
import dev.ayuislands.studio.session.StudioDraftPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioApplyCandidateTest {
    @Test
    fun `unavailable authority blocks before draft review`() {
        val document = catalogTestDocument()
        val session = withHiddenEdit(editedJournalRecord(document))
        val context = context(session, AuthorityStatus.RECOVERY)

        assertEquals(
            ApplyCandidate.Blocked(AcceptanceBlock.RECOVERY),
            StudioApplyCandidate.plan(ApplyRequest(session, 1), document, context),
        )
    }

    @Test
    fun `saving a hidden draft adds a definition without changing Current`() {
        val document = catalogTestDocument()
        val session = withHiddenEdit(editedJournalRecord(document))
        val hidden = session.work.drafts.first()
        val request = ApplyRequest(session, 1)
        val context = context(session)

        val review = assertIs<ApplyCandidate.Review>(StudioApplyCandidate.plan(request, document, context))
        assertEquals(listOf<AcceptanceReview>(AcceptanceReview.HiddenDraft(hidden.id)), review.items)

        val staged =
            assertIs<CatalogStage.Staged>(
                StudioCatalogPolicy.stage(
                    document,
                    session.work,
                    CatalogCommand.Create(hidden.id, ProfileId("saved"), "Saved"),
                ),
            )
        val result =
            assertIs<ApplyCandidate.Ready>(
                StudioApplyCandidate.plan(request.copy(session = session.copy(work = staged.work)), document, context),
            )

        assertTrue(result.hasWork)
        assertEquals(document.currents, result.document.currents)
        assertEquals(document.shared, result.document.shared)
        assertEquals(
            hidden.appearance,
            result.document.catalog
                .last()
                .appearance,
        )
        assertEquals(document.header, result.document.header)
    }

    @Test
    fun `changed capture and selected field conflict are both reviewed in stable order`() {
        val document = catalogTestDocument()
        val session = withHiddenEdit(editedJournalRecord(document))
        val hidden = session.work.drafts.first()
        val staged =
            assertIs<CatalogStage.Staged>(
                StudioCatalogPolicy.stage(
                    document,
                    session.work,
                    CatalogCommand.Create(hidden.id, ProfileId("saved"), "Saved"),
                ),
            )
        val selected = staged.work.drafts.last()
        val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "mirageAccent"))
        val changed = StudioDraftPolicy.edit(hidden, address, FieldValue.Present(RawValue.Text("#FEDCBA")))
        val requested = StudioDraftPolicy.edit(selected, address, FieldValue.Present(RawValue.Text("#112233")))
        val external = StudioDraftPolicy.edit(selected, address, FieldValue.Present(RawValue.Text("#AABBCC")))
        val work = staged.work.copy(drafts = StudioList(listOf(changed, requested)))
        val latest =
            document.copy(
                currents =
                    document.currents.copy(
                        global = document.currents.global.copy(appearance = external.appearance),
                    ),
            )

        val review =
            assertIs<ApplyCandidate.Review>(
                StudioApplyCandidate.plan(ApplyRequest(session.copy(work = work), 1), latest, context(session)),
            )

        assertEquals(3, review.items.size)
        assertEquals(AcceptanceReview.HiddenDraft(hidden.id), review.items[0])
        assertIs<AcceptanceReview.Catalog>(review.items[1])
        assertEquals(address, assertIs<AcceptanceReview.Field>(review.items[2]).conflict.address)
    }

    @Test
    fun `Quick Switcher reviews registered dirty work but leaves an orphan for explicit Restore`() {
        val document = catalogTestDocument()
        val dirty = editedJournalRecord(document)
        val latest = document.copy(sessions = StudioList(listOf(dirty)))
        val popup = journalRecord(document).copy(id = "popup")
        val request = ApplyRequest(popup, 1, origin = ApplyOrigin.QUICK_SWITCHER)
        val orphanContext = context(popup)

        val review =
            assertIs<ApplyCandidate.Review>(
                StudioApplyCandidate.plan(request, latest, context(popup, liveSessions = listOf(dirty))),
            )
        assertEquals(listOf<AcceptanceReview>(AcceptanceReview.LiveSession(dirty.id)), review.items)

        val result = assertIs<ApplyCandidate.Ready>(StudioApplyCandidate.plan(request, latest, orphanContext))
        assertFalse(result.hasWork)
        assertEquals(latest, result.document)
    }

    @Test
    fun `selected edits preserve unrelated accepted values and staged work`() {
        val document = catalogTestDocument()
        val session = editedJournalRecord(document)
        val selected = session.work.drafts.single()
        val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "mirageAccent"))
        val external =
            StudioDraftPolicy.edit(
                journalRecord(document).work.drafts.single(),
                address,
                FieldValue.Present(RawValue.Text("#AABBCC")),
            )
        val latestSource = ProfileReference(ProfileId("custom"), 1)
        val latest =
            document.copy(
                currents =
                    document.currents.copy(
                        global = document.currents.global.copy(appearance = external.appearance, source = latestSource),
                    ),
            )

        val result =
            assertIs<ApplyCandidate.Ready>(
                StudioApplyCandidate.plan(ApplyRequest(session, 1), latest, context(session)),
            )
        assertTrue(result.hasWork)
        assertEquals(latest.currents.global.appearance.accent, result.document.currents.global.appearance.accent)
        assertEquals(latestSource, result.document.currents.global.source)
        assertEquals(selected.appearance.glow, result.document.currents.global.appearance.glow)
        assertEquals(latest.currents.global.revision + 1, result.document.currents.global.revision)
        assertEquals(document.catalog, result.document.catalog)
        assertEquals(
            session.work,
            result.document.sessions
                .single()
                .work,
        )
    }

    @Test
    fun `an equal accepted field value retains the whole current revision`() {
        val document = catalogTestDocument()
        val session = editedJournalRecord(document)
        val selected = session.work.drafts.single()
        val current = document.currents.global.copy(appearance = selected.appearance, revision = 7)
        val latest = document.copy(currents = document.currents.copy(global = current))

        val result =
            assertIs<ApplyCandidate.Ready>(
                StudioApplyCandidate.plan(ApplyRequest(session, 1), latest, context(session)),
            )

        assertTrue(result.hasWork)
        assertEquals(current, result.document.currents.global)
    }

    private fun withHiddenEdit(session: StudioSessionRecord): StudioSessionRecord {
        val dirty = session.work.drafts.single()
        val clean =
            dirty.copy(
                id = DraftId("clean"),
                appearance = dirty.tracking.initial,
                tracking = dirty.tracking.copy(touched = StudioList(emptyList())),
            )
        return session.copy(work = session.work.copy(drafts = StudioList(listOf(dirty, clean)), selected = clean.id))
    }

    private fun context(
        session: StudioSessionRecord,
        authority: AuthorityStatus = AuthorityStatus.READY,
        liveSessions: List<StudioSessionRecord> = emptyList(),
    ): ApplyContext =
        ApplyContext(
            AcceptanceObservation(authority, TargetObservation(session.target, 1, TargetPresence.ACTIVE)),
            StudioList(liveSessions),
        )
}
