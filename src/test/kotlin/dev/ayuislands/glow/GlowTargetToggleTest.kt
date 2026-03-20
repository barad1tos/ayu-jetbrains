package dev.ayuislands.glow

import dev.ayuislands.settings.AyuIslandsState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlowTargetToggleTest {
    private val allIslandIds = listOf("Editor", "Project", "Terminal", "Run", "Debug", "Git", "Services")

    private fun freshState(): AyuIslandsState = AyuIslandsState()

    // BAR-143 regression #4: default trial state has all targets ON

    @Test
    fun `all glow targets are ON by default for fresh state`() {
        val state = freshState()
        for (id in allIslandIds) {
            assertTrue(state.isIslandEnabled(id), "$id should be enabled by default")
        }
        assertTrue(state.glowFocusRing, "Focus ring should be enabled by default")
    }

    // Enable all / Disable all

    @Test
    fun `enable all sets every island to true`() {
        val state = freshState()
        for (id in allIslandIds) {
            state.setIslandEnabled(id, false)
        }

        for (id in allIslandIds) {
            state.setIslandEnabled(id, true)
        }

        for (id in allIslandIds) {
            assertTrue(state.isIslandEnabled(id), "$id should be enabled after enable-all")
        }
    }

    @Test
    fun `disable all sets every island to false`() {
        val state = freshState()
        for (id in allIslandIds) {
            assertTrue(state.isIslandEnabled(id), "$id should start enabled")
        }

        for (id in allIslandIds) {
            state.setIslandEnabled(id, false)
        }

        for (id in allIslandIds) {
            assertFalse(state.isIslandEnabled(id), "$id should be disabled after disable-all")
        }
    }

    @Test
    fun `enable all works regardless of glow preset`() {
        val state = freshState()
        for (presetName in GlowPreset.entries.map { it.name }) {
            state.glowPreset = presetName

            for (id in allIslandIds) {
                state.setIslandEnabled(id, false)
            }
            for (id in allIslandIds) {
                state.setIslandEnabled(id, true)
            }

            for (id in allIslandIds) {
                assertTrue(
                    state.isIslandEnabled(id),
                    "$id should be enabled with preset $presetName",
                )
            }
        }
    }

    @Test
    fun `disabling individual target preserves others`() {
        val state = freshState()

        state.setIslandEnabled("Terminal", false)

        assertTrue(state.isIslandEnabled("Editor"))
        assertTrue(state.isIslandEnabled("Project"))
        assertFalse(state.isIslandEnabled("Terminal"))
        assertTrue(state.isIslandEnabled("Run"))
        assertTrue(state.isIslandEnabled("Debug"))
        assertTrue(state.isIslandEnabled("Git"))
        assertTrue(state.isIslandEnabled("Services"))
    }

    @Test
    fun `glow targets are independent of glowEnabled flag`() {
        val state = freshState()
        state.glowEnabled = false

        for (id in allIslandIds) {
            assertTrue(state.isIslandEnabled(id), "$id should be true even when glow is disabled")
        }
    }

    @Test
    fun `Git alias IDs all affect the same glow property`() {
        val state = freshState()
        val gitAliases = listOf("Git", "Version Control", "Commit")

        state.setIslandEnabled("Git", false)
        for (alias in gitAliases) {
            assertFalse(state.isIslandEnabled(alias), "$alias should be false after disabling Git")
        }

        state.setIslandEnabled("Version Control", true)
        for (alias in gitAliases) {
            assertTrue(state.isIslandEnabled(alias), "$alias should be true after enabling via Version Control")
        }
    }
}
