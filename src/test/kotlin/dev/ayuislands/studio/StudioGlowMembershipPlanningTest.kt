package dev.ayuislands.studio

import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioGlowMembershipPlanning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioGlowMembershipPlanningTest {
    private val defaults =
        GlowSettings(
            enabled = true,
            islandToggles = mapOf("Editor" to true, "Git" to false, "Project" to true, "Terminal" to false),
        )

    @Test
    fun `disabling Git retires only its mounted aliases in their existing order`() {
        val delta =
            plan(
                select("glowGit"),
                defaults,
                available = true,
                mounted = listOf("Terminal", "Git", "Commit", "Version Control", "Project"),
                candidates = setOf("Editor", "Project"),
            )

        assertEquals(listOf("Git", "Commit", "Version Control"), delta.retirements)
        assertTrue(delta.additions.isEmpty())
    }

    @Test
    fun `enabling Git mounts only its available missing hosts`() {
        val settings = defaults.copy(islandToggles = defaults.islandToggles + ("Git" to true))
        val delta =
            plan(
                select("glowGit"),
                settings,
                available = true,
                mounted = listOf("Commit"),
                candidates = setOf("Editor", "Version Control", "Commit"),
            )

        assertEquals(listOf("Version Control"), delta.additions)
        assertTrue(delta.retirements.isEmpty())
    }

    @Test
    fun `accent and style changes leave membership alone`() {
        val selection =
            select("glowStyle").copy(
                dependencies = StudioList(listOf(GlowDependency.ACCENT)),
            )
        assertNull(StudioGlowMembershipPlanning.select(selection, defaults, available = true))
    }

    @Test
    fun `availability and the global toggle share ownership while keeping saved island choices`() {
        val selection = StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.AVAILABILITY)))
        val mounted = listOf("Commit", "Project", "Structure")
        val eligible = setOf("Editor", "Project", "Terminal", "Git")

        val unavailable = plan(selection, defaults, false, mounted, eligible)
        val disabled =
            plan(
                select("glowEnabled"),
                defaults.copy(enabled = false),
                available = true,
                mounted,
                eligible,
            )
        val reenabled = plan(selection, defaults, true, listOf("Structure"), eligible)

        assertEquals(listOf("Commit", "Project"), unavailable.retirements)
        assertTrue(unavailable.additions.isEmpty())
        assertEquals(unavailable, disabled)
        assertEquals(listOf("Editor", "Project"), reenabled.additions)
        assertTrue(reenabled.retirements.isEmpty())
        assertEquals(
            mapOf("Editor" to true, "Git" to false, "Project" to true, "Terminal" to false),
            defaults.islandToggles,
        )
    }

    private fun plan(
        selection: StudioGlowSelection,
        settings: GlowSettings,
        available: Boolean,
        mounted: List<String>,
        candidates: Set<String>,
    ): StudioGlowMembershipPlanning.Delta =
        assertNotNull(StudioGlowMembershipPlanning.select(selection, settings, available)).plan(mounted, candidates)

    private fun select(name: String): StudioGlowSelection =
        StudioGlowSelection(fields = StudioList(listOf(FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, name)))))
}
