package dev.ayuislands

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StartupFontPersistenceTest {
    @Test
    fun `startup preserves an unavailable font preset through disable and reenable reloads`() {
        val originalCustomizations =
            mapOf(
                "FUTURE_PRESET" to "21.00|1.37|true|FUTURE_WEIGHT|Unavailable Font|extension=42",
                "AMBIENT" to "14|1.2|true|REGULAR",
            )
        var settings =
            AyuIslandsSettings().apply {
                state.fontPresetEnabled = true
                state.fontPresetName = "FUTURE_PRESET"
                state.fontPresetCustomizations.putAll(originalCustomizations)
                state.darkAccent = "#AABBCC"
            }

        settings = migrateTwiceAndReload(settings)
        settings.state.fontPresetEnabled = false
        settings = migrateTwiceAndReload(settings)
        settings.state.fontPresetEnabled = true
        settings = migrateTwiceAndReload(settings)

        assertEquals("FUTURE_PRESET", settings.state.fontPresetName)
        assertEquals(originalCustomizations, settings.state.fontPresetCustomizations)
        assertEquals("#AABBCC", settings.state.darkAccent)
    }

    @Test
    fun `startup preserves a whitespace font name through reload`() {
        val settings = AyuIslandsSettings().apply { state.fontPresetName = "  " }

        migrateFontPresets(settings.state)

        assertEquals("  ", reload(settings.state).state.fontPresetName)
    }

    @Test
    fun `startup does not persist a fallback for a missing font name`() {
        val state = AyuIslandsState().apply { fontPresetName = null }
        val originalXml = encodedState(state)

        migrateFontPresets(state)

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
                AyuIslandsSettings().apply {
                    state.fontPresetName = legacyName
                    state.fontPresetCustomizations[legacyName] = "legacy|$legacyName|customization"
                    state.fontPresetCustomizations[currentName] = destinationCustomization
                }

            migrateFontPresets(settings.state)
            val reloaded = reload(settings.state)

            assertEquals(currentName, reloaded.state.fontPresetName)
            assertEquals(destinationCustomization, reloaded.state.fontPresetCustomizations[currentName])
            assertFalse(reloaded.state.fontPresetCustomizations.containsKey(legacyName))
        }
    }

    private fun reload(state: AyuIslandsState): AyuIslandsSettings {
        val xml = encodedState(state)
        val reloadedState = XmlSerializer.deserialize(JDOMUtil.load(xml), AyuIslandsState::class.java)
        return AyuIslandsSettings().apply { loadState(reloadedState) }
    }

    private fun migrateTwiceAndReload(settings: AyuIslandsSettings): AyuIslandsSettings {
        val expectedXml = encodedState(settings.state)
        repeat(2) { migrateFontPresets(settings.state) }
        val reloaded = reload(settings.state)
        assertEquals(expectedXml, encodedState(reloaded.state))
        return reloaded
    }

    private fun encodedState(state: AyuIslandsState): String = JDOMUtil.writeElement(XmlSerializer.serialize(state))
}
