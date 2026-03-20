package dev.ayuislands.settings

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
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
        assertEquals(20, state.getIntensityForStyle(GlowStyle.SOFT))
        assertEquals(50, state.getIntensityForStyle(GlowStyle.SHARP_NEON))
        assertEquals(30, state.getIntensityForStyle(GlowStyle.GRADIENT))
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
        assertEquals(4, state.getWidthForStyle(GlowStyle.SOFT))
        assertEquals(4, state.getWidthForStyle(GlowStyle.SHARP_NEON))
        assertEquals(6, state.getWidthForStyle(GlowStyle.GRADIENT))
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
    fun `isIslandEnabled returns false for unknown tool window IDs`() {
        val state = freshState()
        state.glowEnabled = true
        assertFalse(state.isIslandEnabled("SomeUnknownToolWindow"))
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

    // Accent defaults

    @Test
    fun `default accent colors match variant specifications`() {
        val state = freshState()
        assertEquals("#FFCC66", state.mirageAccent)
        assertEquals("#E6B450", state.darkAccent)
        assertEquals("#F29718", state.lightAccent)
    }

    @Test
    fun `accent color round-trips per variant`() {
        val state = freshState()
        state.mirageAccent = "#FF0000"
        state.darkAccent = "#00FF00"
        state.lightAccent = "#0000FF"
        assertEquals("#FF0000", state.mirageAccent)
        assertEquals("#00FF00", state.darkAccent)
        assertEquals("#0000FF", state.lightAccent)
    }

    // Glow defaults

    @Test
    fun `glow is disabled by default`() {
        val state = freshState()
        assertFalse(state.glowEnabled)
    }

    @Test
    fun `glow preset defaults to WHISPER`() {
        val state = freshState()
        assertEquals(GlowPreset.WHISPER.name, state.glowPreset)
    }

    @Test
    fun `glow style defaults to SOFT`() {
        val state = freshState()
        assertEquals(GlowStyle.SOFT.name, state.glowStyle)
    }

    @Test
    fun `glow animation defaults to NONE`() {
        val state = freshState()
        assertEquals(GlowAnimation.NONE.name, state.glowAnimation)
    }

    @Test
    fun `glow tab mode defaults to MINIMAL`() {
        val state = freshState()
        assertEquals("MINIMAL", state.glowTabMode)
    }

    @Test
    fun `all island toggles are on by default`() {
        val state = freshState()
        assertTrue(state.glowEditor)
        assertTrue(state.glowProject)
        assertTrue(state.glowTerminal)
        assertTrue(state.glowRun)
        assertTrue(state.glowDebug)
        assertTrue(state.glowGit)
        assertTrue(state.glowServices)
    }

    @Test
    fun `glow focus ring is on by default`() {
        val state = freshState()
        assertTrue(state.glowFocusRing)
    }

    @Test
    fun `cgp integration is off by default`() {
        val state = freshState()
        assertFalse(state.cgpIntegrationEnabled)
    }

    // setIslandEnabled round-trip

    @Test
    fun `setIslandEnabled and isIslandEnabled round-trip`() {
        val state = freshState()
        val ids = listOf("Editor", "Project", "Terminal", "Run", "Debug", "Git", "Services")
        for (id in ids) {
            state.setIslandEnabled(id, true)
            assertTrue(state.isIslandEnabled(id), "$id should be enabled after set(true)")
            state.setIslandEnabled(id, false)
            assertFalse(state.isIslandEnabled(id), "$id should be disabled after set(false)")
        }
    }

    @Test
    fun `setIslandEnabled with Git aliases maps to same property`() {
        val state = freshState()
        state.setIslandEnabled("Version Control", true)
        assertTrue(state.isIslandEnabled("Git"))
        assertTrue(state.isIslandEnabled("Commit"))

        state.setIslandEnabled("Commit", false)
        assertFalse(state.isIslandEnabled("Git"))
        assertFalse(state.isIslandEnabled("Version Control"))
    }

    // Force overrides

    @Test
    fun `forceOverrides is empty by default`() {
        val state = freshState()
        assertTrue(state.forceOverrides.isEmpty())
    }

    @Test
    fun `trial and pro defaults flags are false by default`() {
        val state = freshState()
        assertFalse(state.trialExpiredNotified)
        assertFalse(state.proDefaultsApplied)
    }

    // migrateWidthModes (BAR-142)

    @Test
    fun `migrateWidthModes converts legacy autoFitProjectPanelWidth to AUTO_FIT mode`() {
        val state = freshState()
        state.autoFitProjectPanelWidth = true
        state.projectPanelWidthMode = PanelWidthMode.DEFAULT.name

        state.migrateWidthModes()

        assertEquals(PanelWidthMode.AUTO_FIT.name, state.projectPanelWidthMode)
    }

    @Test
    fun `migrateWidthModes converts legacy autoFitCommitPanelWidth to AUTO_FIT mode`() {
        val state = freshState()
        state.autoFitCommitPanelWidth = true
        state.commitPanelWidthMode = PanelWidthMode.DEFAULT.name

        state.migrateWidthModes()

        assertEquals(PanelWidthMode.AUTO_FIT.name, state.commitPanelWidthMode)
    }

    @Test
    fun `migrateWidthModes skips project when mode already set to non-DEFAULT`() {
        val state = freshState()
        state.autoFitProjectPanelWidth = true
        state.projectPanelWidthMode = PanelWidthMode.FIXED.name

        state.migrateWidthModes()

        assertEquals(PanelWidthMode.FIXED.name, state.projectPanelWidthMode)
    }

    @Test
    fun `migrateWidthModes skips when legacy flags are false`() {
        val state = freshState()
        state.autoFitProjectPanelWidth = false
        state.autoFitCommitPanelWidth = false
        state.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.commitPanelWidthMode = PanelWidthMode.DEFAULT.name

        state.migrateWidthModes()

        assertEquals(PanelWidthMode.DEFAULT.name, state.projectPanelWidthMode)
        assertEquals(PanelWidthMode.DEFAULT.name, state.commitPanelWidthMode)
    }

    @Test
    fun `migrateWidthModes handles both panels simultaneously`() {
        val state = freshState()
        state.autoFitProjectPanelWidth = true
        state.autoFitCommitPanelWidth = true
        state.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        state.commitPanelWidthMode = PanelWidthMode.DEFAULT.name

        state.migrateWidthModes()

        assertEquals(PanelWidthMode.AUTO_FIT.name, state.projectPanelWidthMode)
        assertEquals(PanelWidthMode.AUTO_FIT.name, state.commitPanelWidthMode)
    }

    @Test
    fun `PanelWidthMode fromString parses valid names`() {
        assertEquals(PanelWidthMode.DEFAULT, PanelWidthMode.fromString("DEFAULT"))
        assertEquals(PanelWidthMode.AUTO_FIT, PanelWidthMode.fromString("AUTO_FIT"))
        assertEquals(PanelWidthMode.FIXED, PanelWidthMode.fromString("FIXED"))
    }

    @Test
    fun `PanelWidthMode fromString returns DEFAULT for null`() {
        assertEquals(PanelWidthMode.DEFAULT, PanelWidthMode.fromString(null))
    }

    @Test
    fun `PanelWidthMode fromString returns DEFAULT for unknown`() {
        assertEquals(PanelWidthMode.DEFAULT, PanelWidthMode.fromString("NONEXISTENT"))
    }
}
