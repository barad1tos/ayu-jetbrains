package dev.ayuislands

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.font.FontPreset
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.session.StudioFontProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StartupFontPersistenceTest {
    @Test
    fun `startup preserves an unavailable font preset through disable and reenable reloads`() {
        val originalCustomizations =
            mapOf(
                "FUTURE_PRESET" to "21.00|1.37|true|FUTURE_WEIGHT|Unavailable Font|extension=42",
                "AMBIENT" to "14|1.2|true|REGULAR",
            )
        var settings =
            AyuIslandsState().apply {
                fontPresetEnabled = true
                fontPresetName = "FUTURE_PRESET"
                fontPresetCustomizations.putAll(originalCustomizations)
                darkAccent = "#AABBCC"
            }

        settings = migrateTwiceAndReload(settings)
        settings.fontPresetEnabled = false
        settings = migrateTwiceAndReload(settings)
        settings.fontPresetEnabled = true
        settings = migrateTwiceAndReload(settings)

        assertEquals("FUTURE_PRESET", settings.fontPresetName)
        assertEquals(originalCustomizations, settings.fontPresetCustomizations)
        assertEquals("#AABBCC", settings.darkAccent)
    }

    @Test
    fun `startup preserves a whitespace font name through reload`() {
        val settings = AyuIslandsState().apply { fontPresetName = "  " }

        selection(settings)

        assertEquals("  ", reload(settings).fontPresetName)
    }

    @Test
    fun `startup does not persist a fallback for a missing font name`() {
        val state = AyuIslandsState().apply { fontPresetName = null }
        val originalXml = encodedState(state)

        selection(state)

        assertEquals(null, state.fontPresetName)
        assertEquals(originalXml, encodedState(state))
    }

    @Test
    fun `startup migrates every legacy font name without replacing destination customizations`() {
        val migrations =
            mapOf(
                "GLOW_WRITER" to "WHISPER",
                "CLEAN" to "AMBIENT",
                "MODERN" to "AMBIENT",
                "COMPACT" to "NEON",
            )

        for ((legacyName, currentName) in migrations) {
            val destinationCustomization = "current|$currentName|customization"
            val settings =
                AyuIslandsState().apply {
                    fontPresetName = legacyName
                    fontPresetCustomizations[legacyName] = "legacy|$legacyName|customization"
                    fontPresetCustomizations[currentName] = destinationCustomization
                }

            selection(settings)
            val reloaded = reload(settings)

            assertEquals(legacyName, reloaded.fontPresetName)
            assertEquals(FontPreset.valueOf(currentName), selection(reloaded).settings?.preset)
            assertEquals(destinationCustomization, reloaded.fontPresetCustomizations[currentName])
            assertEquals("legacy|$legacyName|customization", reloaded.fontPresetCustomizations[legacyName])
        }
    }

    private fun reload(state: AyuIslandsState): AyuIslandsState {
        val xml = encodedState(state)
        val reloadedState = XmlSerializer.deserialize(JDOMUtil.load(xml), AyuIslandsState::class.java)
        return reloadedState
    }

    private fun migrateTwiceAndReload(settings: AyuIslandsState): AyuIslandsState {
        val expectedXml = encodedState(settings)
        repeat(2) { selection(settings) }
        val reloaded = reload(settings)
        assertEquals(expectedXml, encodedState(reloaded))
        return reloaded
    }

    private fun selection(state: AyuIslandsState) =
        StudioFontProjection.selection(
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(StudioFixtures.capture(state)),
            ).document.currents.global.appearance,
        )

    private fun encodedState(state: AyuIslandsState): String = JDOMUtil.writeElement(XmlSerializer.serialize(state))
}
