package dev.ayuislands.settings

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAccentProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AyuIslandsSettingsTest {
    private fun createSettings(state: AyuIslandsState): AyuIslandsSettings {
        val settings = AyuIslandsSettings()
        settings.loadState(state)
        return settings
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getAccentForVariant returns stored accent for MIRAGE`() {
        val state = AyuIslandsState().apply { mirageAccent = "#FF0000" }
        val settings = createSettings(state)

        assertEquals("#FF0000", settings.getAccentForVariant(AyuVariant.MIRAGE))
    }

    @Test
    fun `getAccentForVariant returns default when no stored accent`() {
        val state =
            AyuIslandsState().apply {
                mirageAccent = null
                darkAccent = null
                lightAccent = null
            }
        val settings = createSettings(state)

        assertEquals("#FFCC66", settings.getAccentForVariant(AyuVariant.MIRAGE))
        assertEquals("#E6B450", settings.getAccentForVariant(AyuVariant.DARK))
        assertEquals("#F29718", settings.getAccentForVariant(AyuVariant.LIGHT))
    }

    @Test
    fun `getAccentForVariant returns system accent when followSystemAccent enabled`() {
        mockkObject(SystemAccentProvider)
        every { SystemAccentProvider.resolve() } returns "#AABBCC"

        val state =
            AyuIslandsState().apply {
                followSystemAccent = true
                mirageAccent = "#FF0000"
            }
        val settings = createSettings(state)

        assertEquals("#AABBCC", settings.getAccentForVariant(AyuVariant.MIRAGE))
    }

    @Test
    fun `getAccentForVariant falls back to stored when system accent null`() {
        mockkObject(SystemAccentProvider)
        every { SystemAccentProvider.resolve() } returns null

        val state =
            AyuIslandsState().apply {
                followSystemAccent = true
                mirageAccent = "#FF0000"
            }
        val settings = createSettings(state)

        assertEquals("#FF0000", settings.getAccentForVariant(AyuVariant.MIRAGE))
    }

    @Test
    fun `setAccentForVariant stores value per variant`() {
        val settings = createSettings(AyuIslandsState())

        settings.setAccentForVariant(AyuVariant.MIRAGE, "#AA1111")
        settings.setAccentForVariant(AyuVariant.DARK, "#BB2222")
        settings.setAccentForVariant(AyuVariant.LIGHT, "#CC3333")

        assertEquals("#AA1111", settings.state.mirageAccent)
        assertEquals("#BB2222", settings.state.darkAccent)
        assertEquals("#CC3333", settings.state.lightAccent)
    }
}
