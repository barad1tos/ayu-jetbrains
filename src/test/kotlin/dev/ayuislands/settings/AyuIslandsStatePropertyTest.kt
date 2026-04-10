package dev.ayuislands.settings

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AyuIslandsStatePropertyTest {
    @Test
    fun `all accent element toggles default to true`() {
        val state = AyuIslandsState()
        for (element in AccentElementId.entries) {
            assertTrue(
                state.isToggleEnabled(element),
                "Default toggle for ${element.name} must be true",
            )
        }
    }

    @Test
    fun `setToggle then isToggleEnabled round-trips for every element`() {
        val state = AyuIslandsState()
        for (element in AccentElementId.entries) {
            state.setToggle(element, false)
            assertFalse(
                state.isToggleEnabled(element),
                "After setToggle(false), ${element.name} must be false",
            )
            state.setToggle(element, true)
            assertTrue(
                state.isToggleEnabled(element),
                "After setToggle(true), ${element.name} must be true",
            )
        }
    }

    @Test
    fun `glow intensity defaults match per-style expectations`() {
        val state = AyuIslandsState()
        for (style in GlowStyle.entries) {
            val intensity = state.getIntensityForStyle(style)
            assertTrue(
                intensity in 0..100,
                "Default intensity for ${style.name} must be in [0,100], got $intensity",
            )
        }
    }

    @Test
    fun `setIntensityForStyle round-trips for every style`() {
        val state = AyuIslandsState()
        for (style in GlowStyle.entries) {
            state.setIntensityForStyle(style, 42)
            assertEquals(
                42,
                state.getIntensityForStyle(style),
                "After setIntensityForStyle(42), ${style.name} must return 42",
            )
        }
    }

    @Test
    fun `glow width defaults are within sane range for every style`() {
        val state = AyuIslandsState()
        for (style in GlowStyle.entries) {
            val width = state.getWidthForStyle(style)
            assertTrue(
                width in 1..32,
                "Default width for ${style.name} must be in [1,32], got $width",
            )
        }
    }

    @Test
    fun `setWidthForStyle round-trips for every style`() {
        val state = AyuIslandsState()
        for (style in GlowStyle.entries) {
            state.setWidthForStyle(style, 16)
            assertEquals(
                16,
                state.getWidthForStyle(style),
                "After setWidthForStyle(16), ${style.name} must return 16",
            )
        }
    }

    @Test
    fun `default glow preset is WHISPER`() {
        val state = AyuIslandsState()
        assertEquals(GlowPreset.WHISPER.name, state.glowPreset)
    }

    @Test
    fun `default glow style is SOFT`() {
        val state = AyuIslandsState()
        assertEquals(GlowStyle.SOFT.name, state.glowStyle)
    }

    @Test
    fun `default glow animation is NONE`() {
        val state = AyuIslandsState()
        assertEquals(GlowAnimation.NONE.name, state.glowAnimation)
    }

    @Test
    fun `glow is disabled by default`() {
        val state = AyuIslandsState()
        assertFalse(state.glowEnabled)
    }

    @Test
    fun `all island toggles default to true`() {
        val state = AyuIslandsState()
        val islands =
            listOf(
                "Editor" to state.glowEditor,
                "Project" to state.glowProject,
                "Terminal" to state.glowTerminal,
                "Run" to state.glowRun,
                "Debug" to state.glowDebug,
                "Git" to state.glowGit,
                "Services" to state.glowServices,
            )
        for ((name, value) in islands) {
            assertTrue(value, "Island toggle '$name' must default to true")
        }
    }

    @Test
    fun `isIslandEnabled returns false for unknown tool window IDs`() {
        val state = AyuIslandsState()
        assertFalse(
            state.isIslandEnabled("NonExistent"),
            "Unknown tool window must return false",
        )
    }

    @Test
    fun `boolean flags default to false`() {
        val state = AyuIslandsState()
        val flags =
            listOf(
                "followSystemAccent" to state.followSystemAccent,
                "followSystemAppearance" to state.followSystemAppearance,
                "trialExpiredNotified" to state.trialExpiredNotified,
                "proDefaultsApplied" to state.proDefaultsApplied,
                "everBeenPro" to state.everBeenPro,
                "freeOnboardingShown" to state.freeOnboardingShown,
                "premiumOnboardingShown" to state.premiumOnboardingShown,
                "accentRotationEnabled" to state.accentRotationEnabled,
                "fontPresetEnabled" to state.fontPresetEnabled,
                "cgpIntegrationEnabled" to state.cgpIntegrationEnabled,
                "irIntegrationEnabled" to state.irIntegrationEnabled,
            )
        for ((name, value) in flags) {
            assertFalse(value, "Flag '$name' must default to false")
        }
    }
}
