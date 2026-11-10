package dev.ayuislands.settings

import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformMovement
import org.jdom.Element
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Legacy XML import and accepted Studio persistence retain user choices across reload. */
class AyuIslandsStatePersistenceTest {
    private fun roundTrip(mutate: (AyuIslandsState) -> Unit): dev.ayuislands.studio.persistence.AyuSettingsView =
        dev.ayuislands.studio.StudioFixtures
            .persistedView(freshSettingsState().apply(mutate))
            .settings

    @Test
    fun `legacy XML keeps native override enabled and opt out round trips raw choices`() {
        val legacy = XmlSerializer.deserialize(Element("state"), AyuIslandsState::class.java)
        assertTrue(legacy.externalThemeAccentOverrideEnabled)
        legacy.externalThemeAccentOverrideEnabled = false
        legacy.externalThemeAccentSource = "FUTURE_SOURCE"
        legacy.externalThemeAccent = "future-color"
        legacy.forceOverrides.addAll(listOf("FUTURE_SURFACE", "PANEL_BORDER"))

        val reloaded = XmlSerializer.deserialize(XmlSerializer.serialize(legacy), AyuIslandsState::class.java)

        assertFalse(reloaded.externalThemeAccentOverrideEnabled)
        assertEquals("FUTURE_SOURCE", reloaded.externalThemeAccentSource)
        assertEquals("future-color", reloaded.externalThemeAccent)
        assertEquals(legacy.forceOverrides.toList(), reloaded.forceOverrides.toList())
    }

    @Test
    fun `native accent opt out survives reload without normalizing future choices`() {
        assertTrue(AyuIslandsState().externalThemeAccentOverrideEnabled)
        val reloaded =
            roundTrip { state ->
                state.externalThemeEnhancementsEnabled = true
                state.externalThemeAccentOverrideEnabled = false
                state.externalThemeAccentSource = "FUTURE_SOURCE"
                state.externalThemeAccent = "future-color"
                state.externalThemeGlowEnabled = true
                state.externalThemeChromeTintEnabled = true
            }
        assertFalse(reloaded.externalThemeAccentOverrideEnabled)
        assertEquals("FUTURE_SOURCE", reloaded.externalThemeAccentSource)
        assertEquals("future-color", reloaded.externalThemeAccent)
        assertTrue(reloaded.externalThemeGlowEnabled)
        assertTrue(reloaded.externalThemeChromeTintEnabled)
    }

