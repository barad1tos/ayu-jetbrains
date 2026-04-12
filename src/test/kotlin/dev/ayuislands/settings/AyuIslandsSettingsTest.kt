package dev.ayuislands.settings

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAccentProvider
import dev.ayuislands.font.FontPreset
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import java.awt.GraphicsEnvironment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `getAccentForVariant returns default for empty string`() {
        val state =
            AyuIslandsState().apply {
                mirageAccent = ""
            }
        val settings = createSettings(state)

        // BaseState's `by string()` coerces empty to default
        assertEquals(
            AyuVariant.MIRAGE.defaultAccent,
            settings.getAccentForVariant(AyuVariant.MIRAGE),
        )
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

    @Test
    fun `seedInstalledFontsFromDiskIfNeeded is no-op when already seeded`() {
        val state = AyuIslandsState().apply { installedFontsSeeded = true }
        val settings = createSettings(state)

        settings.seedInstalledFontsFromDiskIfNeeded()

        assertTrue(state.installedFontsSeeded)
        assertEquals(0, state.installedFonts.size)
    }

    @Test
    fun `seedInstalledFontsFromDiskIfNeeded sets seeded flag on first run`() {
        val settings = createSettings(AyuIslandsState())

        settings.seedInstalledFontsFromDiskIfNeeded()

        assertTrue(settings.state.installedFontsSeeded)
    }

    @Test
    fun `seedInstalledFontsFromDiskIfNeeded does not re-add already installed fonts`() {
        val fontFamily = FontPreset.WHISPER.fontFamily
        val state =
            AyuIslandsState().apply {
                installedFonts.add(fontFamily)
            }
        val settings = createSettings(state)

        settings.seedInstalledFontsFromDiskIfNeeded()

        // Recorded font should only appear once, not duplicated
        assertEquals(1, state.installedFonts.count { it == fontFamily })
    }

    @Test
    fun `seeder respectsExplicitUninstallGuard`() {
        mockkStatic(GraphicsEnvironment::class)
        val ge = mockk<GraphicsEnvironment>()
        every { GraphicsEnvironment.getLocalGraphicsEnvironment() } returns ge
        every { ge.availableFontFamilyNames } returns arrayOf("Maple Mono", "Victor Mono")

        val settings = AyuIslandsSettings()
        settings.state.explicitlyUninstalledFonts.add("Maple Mono")
        settings.state.installedFontsSeeded = false

        settings.seedInstalledFontsFromDiskIfNeeded()

        assertFalse(settings.state.installedFonts.contains("Maple Mono"))
        assertTrue(settings.state.installedFontsSeeded)

        unmockkStatic(GraphicsEnvironment::class)
    }

    @Test
    fun `seeder addsNonGuardedFamilies`() {
        mockkStatic(GraphicsEnvironment::class)
        val ge = mockk<GraphicsEnvironment>()
        every { GraphicsEnvironment.getLocalGraphicsEnvironment() } returns ge
        every { ge.availableFontFamilyNames } returns arrayOf("Maple Mono", "Victor Mono")

        val settings = AyuIslandsSettings()
        settings.state.explicitlyUninstalledFonts.add("Maple Mono")
        settings.state.installedFontsSeeded = false

        settings.seedInstalledFontsFromDiskIfNeeded()

        assertTrue(settings.state.installedFonts.contains("Victor Mono"))
        assertFalse(settings.state.installedFonts.contains("Maple Mono"))

        unmockkStatic(GraphicsEnvironment::class)
    }

    @Test
    fun `seeder marksSeededFlagEvenWhenGuardBlocksAll`() {
        mockkStatic(GraphicsEnvironment::class)
        val ge = mockk<GraphicsEnvironment>()
        every { GraphicsEnvironment.getLocalGraphicsEnvironment() } returns ge
        every { ge.availableFontFamilyNames } returns arrayOf("Maple Mono", "Victor Mono")

        val settings = AyuIslandsSettings()
        settings.state.explicitlyUninstalledFonts.add("Maple Mono")
        settings.state.explicitlyUninstalledFonts.add("Victor Mono")
        settings.state.explicitlyUninstalledFonts.add("Monaspace Neon")
        settings.state.explicitlyUninstalledFonts.add("Monaspace Xenon")
        settings.state.installedFontsSeeded = false

        settings.seedInstalledFontsFromDiskIfNeeded()

        assertTrue(settings.state.installedFonts.isEmpty())
        assertTrue(settings.state.installedFontsSeeded)

        unmockkStatic(GraphicsEnvironment::class)
    }
}
