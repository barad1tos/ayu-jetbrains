package dev.ayuislands.studio

import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProfileChange
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioProfileKind
import dev.ayuislands.studio.session.StudioProfileProjection
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioProfileProjectionTest {
    @Test
    fun `staged definitions append without selecting or replacing the accepted appearance`() {
        val document = sessionDocument()
        val initial = sessionState(document)
        val record = requireNotNull(initial.record)
        val created =
            ProfileDefinition.User(
                ProfileId("created"),
                "Saved later",
                document.catalog.first().appearance,
                1,
            )
        val state =
            initial.copy(
                record =
                    record.copy(
                        work =
                            record.work.copy(
                                catalog = StudioList(listOf(CatalogIntent.Create(created, record.work.selected))),
                            ),
                    ),
            )

        val view = StudioProfileProjection.project(document, state)
        val profileRows = view.rows.filter { it.choice is StudioProfileChoice.Profile }
        assertEquals(
            document.catalog.map { it.id } + created.id,
            profileRows.map { (it.choice as StudioProfileChoice.Profile).id },
        )
        assertEquals(listOf(StudioProfileChange.CREATE), profileRows.last().pending)
        assertFalse(profileRows.last().isCurrent)
        assertNotEquals(profileRows.last().choice, view.selected)
        assertEquals(StudioProfileProjection.project(document, initial).currentName, view.currentName)
        assertTrue(view.hasPendingChanges)
    }

    @Test
    fun `rename update and delete remain distinct pending facts on the accepted row`() {
        val document = sessionDocument()
        val initial = sessionState(document)
        val record = requireNotNull(initial.record)
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val changed = user.copy(appearance = document.catalog.first().appearance, revision = user.revision + 1)
        val intents =
            listOf(
                CatalogIntent.Rename(user, "Next name"),
                CatalogIntent.Update(user, changed, record.work.selected),
                CatalogIntent.Delete(user),
            )
        val state = initial.copy(record = record.copy(work = record.work.copy(catalog = StudioList(intents))))

        val view = StudioProfileProjection.project(document, state)
        val row = view.rows.single { it.choice == StudioProfileChoice.Profile(user.id) }
        assertEquals(user.name, row.label.name)
        assertEquals("Next name", row.label.pendingName)
        assertEquals(
            listOf(StudioProfileChange.RENAME, StudioProfileChange.UPDATE, StudioProfileChange.DELETE),
            row.pending,
        )
        assertEquals(
            document.catalog.map { it.id },
            view.rows.mapNotNull { (it.choice as? StudioProfileChoice.Profile)?.id },
        )
        assertTrue(row.isEnabled)
        assertEquals(user, document.catalog.single { it.id == user.id })
    }

    @Test
    fun `duplicate labels use stable distinct identity qualifiers without sorting or changing names`() {
        val original = sessionDocument()
        val first = ProfileDefinition.User(ProfileId("same-prefix-a"), "Same", original.catalog.first().appearance, 1)
        val second = first.copy(id = ProfileId("same-prefix-b"))
        val document = original.copy(catalog = StudioList(original.catalog + listOf(first, second)))
        val state = sessionState(document)

        val view = StudioProfileProjection.project(document, state)
        val duplicates = view.rows.filter { it.label.name == "Same" }
        assertEquals(listOf(first.id, second.id), duplicates.map { (it.choice as StudioProfileChoice.Profile).id })
        assertTrue(duplicates.all { it.label.qualifier != null })
        assertNotEquals(duplicates[0].label.qualifier, duplicates[1].label.qualifier)
        assertEquals(
            duplicates.map { it.label },
            StudioProfileProjection
                .project(document, state)
                .rows
                .filter { it.label.name == "Same" }
                .map { it.label },
        )
        assertTrue(view.rows.filter { it.kind == StudioProfileKind.FACTORY }.all { it.label.qualifier == null })
    }

    @Test
    fun `unbound current and detached drafts remain reachable after the catalog in retained order`() {
        val original = sessionDocument()
        val document =
            original.copy(
                currents = original.currents.copy(global = original.currents.global.copy(source = null)),
            )
        val initial = sessionState(document)
        val record = requireNotNull(initial.record)
        val currentDraft = record.work.drafts.single()
        val detached = currentDraft.copy(id = DraftId("detached"), sourceName = "Deleted profile")
        val state =
            initial.copy(
                record = record.copy(work = record.work.copy(drafts = StudioList(listOf(currentDraft, detached)))),
            )

        val view = StudioProfileProjection.project(document, state)
        assertEquals("Current appearance", view.currentName)
        assertEquals(StudioProfileChoice.Draft(currentDraft.id), view.selected)
        assertEquals(
            listOf(currentDraft.id, detached.id),
            view.rows
                .drop(document.catalog.size)
                .map { (it.choice as StudioProfileChoice.Draft).id },
        )
        assertEquals(listOf("Current appearance", "Deleted profile"), view.rows.takeLast(2).map { it.label.name })
        assertTrue(view.rows.takeLast(2).all { it.kind == StudioProfileKind.DETACHED })
        assertFalse(view.rows.any { it.isCurrent })
    }

    @Test
    fun `browsing updates the draft view before Apply while Current remains accepted`() {
        val document = sessionDocument()
        val initial = sessionState(document)
        val before = StudioProfileProjection.project(document, initial)
        val profile = document.catalog.first()
        val selected =
            StudioSessionReducer
                .reduce(
                    initial,
                    SessionEvent.Intent(SessionIntent.Browse(profile.id, DraftId("browsed"))),
                    sessionInputs(document, initial),
                ).state

        val draftView = StudioProfileProjection.project(document, selected)
        assertEquals(StudioProfileChoice.Profile(profile.id), draftView.selected)
        assertEquals(PreviewMode.DRAFT, draftView.preview)
        assertTrue(draftView.hasPendingChanges)
        assertEquals(before.currentName, draftView.currentName)
        assertEquals(
            before.rows
                .filter {
                    it.isCurrent
                }.map { it.choice },
            draftView.rows.filter { it.isCurrent }.map { it.choice },
        )
        val previewed =
            StudioSessionReducer
                .reduce(
                    selected,
                    SessionEvent.Intent(SessionIntent.Preview(PreviewMode.CURRENT)),
                    sessionInputs(document, selected),
                ).state
        assertEquals(selected.record, previewed.record)
        assertEquals(PreviewMode.CURRENT, StudioProfileProjection.project(document, previewed).preview)
    }

    @Test
    fun `loading disables selection while recovery permits only presentation changes`() {
        val document = sessionDocument()
        val initial = sessionState(document)
        val loading =
            StudioProfileProjection.project(
                document,
                initial.copy(record = null, phase = SessionPhase.Loading),
            )
        assertNull(loading.selected)
        assertTrue(loading.rows.none { it.isEnabled })
        assertFalse(loading.isPreviewEnabled)
        val recovery =
            initial.copy(
                phase =
                    SessionPhase.Processing(
                        OperationStage.RECOVERY_REQUIRED,
                        OperationStamp(initial.identity.id, 0, "recovery"),
                    ),
            )
        val recovering = StudioProfileProjection.project(document, recovery)
        assertTrue(recovering.rows.none { it.isEnabled })
        assertTrue(recovering.isPreviewEnabled)
        assertEquals(StudioProfileProjection.project(document, initial).selected, recovering.selected)
    }
}
