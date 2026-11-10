package dev.ayuislands.studio

import dev.ayuislands.studio.model.CatalogApprovals
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogError
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
import dev.ayuislands.studio.session.StudioCatalogPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioCatalogPolicyTest {
    @Test
    fun `create captures all five domains without selecting or activating and permits duplicate names`() {
        val document = sessionDocument()
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
    fun `factories reject writes and entered invalid names are never silently repaired`() {
        val document = sessionDocument()
        val work = sessionRecord(document).work
        val factory = document.catalog.filterIsInstance<ProfileDefinition.Factory>().first()
        for (command in listOf(
            CatalogCommand.Update(work.selected, factory.id),
            CatalogCommand.Rename(factory.id, "Renamed"),
            CatalogCommand.Delete(factory.id),
        )) {
            assertEquals(
                CatalogStage.Rejected(CatalogError.FACTORY_LOCKED),
                StudioCatalogPolicy.stage(document, work, command),
            )
        }
        for (name in listOf(" ", "a\nb", "a\rb", "a\tb", "\u0000name")) {
            assertEquals(
                CatalogStage.Rejected(CatalogError.INVALID_NAME),
                StudioCatalogPolicy.stage(document, work, CatalogCommand.Create(work.selected, ProfileId("new"), name)),
            )
        }
        val longName = "e\u0301".repeat(500)
        val allowed = stage(document, work, CatalogCommand.Create(work.selected, ProfileId("new"), longName))
        assertEquals(longName, (allowed.catalog.single() as CatalogIntent.Create).definition.name)
    }

    @Test
    fun `later draft edits require explicit retain or refresh of the immutable capture`() {
        val document = sessionDocument()
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
        val document = sessionDocument()
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
    fun `delete detaches closed projects and every distinct draft while preserving data and order`() {
        val original = sessionDocument()
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
        val result =
            assertIs<CatalogPlan.Ready>(
                StudioCatalogPolicy.plan(document, staged, CatalogApprovals(deletions = StudioList(listOf(impact)))),
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
        val document = sessionDocument()
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
    fun `external deletion detaches a retained draft without replacing its content`() {
        val document = sessionDocument()
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
        val document = sessionDocument()
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

    private fun stage(
        document: StudioDocument,
        work: SessionWork,
        command: CatalogCommand,
    ): SessionWork = assertIs<CatalogStage.Staged>(StudioCatalogPolicy.stage(document, work, command)).work
}
