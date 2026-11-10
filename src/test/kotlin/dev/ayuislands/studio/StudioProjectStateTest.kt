package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.profiles.StudioProjectState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class StudioProjectStateTest {
    @Test
    fun `only a new project receives an independent unbound copy of current accepted intent`() {
        val imported = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture())).document
        val duplicates =
            listOf("user-1", "user-2").map { id ->
                ProfileDefinition.User(ProfileId(id), "Same name", imported.currents.global.appearance, revision = 1)
            }
        val original = imported.copy(catalog = StudioList(imported.catalog + duplicates))
        val current = original.currents.global.copy(source = ProfileReference(ProfileId("factory:neon"), 1))
        val key = StudioProjectKey($$"/work/$LITERAL$/project")
        val initialized = StudioProjectState.initialize(original, key, "Same name", current)
        val project = initialized.currents.projects.single()

        assertEquals(key, project.key)
        assertEquals(current.appearance, project.current.appearance)
        assertNotSame(current.appearance, project.current.appearance)
        assertNull(project.current.source)
        assertEquals(original.catalog, initialized.catalog)
        assertEquals(original.shared, initialized.shared)
        assertSame(initialized, StudioProjectState.initialize(initialized, key, "Renamed", original.currents.global))

        val moved = StudioProjectState.initialize(initialized, StudioProjectKey("/work/moved"), "Same name", current)
        assertEquals(listOf(key, StudioProjectKey("/work/moved")), moved.currents.projects.map { it.key })
    }
}
