package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogError
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.LegacyCapture
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.session.StudioCatalogStaging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioCatalogStagingTest {
    @Test
    fun `create copies a full draft without selecting or altering it and permits duplicate names`() {
        val document = document()
        val initial = work(document)
        val first = CatalogCommand.Create(initial.selected, ProfileId("new-one"), "  Custom  ")
        val second = CatalogCommand.Create(initial.selected, ProfileId("new-two"), "Custom")
        val staged = stage(document, stage(document, initial, first), second)
        assertEquals(initial.selected, staged.selected)
        assertEquals(initial.drafts, staged.drafts)
        val captures = staged.catalog.filterIsInstance<CatalogIntent.Create>()
        assertEquals(listOf(first.id, second.id), captures.map { it.definition.id })
        assertEquals(listOf("Custom", "Custom"), captures.map { it.definition.name })
        assertEquals(List(2) { initial.drafts.single().appearance }, captures.map { it.definition.appearance })
        assertEquals(staged, stage(document, staged, first))
    }

    @Test
    fun `editing a draft leaves the staged snapshot unchanged until an explicit update`() {
        val document = document()
        val initial = work(document)
        val id = ProfileId("new")
        val staged = stage(document, initial, CatalogCommand.Create(initial.selected, id, "Saved"))
        val changed = initial.drafts.single().copy(appearance = document.catalog.first().appearance)
        val edited = staged.copy(drafts = StudioList(listOf(changed)))
        val captured = assertIs<CatalogIntent.Create>(edited.catalog.single())
        assertEquals(initial.drafts.single().appearance, captured.definition.appearance)
        val updated = stage(document, edited, CatalogCommand.Update(changed.id, id))
        val refreshed = assertIs<CatalogIntent.Create>(updated.catalog.single()).definition
        assertEquals(id, refreshed.id)
        assertEquals(changed.appearance, refreshed.appearance)
        val deleted = stage(document, updated, CatalogCommand.Delete(id))
        assertEquals(edited.drafts, deleted.drafts)
        assertEquals(emptyList(), deleted.catalog)
    }

    @Test
    fun `factories are immutable and invalid names are rejected without changing the draft`() {
        val document = document()
        val initial = work(document)
        val factory = document.catalog.filterIsInstance<ProfileDefinition.Factory>().first()
        val factoryEdits =
            listOf(
                CatalogCommand.Update(initial.selected, factory.id),
                CatalogCommand.Rename(factory.id, "Other"),
                CatalogCommand.Delete(factory.id),
            )
        for (command in factoryEdits) {
            assertEquals(
                CatalogStage.Rejected(CatalogError.FACTORY_LOCKED),
                StudioCatalogStaging.stage(document, initial, command),
            )
        }
        for (name in listOf(" ", "line\nbreak", "a\u2028b", "a\tb")) {
            val command = CatalogCommand.Create(initial.selected, ProfileId("new"), name)
            assertEquals(
                CatalogStage.Rejected(CatalogError.INVALID_NAME),
                StudioCatalogStaging.stage(document, initial, command),
            )
        }
        val fullName = "e\u0301".repeat(500)
        val staged = stage(document, initial, CatalogCommand.Create(initial.selected, ProfileId("new"), fullName))
        assertEquals(fullName, assertIs<CatalogIntent.Create>(staged.catalog.single()).definition.name)
    }

    @Test
    fun `replacing a staged rename preserves intent order and deleting a saved profile retains its other intents`() {
        val document = document()
        val initial = work(document)
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val renamed = stage(document, initial, CatalogCommand.Rename(user.id, "First"))
        val updated = stage(document, renamed, CatalogCommand.Update(initial.selected, user.id))
        val moved = stage(document, updated, CatalogCommand.Rename(user.id, "Second"))
        assertEquals(CatalogIntent.Rename(user, "Second"), moved.catalog[0])
        assertEquals(updated.catalog[1], moved.catalog[1])
        val deleted = stage(document, moved, CatalogCommand.Delete(user.id))
        assertEquals(moved.catalog + CatalogIntent.Delete(user), deleted.catalog)
        assertEquals(moved.catalog, stage(document, deleted, CatalogCommand.Unstage(deleted.catalog[2])).catalog)
        assertEquals(initial.drafts, deleted.drafts)
    }

    @Test
    fun `undo removes only the exact staged edit even if the same action repeats`() {
        val document = document()
        val initial = work(document)
        val user = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val renamed = stage(document, initial, CatalogCommand.Rename(user.id, "First"))
        val staged = stage(document, renamed, CatalogCommand.Update(initial.selected, user.id))
        val undo = CatalogCommand.Unstage(staged.catalog.first())
        val afterUndo = stage(document, staged, undo)
        assertEquals(staged.catalog.drop(1), afterUndo.catalog)
        assertEquals(afterUndo, stage(document, afterUndo, undo))

        val replacement = stage(document, staged, CatalogCommand.Rename(user.id, "Newer"))
        assertEquals(replacement, stage(document, replacement, undo))

        val reentered = stage(document, replacement, CatalogCommand.Rename(user.id, "First"))
        assertEquals(staged.catalog, reentered.catalog)
        assertEquals(reentered, stage(document, reentered, undo))
    }

    @Test
    fun `profile names reject unpaired surrogates but accept emoji`() {
        val document = document()
        val initial = work(document)
        val id = ProfileId("work")
        val created = stage(document, initial, CatalogCommand.Create(initial.selected, id, "Робота 🌿"))
        assertEquals("Робота 🌿", assertIs<CatalogIntent.Create>(created.catalog.single()).definition.name)
        val invalid = CatalogCommand.Rename(id, "Broken " + 0xD800.toChar())
        assertEquals(
            CatalogStage.Rejected(CatalogError.INVALID_NAME),
            StudioCatalogStaging.stage(document, created, invalid),
        )
    }

    private fun stage(
        document: StudioDocument,
        work: SessionWork,
        command: CatalogCommand,
    ): SessionWork = assertIs<CatalogStage.Staged>(StudioCatalogStaging.stage(document, work, command)).work

    private fun document(): StudioDocument {
        val source =
            MigrationSource("catalog", StudioList(LegacyStore.entries.map { LegacyArchive(it, LegacyFile.Absent) }))
        val snapshot = assertIs<LegacyCapture.Ready>(StudioLegacyCapture.read(source, "initial", "/work")).snapshot
        val fresh = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(snapshot)).document
        val custom = ProfileDefinition.User(ProfileId("custom"), "Custom", fresh.currents.global.appearance, 1)
        return fresh.copy(catalog = StudioList(fresh.catalog + custom))
    }

    private fun work(document: StudioDocument): SessionWork {
        val current = document.currents.global
        val draft = DraftRecord(DraftId("selected"), current.source, current, current.appearance)
        return SessionWork(StudioList(listOf(draft)), draft.id, StudioList(emptyList()), StudioList(emptyList()))
    }
}
