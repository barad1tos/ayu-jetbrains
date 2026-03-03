package dev.ayuislands.settings

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.glow.GlowStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AyuIslandsStateTest {
    private fun freshState(): AyuIslandsState = AyuIslandsState()

    @Test
    fun `isToggleEnabled returns true by default for all elements`() {
        val state = freshState()
        for (id in AccentElementId.entries) {
            assertTrue(state.isToggleEnabled(id), "${id.name} should be enabled by default")
        }
    }

    @Test
    fun `setToggle and isToggleEnabled round-trip`() {
        val state = freshState()
        for (id in AccentElementId.entries) {
            state.setToggle(id, false)
            assertFalse(state.isToggleEnabled(id), "${id.name} should be disabled after set(false)")
            state.setToggle(id, true)
            assertTrue(state.isToggleEnabled(id), "${id.name} should be enabled after set(true)")
        }
    }

    @Test
    fun `getIntensityForStyle returns correct per-style defaults`() {
        val state = freshState()
        assertEquals(35, state.getIntensityForStyle(GlowStyle.SOFT))
        assertEquals(65, state.getIntensityForStyle(GlowStyle.SHARP_NEON))
        assertEquals(45, state.getIntensityForStyle(GlowStyle.GRADIENT))
    }

    @Test
    fun `setIntensityForStyle and getIntensityForStyle round-trip`() {
        val state = freshState()
        for (style in GlowStyle.entries) {
            state.setIntensityForStyle(style, 77)
            assertEquals(77, state.getIntensityForStyle(style))
        }
    }

    @Test
    fun `getWidthForStyle returns correct per-style defaults`() {
        val state = freshState()
        assertEquals(8, state.getWidthForStyle(GlowStyle.SOFT))
        assertEquals(8, state.getWidthForStyle(GlowStyle.SHARP_NEON))
        assertEquals(10, state.getWidthForStyle(GlowStyle.GRADIENT))
    }

    @Test
    fun `setWidthForStyle and getWidthForStyle round-trip`() {
        val state = freshState()
        for (style in GlowStyle.entries) {
            state.setWidthForStyle(style, 25)
            assertEquals(25, state.getWidthForStyle(style))
        }
    }

    @Test
    fun `settingsSelectedTab defaults to 0`() {
        val state = freshState()
        assertEquals(0, state.settingsSelectedTab)
    }

    @Test
    fun `settingsSelectedTab round-trips`() {
        val state = freshState()
        state.settingsSelectedTab = 1
        assertEquals(1, state.settingsSelectedTab)
    }

    @Test
    fun `isIslandEnabled maps Git aliases to same property`() {
        val state = freshState()
        state.glowGit = true
        assertTrue(state.isIslandEnabled("Git"))
        assertTrue(state.isIslandEnabled("Version Control"))
        assertTrue(state.isIslandEnabled("Commit"))

        state.glowGit = false
        assertFalse(state.isIslandEnabled("Git"))
        assertFalse(state.isIslandEnabled("Version Control"))
        assertFalse(state.isIslandEnabled("Commit"))
    }

    @Test
    fun `isIslandEnabled returns glowEnabled for unknown tool window IDs`() {
        val state = freshState()
        state.glowEnabled = false
        assertFalse(state.isIslandEnabled("SomeUnknownToolWindow"))

        state.glowEnabled = true
        assertTrue(state.isIslandEnabled("SomeUnknownToolWindow"))
    }

    @Test
    fun `isIslandEnabled maps known IDs correctly`() {
        val state = freshState()
        val idToSetter: Map<String, (Boolean) -> Unit> =
            mapOf(
                "Editor" to { v -> state.glowEditor = v },
                "Project" to { v -> state.glowProject = v },
                "Terminal" to { v -> state.glowTerminal = v },
                "Run" to { v -> state.glowRun = v },
                "Debug" to { v -> state.glowDebug = v },
                "Services" to { v -> state.glowServices = v },
            )

        for ((id, setter) in idToSetter) {
            setter(true)
            assertTrue(state.isIslandEnabled(id), "$id should be enabled")
            setter(false)
            assertFalse(state.isIslandEnabled(id), "$id should be disabled")
        }
    }
}