    @Test
    fun `accent colors survive save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.mirageAccent = "#AA0000"
                state.darkAccent = "#00BB00"
                state.lightAccent = "#0000CC"
            }

        assertEquals("#AA0000", reloaded.mirageAccent)
        assertEquals("#00BB00", reloaded.darkAccent)
        assertEquals("#0000CC", reloaded.lightAccent)
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
                reloaded.isToggleEnabled(element),
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

        assertTrue(reloaded.glowEnabled)
        assertEquals(GlowStyle.SHARP_NEON.name, reloaded.glowStyle)
        assertEquals(
            EXPECTED_SHARP_NEON_INTENSITY,
            reloaded.getIntensityForStyle(GlowStyle.SHARP_NEON),
        )
        assertEquals(
            EXPECTED_SHARP_NEON_WIDTH,
            reloaded.getWidthForStyle(GlowStyle.SHARP_NEON),
        )
        assertEquals(GlowAnimation.BREATHE.name, reloaded.glowAnimation)
    }

    @Test
    fun `waveform settings survive save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.glowShape = GlowShape.WAVEFORM.name
                state.waveformDirection = WaveformMovement.COUNTER_CLOCKWISE.name
                state.waveformBaseline = WaveformBaseline.CENTERED.name
                state.waveformTraceDensity = 4
                state.waveformTraceLength = 640
                state.waveformAmplitude = 36
                state.waveformIntensity = 150
                state.waveformLoopSeconds = 3.7f
            }

        assertEquals(GlowShape.WAVEFORM.name, reloaded.glowShape)
        assertEquals(WaveformMovement.COUNTER_CLOCKWISE.name, reloaded.waveformDirection)
        assertEquals(WaveformBaseline.CENTERED.name, reloaded.waveformBaseline)
        assertEquals(4, reloaded.waveformTraceDensity)
        assertEquals(640, reloaded.waveformTraceLength)
        assertEquals(36, reloaded.waveformAmplitude)
        assertEquals(150, reloaded.waveformIntensity)
        assertEquals(3.7f, reloaded.waveformLoopSeconds)
    }

    @Test
    fun `chaotic movement round trips through legacy waveformDirection key`() {
        val reloaded =
            roundTrip { state ->
                state.waveformDirection = WaveformMovement.CHAOTIC.name
            }

        assertEquals(WaveformMovement.CHAOTIC.name, reloaded.waveformDirection)
    }

    @Test
    fun `onboarding flags survive save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.freeOnboardingShown = true
                state.premiumOnboardingShown = true
                state.lastSeenVersion = "2.4.1"
            }

        assertTrue(reloaded.freeOnboardingShown)
        assertTrue(reloaded.premiumOnboardingShown)
        assertEquals("2.4.1", reloaded.lastSeenVersion)
    }

    @Test
    fun `settings badge deadlines survive save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.settingsBadgeDeadlines["glow-waveform"] = "123456"
                state.settingsBadgeDeadlines["accent-from-project-icon"] = "654321"
                state.settingsBadgesExpireAtMs = 111111L
            }

        assertEquals(
            mapOf(
                "glow-waveform" to "123456",
                "accent-from-project-icon" to "654321",
            ),
            reloaded.settingsBadgeDeadlines,
        )
        assertEquals(111111L, reloaded.settingsBadgesExpireAtMs)
    }

    @Test
    fun `installed fonts set survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.installedFonts.add("Maple Mono")
                state.installedFonts.add("JetBrains Mono")
                state.installedFonts.add("Fira Code")
            }

        assertEquals(3, reloaded.installedFonts.size)
        assertTrue(reloaded.installedFonts.contains("Maple Mono"))
        assertTrue(reloaded.installedFonts.contains("JetBrains Mono"))
        assertTrue(reloaded.installedFonts.contains("Fira Code"))
    }

    @Test
    fun `panel width modes survive save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.projectPanelWidthMode = PanelWidthMode.AUTO_FIT.name
                state.commitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
                state.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
            }

        assertEquals(PanelWidthMode.AUTO_FIT.name, reloaded.projectPanelWidthMode)
        assertEquals(PanelWidthMode.AUTO_FIT.name, reloaded.commitPanelWidthMode)
        assertEquals(PanelWidthMode.AUTO_FIT.name, reloaded.gitPanelWidthMode)
    }

    @Test
    fun `commit path shortening settings survive save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.commitPanelPathDisplayMode = CommitPathDisplayMode.TOOLTIP.name
                state.commitPanelPathMinHiddenLevels = 2
                state.commitPanelPathMaxHiddenLevels = 6
            }

        assertEquals(CommitPathDisplayMode.TOOLTIP.name, reloaded.commitPanelPathDisplayMode)
        assertEquals(2, reloaded.commitPanelPathMinHiddenLevels)
        assertEquals(6, reloaded.commitPanelPathMaxHiddenLevels)
    }

    @Test
    fun `font preset customizations map survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.fontPresetCustomizations["AMBIENT"] = "14|1.2|true|400"
                state.fontPresetCustomizations["WHISPER"] = "13|1.1|false|300"
            }

        assertEquals(2, reloaded.fontPresetCustomizations.size)
        assertEquals("14|1.2|true|400", reloaded.fontPresetCustomizations["AMBIENT"])
        assertEquals("13|1.1|false|300", reloaded.fontPresetCustomizations["WHISPER"])
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

        assertTrue(reloaded.trialExpiredNotified)
        assertTrue(reloaded.trialExpiryWarningShown)
        assertTrue(reloaded.everBeenPro)
        assertEquals(EXPECTED_LICENSED_MS, reloaded.lastKnownLicensedMs)
    }

    @Test
    fun `installedFontFiles defaults to empty map`() {
        val settings = freshSettingsState()
        assertTrue(settings.installedFontFiles.isEmpty())
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
            reloaded.installedFontFiles["Maple Mono"],
        )
        assertEquals(
            "/Users/test/Library/Fonts/VictorMono-Light.ttf",
            reloaded.installedFontFiles["Victor Mono"],
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

        assertEquals("/a/MapleMono-Regular.ttf", reloaded.installedFontFiles["Maple Mono"])
        assertFalse(reloaded.installedFontFiles.containsKey("Victor Mono"))
    }

    @Test
    fun `explicitlyUninstalledFonts defaults to empty set`() {
        val settings = freshSettingsState()
        assertTrue(settings.explicitlyUninstalledFonts.isEmpty())
    }

    @Test
    fun `explicitlyUninstalledFonts survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.explicitlyUninstalledFonts.add("Monaspace Neon")
                state.explicitlyUninstalledFonts.add("Victor Mono")
            }

        assertTrue(reloaded.explicitlyUninstalledFonts.contains("Monaspace Neon"))
        assertTrue(reloaded.explicitlyUninstalledFonts.contains("Victor Mono"))
        assertEquals(2, reloaded.explicitlyUninstalledFonts.size)
    }

    @Test
    fun `ignore plugin syntax color opt-out survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.ignorePluginSyntaxColorsEnabled = false
            }

        assertFalse(reloaded.ignorePluginSyntaxColorsEnabled)
    }

    // ---------- Chrome tinting (phase 40) XML round-trip for all 8 new fields ----------

    @Test
    fun `all five chrome-group toggles survive save reload cycle when enabled`() {
        // Drive every CHROME-group id through setToggle so the routing in
        // AyuIslandsState.setToggle is exercised during the mutation phase,
        // then verify each backing property persisted across the reload.
        val reloaded =
            roundTrip { state ->
                for (id in AccentElementId.entries.filter { it.group == AccentGroup.CHROME }) {
                    state.setToggle(id, true)
                }
            }

        assertTrue(reloaded.chromeStatusBar, "chromeStatusBar must persist")
        assertTrue(reloaded.chromeMainToolbar, "chromeMainToolbar must persist")
        assertTrue(reloaded.chromeToolWindowStripe, "chromeToolWindowStripe must persist")
        assertTrue(reloaded.chromeNavBar, "chromeNavBar must persist")
        assertTrue(reloaded.chromePanelBorder, "chromePanelBorder must persist")

        // isToggleEnabled must resolve through the reloaded state, not the original.
        for (id in AccentElementId.entries.filter { it.group == AccentGroup.CHROME }) {
            assertTrue(
                reloaded.isToggleEnabled(id),
                "${id.name} must remain enabled after reload",
            )
        }
    }

    @Test
    fun `chromeTintIntensity survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.chromeTintIntensity = EXPECTED_CHROME_TINT_INTENSITY
            }
        assertEquals(EXPECTED_CHROME_TINT_INTENSITY, reloaded.chromeTintIntensity)
    }

    @Test
    fun `chromeTintingGroupExpanded survives save reload cycle when expanded`() {
        val reloaded =
            roundTrip { state ->
                state.chromeTintingGroupExpanded = true
            }
        assertTrue(reloaded.chromeTintingGroupExpanded)
    }

    // ---- Startup-flicker anti-flash (phase 40) ----

    @Test
    fun `lastAppliedAccentHex defaults to null`() {
        val settings = freshSettingsState()
        assertEquals(null, settings.lastAppliedAccentHex)
    }

    @Test
    fun `lastAppliedAccentHex survives save reload cycle`() {
        val reloaded =
            roundTrip { state ->
                state.lastAppliedAccentHex = "#5CCFE6"
            }
        assertEquals("#5CCFE6", reloaded.lastAppliedAccentHex)
    }

    @Test
    fun `lastAppliedAccentHex last-write-wins across reloads`() {
        val reloaded =
            roundTrip { state ->
                state.lastAppliedAccentHex = "#5CCFE6"
                state.lastAppliedAccentHex = "#FF3333"
            }
        assertEquals("#FF3333", reloaded.lastAppliedAccentHex)
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
        private const val EXPECTED_CHROME_TINT_INTENSITY = 65
    }
}
