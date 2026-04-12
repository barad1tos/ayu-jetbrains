package dev.ayuislands.settings

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that [AyuIslandsState] survives a save/reload cycle via
 * [com.intellij.openapi.components.SimplePersistentStateComponent.loadState].
 *
 * IntelliJ serializes [com.intellij.openapi.components.BaseState] to XML via
 * reflection. `loadState` copies fields with `XmlSerializerUtil.copyBean`, which is
 * the same mechanism used across real IDE restarts. If properties survive the
 * in-memory round-trip here, they survive on-disk persistence as well.
 */
class AyuIslandsStatePersistenceTest {
    private fun roundTrip(mutate: (AyuIslandsState) -> Unit): AyuIslandsSettings {
        val original = AyuIslandsSettings()
        mutate(original.state)
        val savedState = original.state
        val reloaded = AyuIslandsSettings()
        reloaded.loadState(savedState)
        return reloaded
    }

    @Test
    fun `accent colors survive save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.mirageAccent = "#AA0000"
                state.darkAccent = "#00BB00"
                state.lightAccent = "#0000CC"
            }

        assertEquals("#AA0000", reloaded.state.mirageAccent)
        assertEquals("#00BB00", reloaded.state.darkAccent)
        assertEquals("#0000CC", reloaded.state.lightAccent)
    }

    @Test
    fun `all accent element toggles survive save reload cycle when disabled`() {
        val reloaded =
            roundTrip { state ->
                for (element in AccentElementId.entries) {
                    state.setToggle(element, false)
                }
            }

        for (element in AccentElementId.entries) {
            assertFalse(
                reloaded.state.isToggleEnabled(element),
                "Toggle for ${element.name} must remain disabled after reload",
            )
        }
    }

    @Test
    fun `glow settings survive save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.glowEnabled = true
                state.glowStyle = GlowStyle.SHARP_NEON.name
                state.setIntensityForStyle(GlowStyle.SHARP_NEON, EXPECTED_SHARP_NEON_INTENSITY)
                state.setWidthForStyle(GlowStyle.SHARP_NEON, EXPECTED_SHARP_NEON_WIDTH)
                state.glowAnimation = GlowAnimation.BREATHE.name
            }

        assertTrue(reloaded.state.glowEnabled)
        assertEquals(GlowStyle.SHARP_NEON.name, reloaded.state.glowStyle)
        assertEquals(
            EXPECTED_SHARP_NEON_INTENSITY,
            reloaded.state.getIntensityForStyle(GlowStyle.SHARP_NEON),
        )
        assertEquals(
            EXPECTED_SHARP_NEON_WIDTH,
            reloaded.state.getWidthForStyle(GlowStyle.SHARP_NEON),
        )
        assertEquals(GlowAnimation.BREATHE.name, reloaded.state.glowAnimation)
    }

    @Test
    fun `onboarding flags survive save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.freeOnboardingShown = true
                state.premiumOnboardingShown = true
                state.lastSeenVersion = "2.4.1"
            }

        assertTrue(reloaded.state.freeOnboardingShown)
        assertTrue(reloaded.state.premiumOnboardingShown)
        assertEquals("2.4.1", reloaded.state.lastSeenVersion)
    }

    @Test
    fun `installed fonts set survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.installedFonts.add("Maple Mono")
                state.installedFonts.add("JetBrains Mono")
                state.installedFonts.add("Fira Code")
            }

        assertEquals(3, reloaded.state.installedFonts.size)
        assertTrue(reloaded.state.installedFonts.contains("Maple Mono"))
        assertTrue(reloaded.state.installedFonts.contains("JetBrains Mono"))
        assertTrue(reloaded.state.installedFonts.contains("Fira Code"))
    }

    @Test
    fun `panel width modes survive save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.projectPanelWidthMode = PanelWidthMode.AUTO_FIT.name
                state.commitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
                state.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
            }

        assertEquals(PanelWidthMode.AUTO_FIT.name, reloaded.state.projectPanelWidthMode)
        assertEquals(PanelWidthMode.AUTO_FIT.name, reloaded.state.commitPanelWidthMode)
        assertEquals(PanelWidthMode.AUTO_FIT.name, reloaded.state.gitPanelWidthMode)
    }

    @Test
    fun `font preset customizations map survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.fontPresetCustomizations["AMBIENT"] = "14|1.2|true|400"
                state.fontPresetCustomizations["WHISPER"] = "13|1.1|false|300"
            }

        assertEquals(2, reloaded.state.fontPresetCustomizations.size)
        assertEquals("14|1.2|true|400", reloaded.state.fontPresetCustomizations["AMBIENT"])
        assertEquals("13|1.1|false|300", reloaded.state.fontPresetCustomizations["WHISPER"])
    }

    @Test
    fun `trial and license state survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.trialExpiredNotified = true
                state.trialExpiryWarningShown = true
                state.everBeenPro = true
                state.lastKnownLicensedMs = EXPECTED_LICENSED_MS
            }

        assertTrue(reloaded.state.trialExpiredNotified)
        assertTrue(reloaded.state.trialExpiryWarningShown)
        assertTrue(reloaded.state.everBeenPro)
        assertEquals(EXPECTED_LICENSED_MS, reloaded.state.lastKnownLicensedMs)
    }

    @Test
    fun `installedFontFiles defaults to empty map`() {
        val settings = AyuIslandsSettings()
        assertTrue(settings.state.installedFontFiles.isEmpty())
    }

    @Test
    fun `installedFontFiles survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.installedFontFiles["Maple Mono"] =
                    "/Users/test/Library/Fonts/MapleMono-Regular.ttf\n" +
                    "/Users/test/Library/Fonts/MapleMono-Italic.ttf"
                state.installedFontFiles["Victor Mono"] =
                    "/Users/test/Library/Fonts/VictorMono-Light.ttf"
            }

        assertEquals(
            "/Users/test/Library/Fonts/MapleMono-Regular.ttf\n" +
                "/Users/test/Library/Fonts/MapleMono-Italic.ttf",
            reloaded.state.installedFontFiles["Maple Mono"],
        )
        assertEquals(
            "/Users/test/Library/Fonts/VictorMono-Light.ttf",
            reloaded.state.installedFontFiles["Victor Mono"],
        )
    }

    @Test
    fun `installedFontFiles entries can be removed across reload`() {
        val reloaded =
            roundTrip { state ->
                state.installedFontFiles["Maple Mono"] = "/a/MapleMono-Regular.ttf"
                state.installedFontFiles["Victor Mono"] = "/b/VictorMono-Light.ttf"
                state.installedFontFiles.remove("Victor Mono")
            }

        assertEquals("/a/MapleMono-Regular.ttf", reloaded.state.installedFontFiles["Maple Mono"])
        assertFalse(reloaded.state.installedFontFiles.containsKey("Victor Mono"))
    }

    @Test
    fun `explicitlyUninstalledFonts defaults to empty set`() {
        val settings = AyuIslandsSettings()
        assertTrue(settings.state.explicitlyUninstalledFonts.isEmpty())
    }

    @Test
    fun `explicitlyUninstalledFonts survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.explicitlyUninstalledFonts.add("Monaspace Neon")
                state.explicitlyUninstalledFonts.add("Victor Mono")
            }

        assertTrue(reloaded.state.explicitlyUninstalledFonts.contains("Monaspace Neon"))
        assertTrue(reloaded.state.explicitlyUninstalledFonts.contains("Victor Mono"))
        assertEquals(2, reloaded.state.explicitlyUninstalledFonts.size)
    }

    // ---- encodeFontPaths / decodeFontPaths helpers ----

    @Test
    fun `encodeFontPaths joins absolute paths with newline`() {
        val files = listOf(File("/a/Regular.ttf"), File("/b/Italic.ttf"))
        assertEquals("/a/Regular.ttf\n/b/Italic.ttf", AyuIslandsState.encodeFontPaths(files))
    }

    @Test
    fun `encodeFontPaths returns empty string for empty list`() {
        assertEquals("", AyuIslandsState.encodeFontPaths(emptyList()))
    }

    @Test
    fun `decodeFontPaths returns empty list for null`() {
        assertTrue(AyuIslandsState.decodeFontPaths(null).isEmpty())
    }

    @Test
    fun `decodeFontPaths returns empty list for blank string`() {
        assertTrue(AyuIslandsState.decodeFontPaths("").isEmpty())
        assertTrue(AyuIslandsState.decodeFontPaths("  ").isEmpty())
    }

    @Test
    fun `decodeFontPaths skips blank lines in middle`() {
        val result = AyuIslandsState.decodeFontPaths("/a/Regular.ttf\n\n/b/Italic.ttf")
        assertEquals(listOf("/a/Regular.ttf", "/b/Italic.ttf"), result)
    }

    @Test
    fun `encodeFontPaths and decodeFontPaths round-trip`() {
        val files = listOf(File("/Users/test/Library/Fonts/Mono.ttf"), File("/tmp/Bold.ttf"))
        val encoded = AyuIslandsState.encodeFontPaths(files)
        val decoded = AyuIslandsState.decodeFontPaths(encoded)
        assertEquals(files.map { it.absolutePath }, decoded)
    }

    companion object {
        private const val EXPECTED_SHARP_NEON_INTENSITY = 80
        private const val EXPECTED_SHARP_NEON_WIDTH = 6
        private const val EXPECTED_LICENSED_MS = 1_700_000_000_000L
    }
}
