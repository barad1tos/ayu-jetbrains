package dev.ayuislands.studio

import dev.ayuislands.studio.model.CatalogApprovals
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogPlan
import dev.ayuislands.studio.model.CatalogReview
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.StudioCatalogPolicy
import dev.ayuislands.studio.session.StudioCatalogStaging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioCatalogPolicyTest {
    @Test
    fun `create captures all five domains without selecting or activating and permits duplicate names`() {
        val document = catalogTestDocument()
        val session = sessionRecord(document)
        val first =
            stage(document, session.work, CatalogCommand.Create(session.work.selected, ProfileId("new-1"), " Custom "))
        val second = stage(document, first, CatalogCommand.Create(session.work.selected, ProfileId("new-2"), "Custom"))
        assertEquals(session.work.selected, second.selected)
        assertEquals(session.work.drafts, second.drafts)
        val captures = second.catalog.filterIsInstance<CatalogIntent.Create>()
        assertEquals(listOf("Custom", "Custom"), captures.map { it.definition.name })
        assertTrue(
            captures.all {
                it.definition.appearance ==
                    session.work.drafts
                        .single()
                        .appearance
            },
        )
        val result =
            assertIs<CatalogPlan.Ready>(
                StudioCatalogPolicy.plan(document, session.copy(work = second)),
            ).document
        assertEquals(listOf("new-1", "new-2"), result.catalog.takeLast(2).map { it.id.value })
        assertEquals(document.currents, result.currents)
        assertEquals(document.runtime, result.runtime)
        assertEquals(document.shared, result.shared)
    }

    @Test
    fun `later draft edits require explicit retain or refresh of the immutable capture`() {
        val document = catalogTestDocument()
        val session = sessionRecord(document)
        val work =
            stage(document, session.work, CatalogCommand.Create(session.work.selected, ProfileId("new"), "Saved"))
        val changed = work.drafts.single().copy(appearance = document.catalog.first().appearance)
        val edited = session.copy(work = work.copy(drafts = StudioList(listOf(changed))))
        val review = assertIs<CatalogPlan.Review>(StudioCatalogPolicy.plan(document, edited))
        val difference = assertIs<CatalogReview.StaleCapture>(review.items.single()).difference
        val retained =
            assertIs<CatalogPlan.Ready>(
                StudioCatalogPolicy.plan(document, edited, CatalogApprovals(retained = StudioList(listOf(difference)))),
            ).document
        assertEquals(
            session.work.drafts
                .single()
                .appearance,
            retained.catalog.last().appearance,
        )
        assertEquals(document.currents, retained.currents)
        val refreshed = stage(document, edited.work, CatalogCommand.Update(changed.id, ProfileId("new")))
        val accepted =
            assertIs<CatalogPlan.Ready>(
                StudioCatalogPolicy.plan(document, edited.copy(work = refreshed)),
            ).document
        assertEquals(changed.appearance, accepted.catalog.last().appearance)
        assertEquals(ProfileId("new"), accepted.catalog.last().id)
    }

    @Test
    fun `rename retains insertion order and concurrent definition edits require review`() {
        val document = catalogTestDocument()
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val session = sessionRecord(document)
        val renamed = stage(document, session.work, CatalogCommand.Rename(user.id, "Mine"))
        val result =
            assertIs<CatalogPlan.Ready>(
                StudioCatalogPolicy.plan(document, session.copy(work = renamed)),
            ).document
        assertEquals(document.catalog.map { it.id }, result.catalog.map { it.id })
        assertEquals("Mine", result.catalog.last().name)
        assertEquals(document.currents, result.currents)
        val external =
            document.copy(
                catalog =
                    StudioList(
                        document.catalog.map {
                            if (it.id ==
                                user.id
                            ) {
                                user.copy(name = "External", revision = 2)
                            } else {
                                it
                            }
                        },
                    ),
            )
        assertIs<CatalogPlan.Review>(StudioCatalogPolicy.plan(external, session.copy(work = renamed)))
    }

    @Test
    fun `a saved rename updates labels in open and recovered drafts without replacing them`() {
        val document = catalogTestDocument()
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val session = sessionRecord(document)
        val draft =
            session.work.drafts.single().copy(
                source = ProfileReference(user.id, user.revision),
                sourceName = user.name,
            )
        val work = session.work.copy(drafts = StudioList(listOf(draft)))
        val recovered = session.copy(id = "other", work = work, availability = SessionAvailability.RECOVERED)
        val retired = session.copy(id = "retired", work = work, availability = SessionAvailability.RETIRED)
        val records = StudioList(listOf(session.copy(work = work), recovered, retired))
        val latest = document.copy(sessions = records)
        val staged = stage(latest, work, CatalogCommand.Rename(user.id, "Focus"))
        val request = session.copy(work = staged)

        val accepted = assertIs<CatalogPlan.Ready>(StudioCatalogPolicy.plan(latest, request)).document

        assertEquals(listOf(session.id, recovered.id, retired.id), accepted.sessions.map { it.id })
        for (record in accepted.sessions.take(2)) {
            assertEquals(draft.copy(sourceName = "Focus"), record.work.drafts.single())
            assertEquals(work.selected, record.work.selected)
        }
        assertEquals(retired, accepted.sessions.last())
        assertEquals(latest.currents, accepted.currents)
    }

    @Test
    fun `updating a saved profile keeps an external rename and leaves Current untouched`() {
        val document = catalogTestDocument()
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val session = sessionRecord(document)
        val draft =
            session.work.drafts
                .single()
                .copy(appearance = document.catalog.first().appearance)
        val edited = session.work.copy(drafts = StudioList(listOf(draft)))
        val staged = stage(document, edited, CatalogCommand.Update(draft.id, user.id))
        val renamed = user.copy(name = "External", revision = user.revision + 1)
        val latest = document.copy(catalog = StudioList(document.catalog.dropLast(1) + renamed))

        val accepted =
            assertIs<CatalogPlan.Ready>(
                StudioCatalogPolicy.plan(latest, session.copy(work = staged)),
            ).document

        assertEquals(
            renamed.copy(appearance = draft.appearance, revision = renamed.revision + 1),
            accepted.catalog.last(),
        )
        assertEquals(latest.currents, accepted.currents)
    }

    @Test
    fun `an already applied rename keeps the latest profile revision`() {
        val document = catalogTestDocument()
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val session = sessionRecord(document)
        val renamed = user.copy(name = "Focus", revision = user.revision + 1)
        val staged = stage(document, session.work, CatalogCommand.Rename(user.id, renamed.name))
        val latest = document.copy(catalog = StudioList(document.catalog.dropLast(1) + renamed))

        val request = session.copy(work = staged)
        val accepted = assertIs<CatalogPlan.Ready>(StudioCatalogPolicy.plan(latest, request)).document

        assertEquals(latest.catalog, accepted.catalog)
        assertEquals(latest.currents, accepted.currents)
    }

    @Test
    fun `delete detaches closed projects and every distinct draft while preserving data and order`() {
        val original = catalogTestDocument()
        val user = original.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val reference = ProfileReference(user.id, user.revision)
        val current = original.currents.global.copy(source = reference)
        val draftA = DraftRecord(DraftId("a"), reference, current, current.appearance)
        val draftB = draftA.copy(id = DraftId("b"), appearance = original.catalog.first().appearance)
        val session =
            sessionRecord(
                original,
            ).copy(
                work =
                    SessionWork(
                        StudioList(listOf(draftA, draftB)),
                        draftA.id,
                        StudioList(emptyList()),
                        StudioList(emptyList()),
                    ),
            )
        val orphan = session.copy(id = "orphan", availability = SessionAvailability.RECOVERED)
        val document =
            original.copy(
                currents =
                    original.currents.copy(
                        global = current,
                        projects =
                            StudioList(
                                listOf(StudioProjectRecord(StudioProjectKey("closed"), "Closed", current)),
                            ),
                    ),
                sessions = StudioList(listOf(session, orphan)),
            )
        val staged = session.copy(work = stage(document, session.work, CatalogCommand.Delete(user.id)))
        val impact =
            assertIs<CatalogReview.ConfirmDelete>(
                assertIs<CatalogPlan.Review>(StudioCatalogPolicy.plan(document, staged)).items.single(),
            ).impact
        assertEquals(2, impact.targets.size)
        assertEquals(4, impact.drafts.size)
        val approvals = CatalogApprovals(deletions = StudioList(listOf(impact)))
        val otherProject = StudioProjectRecord(StudioProjectKey("other"), "Other", current)
        val latest =
            document.copy(
                currents =
                    document.currents.copy(
                        projects =
                            StudioList(
                                document.currents.projects + otherProject,
                            ),
                    ),
            )
        val refreshedReview = assertIs<CatalogPlan.Review>(StudioCatalogPolicy.plan(latest, staged, approvals))
        val refreshedImpact = assertIs<CatalogReview.ConfirmDelete>(refreshedReview.items.single()).impact
        assertEquals(impact.targets + StudioTarget.Project(otherProject.key), refreshedImpact.targets)
        val result =
            assertIs<CatalogPlan.Ready>(
                StudioCatalogPolicy.plan(document, staged, approvals),
            ).document
        assertTrue(result.catalog.none { it.id == user.id })
        assertNull(result.currents.global.source)
        assertNull(
            result.currents.projects
                .single()
                .current.source,
        )
        assertEquals(current.appearance, result.currents.global.appearance)
        for ((drafts) in result.sessions.map { it.work }) {
            assertEquals(listOf(draftA.appearance, draftB.appearance), drafts.map { it.appearance })
            assertEquals(listOf(draftA.id, draftB.id), drafts.map { it.id })
            assertTrue(drafts.all { it.source == null && it.base.source == null })
        }
    }

    @Test
    fun `deleting a staged addition keeps draft and removing a definition never drops staged updates`() {
        val document = catalogTestDocument()
        val session = sessionRecord(document)
        val created =
            stage(document, session.work, CatalogCommand.Create(session.work.selected, ProfileId("new"), "New"))
        val cancelled = stage(document, created, CatalogCommand.Delete(ProfileId("new")))
        assertTrue(cancelled.catalog.isEmpty())
        assertEquals(session.work.drafts, cancelled.drafts)
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val updated = stage(document, session.work, CatalogCommand.Update(session.work.selected, user.id))
        val deleted = stage(document, updated, CatalogCommand.Delete(user.id))
        assertEquals(2, deleted.catalog.size)
        val result = assertIs<CatalogPlan.Review>(StudioCatalogPolicy.plan(document, session.copy(work = deleted)))
        assertTrue(result.items.any { it is CatalogReview.ConflictingIntents })
    }

    @Test
    fun `a discarded update does not prevent deleting its former profile`() {
        val document = catalogTestDocument()
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val session = sessionRecord(document)
        val updated = stage(document, session.work, CatalogCommand.Update(session.work.selected, user.id))
        val reference = ProfileReference(user.id, user.revision)
        val draft = updated.drafts.single()
        val linked = draft.copy(source = reference, base = draft.base.copy(source = reference), sourceName = user.name)
        val retiredWork = updated.copy(drafts = StudioList(listOf(linked)))
        val retired = session.copy(id = "discarded", work = retiredWork, availability = SessionAvailability.RETIRED)
        val latest = document.copy(sessions = StudioList(listOf(retired)))
        val request = session.copy(work = stage(latest, session.work, CatalogCommand.Delete(user.id)))

        val review = assertIs<CatalogPlan.Review>(StudioCatalogPolicy.plan(latest, request))
        val impact = assertIs<CatalogReview.ConfirmDelete>(review.items.single()).impact
        assertTrue(impact.drafts.isEmpty())
        val approvals = CatalogApprovals(deletions = StudioList(listOf(impact)))
        val accepted = assertIs<CatalogPlan.Ready>(StudioCatalogPolicy.plan(latest, request, approvals)).document

        assertTrue(accepted.catalog.none { it.id == user.id })
        assertEquals(retired, accepted.sessions.first())
    }

    @Test
    fun `external deletion detaches a retained draft without replacing its content`() {
        val document = catalogTestDocument()
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val reference = ProfileReference(user.id, user.revision)
        val session = sessionRecord(document)
        val draft =
            session.work.drafts.single().copy(
                source = reference,
                base = document.currents.global.copy(source = reference),
                appearance = document.catalog.first().appearance,
                sourceName = user.name,
            )
        val request = session.copy(work = session.work.copy(drafts = StudioList(listOf(draft))))
        val latest = document.copy(catalog = StudioList(document.catalog.filterNot { it.id == user.id }))
        val result = assertIs<CatalogPlan.Ready>(StudioCatalogPolicy.plan(latest, request)).document
        val detached =
            result.sessions
                .single()
                .work.drafts
                .single()
        assertNull(detached.source)
        assertNull(detached.base.source)
        assertEquals(draft.appearance, detached.appearance)
        assertEquals(draft.id, detached.id)
        assertEquals(user.name, detached.sourceName)
    }

    @Test
    fun `external rename refreshes only the label and retains the exact open snapshot`() {
        val document = catalogTestDocument()
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val session = sessionRecord(document)
        val draft =
            session.work.drafts.single().copy(
                source = ProfileReference(user.id, user.revision),
                sourceName = user.name,
            )
        val request = session.copy(work = session.work.copy(drafts = StudioList(listOf(draft))))
        val latest =
            document.copy(
                catalog =
                    StudioList(
                        document.catalog.map {
                            if (it.id ==
                                user.id
                            ) {
                                user.copy(name = "Renamed externally", revision = user.revision + 1)
                            } else {
                                it
                            }
                        },
                    ),
            )
        val result = assertIs<CatalogPlan.Ready>(StudioCatalogPolicy.plan(latest, request)).document
        val retained =
            result.sessions
                .single()
                .work.drafts
                .single()
        assertEquals("Renamed externally", retained.sourceName)
        assertEquals(draft.appearance, retained.appearance)
        assertEquals(draft.source, retained.source)
    }

    private fun sessionRecord(document: StudioDocument): StudioSessionRecord =
        StudioSessionRecord(
            "session",
            1,
            StudioTarget.Global,
            document.header.revision,
            catalogTestWork(document),
            SessionAvailability.LIVE,
        )

    private fun stage(
        document: StudioDocument,
        work: SessionWork,
        command: CatalogCommand,
    ): SessionWork = assertIs<CatalogStage.Staged>(StudioCatalogStaging.stage(document, work, command)).work
}
