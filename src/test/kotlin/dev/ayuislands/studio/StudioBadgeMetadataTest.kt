package dev.ayuislands.studio

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.SettingsBadges
import dev.ayuislands.studio.fields.StudioMetadataPolicy
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StudioBadgeMetadataTest {
    @Test
    fun `separate badge acknowledgements merge with latest metadata without losing unknown entries`() {
        val source =
            document(
                AyuIslandsState().apply {
                    lastSeenVersion = "2.8.9"
                    acknowledgedSettingsBadges.add("future-anchor")
                    settingsBadgeDeadlines["future-anchor"] = "raw-unknown"
                    settingsBadgeDeadlines["glow-waveform"] = "123"
                },
            )
        val first = acknowledge(source, "glow-waveform")
        val second = acknowledge(first, "glow-placement")
        val view = assertNotNull(StudioLegacyProjection.project(second, StudioTarget.Global)).settings

        assertEquals(
            listOf("future-anchor", "glow-waveform", "glow-placement"),
            view.acknowledgedSettingsBadges.toList(),
        )
        assertEquals(mapOf("future-anchor" to "raw-unknown"), view.settingsBadgeDeadlines)
        assertEquals(second, acknowledge(second, "glow-waveform"))
        assertEquals(source.copy(auxiliary = source.auxiliary.copy(ui = second.auxiliary.ui)), second)
    }

    @Test
    fun `release processing preserves independent expiry and unknown metadata across repeated startup`() {
        val source =
            document(
                AyuIslandsState().apply {
                    lastSeenVersion = "2.8.9"
                    acknowledgedSettingsBadges.add("future-anchor")
                    settingsBadgeDeadlines["future-anchor"] = "raw-unknown"
                    settingsBadgeDeadlines["glow-waveform"] = "1"
                },
            )
        val current = assertNotNull(StudioMetadataPolicy.apply(source, StudioMetadataCommand.ReleaseSeen("3.0.0", 100)))
        val view = assertNotNull(StudioLegacyProjection.project(current, StudioTarget.Global)).settings

        assertEquals("3.0.0", view.lastSeenVersion)
        assertTrue("glow-waveform" in view.acknowledgedSettingsBadges)
        assertTrue("future-anchor" in view.acknowledgedSettingsBadges)
        assertEquals("raw-unknown", view.settingsBadgeDeadlines["future-anchor"])
        assertEquals((100 + SettingsBadges.BADGE_LIFETIME_MS).toString(), view.settingsBadgeDeadlines["glow-placement"])
        assertEquals(current, StudioMetadataPolicy.apply(current, StudioMetadataCommand.ReleaseSeen("3.0.0", 101)))
        assertEquals(source.copy(auxiliary = source.auxiliary.copy(ui = current.auxiliary.ui)), current)
    }

    @Test
    fun `fresh install seeds badges once without changing appearance`() {
        val source = document(AyuIslandsState())
        val current = assertNotNull(StudioMetadataPolicy.apply(source, StudioMetadataCommand.ReleaseSeen("3.0.0", 100)))
        val view = assertNotNull(StudioLegacyProjection.project(current, StudioTarget.Global)).settings

        assertEquals(SettingsBadges.registry.map { it.id }, view.acknowledgedSettingsBadges.toList())
        assertTrue(view.settingsBadgeDeadlines.isEmpty())
        assertEquals(source.copy(auxiliary = source.auxiliary.copy(ui = current.auxiliary.ui)), current)
    }

    private fun acknowledge(
        document: StudioDocument,
        id: String,
    ): StudioDocument =
        assertNotNull(StudioMetadataPolicy.apply(document, StudioMetadataCommand.BadgesSeen(StudioList(listOf(id)))))

    private fun document(state: AyuIslandsState): StudioDocument =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture(state))).document
}
