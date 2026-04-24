package dev.ayuislands.settings

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
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
    fun `isToggleEnabled defaults true for VISUAL INTERACTIVE and false for CHROME`() {
        // VISUAL/INTERACTIVE group elements default ON — part of the free accent surface.
        // CHROME group (phase 40) defaults OFF — premium, opt-in chrome tinting.
        val state = freshState()
        for (id in AccentElementId.entries.filter { it.group != AccentGroup.CHROME }) {
            assertTrue(state.isToggleEnabled(id), "${id.name} should be enabled by default")
        }
        for (id in AccentElementId.entries.filter { it.group == AccentGroup.CHROME }) {
            assertFalse(state.isToggleEnabled(id), "${id.name} should be disabled by default (premium opt-in)")
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

    // ---------- Chrome tinting (phase 40) — per-id branch coverage + defaults ----------

    @Test
    fun `setToggle routes STATUS_BAR to chromeStatusBar field`() {
        val state = freshState()
        assertFalse(state.chromeStatusBar, "precondition: chromeStatusBar defaults false")

        state.setToggle(AccentElementId.STATUS_BAR, true)
        assertTrue(state.chromeStatusBar, "setToggle(STATUS_BAR,true) must flip chromeStatusBar")
        assertTrue(state.isToggleEnabled(AccentElementId.STATUS_BAR))

        state.setToggle(AccentElementId.STATUS_BAR, false)
        assertFalse(state.chromeStatusBar)
        assertFalse(state.isToggleEnabled(AccentElementId.STATUS_BAR))
    }

    @Test
    fun `setToggle routes MAIN_TOOLBAR to chromeMainToolbar field`() {
        val state = freshState()
        state.setToggle(AccentElementId.MAIN_TOOLBAR, true)
        assertTrue(state.chromeMainToolbar)
        assertTrue(state.isToggleEnabled(AccentElementId.MAIN_TOOLBAR))

        state.setToggle(AccentElementId.MAIN_TOOLBAR, false)
        assertFalse(state.chromeMainToolbar)
    }

    @Test
    fun `setToggle routes TOOL_WINDOW_STRIPE to chromeToolWindowStripe field`() {
        val state = freshState()
        state.setToggle(AccentElementId.TOOL_WINDOW_STRIPE, true)
        assertTrue(state.chromeToolWindowStripe)
        assertTrue(state.isToggleEnabled(AccentElementId.TOOL_WINDOW_STRIPE))

        state.setToggle(AccentElementId.TOOL_WINDOW_STRIPE, false)
        assertFalse(state.chromeToolWindowStripe)
    }

    @Test
    fun `setToggle routes NAV_BAR to chromeNavBar field`() {
        val state = freshState()
        state.setToggle(AccentElementId.NAV_BAR, true)
        assertTrue(state.chromeNavBar)
        assertTrue(state.isToggleEnabled(AccentElementId.NAV_BAR))

        state.setToggle(AccentElementId.NAV_BAR, false)
        assertFalse(state.chromeNavBar)
    }

    @Test
    fun `setToggle routes PANEL_BORDER to chromePanelBorder field`() {
        val state = freshState()
        state.setToggle(AccentElementId.PANEL_BORDER, true)
        assertTrue(state.chromePanelBorder)
        assertTrue(state.isToggleEnabled(AccentElementId.PANEL_BORDER))

        state.setToggle(AccentElementId.PANEL_BORDER, false)
        assertFalse(state.chromePanelBorder)
    }

    @Test
    fun `chrome tinting auxiliary defaults`() {
        val state = freshState()
        assertEquals(
            AyuIslandsState.DEFAULT_CHROME_TINT_INTENSITY,
            state.chromeTintIntensity,
            "chromeTintIntensity must match DEFAULT_CHROME_TINT_INTENSITY",
        )
        assertFalse(
            state.chromeTintingGroupExpanded,
            "chromeTintingGroupExpanded defaults collapsed (same shape as accentElementsGroupExpanded)",
        )
    }

    @Test
    fun `chromeTintIntensity round-trips`() {
        val state = freshState()
        state.chromeTintIntensity = 0
        assertEquals(0, state.chromeTintIntensity)
        state.chromeTintIntensity = 55
        assertEquals(55, state.chromeTintIntensity)
        state.chromeTintIntensity = 100
        assertEquals(100, state.chromeTintIntensity)
    }

    @Test
    fun `effectiveChromeTintIntensity coerces corrupted persisted values to user-visible range`() {
        // Regression guard for PR #151 Round 1 Fix 2: a corrupted or legacy persisted
        // intensity (negative, above the user-visible slider cap) must never flow into
        // the HSB blender as-is. The raw BaseState `property(...)` delegate can't
        // validate at the setter boundary without breaking XML deserialization, so the
        // state class exposes an `effective*` helper that clamps at READ time. Every
        // chrome-element apply site reads through this helper, so garbage or legacy
        // values in the XML cannot desaturate / over-saturate chrome surfaces.
        //
        // The upper bound here is the USER-VISIBLE `MAX_CHROME_TINT_INTENSITY` (50),
        // not the blender's internal math clamp (0-100). Keeping state aligned with
        // the UI slider cap means pre-cap sessions that could save up to 100 see
        // the same ceiling every live user can reach, eliminating the "slider maxes
        // at 50 but chrome paints like 80" desync reported during runIde smoke.
        val cap = AyuIslandsState.MAX_CHROME_TINT_INTENSITY
        val state = freshState()

        state.chromeTintIntensity = -10
        assertEquals(0, state.effectiveChromeTintIntensity().percent, "negative values clamp to 0")

        state.chromeTintIntensity = 500
        assertEquals(
            cap,
            state.effectiveChromeTintIntensity().percent,
            "above-cap values clamp to the user-visible ceiling",
        )

        state.chromeTintIntensity = 80
        assertEquals(
            cap,
            state.effectiveChromeTintIntensity().percent,
            "legacy pre-cap persisted values observe the new ceiling",
        )

        state.chromeTintIntensity = 42
        assertEquals(42, state.effectiveChromeTintIntensity().percent, "in-range values pass through unchanged")

        state.chromeTintIntensity = 0
        assertEquals(0, state.effectiveChromeTintIntensity().percent)
        state.chromeTintIntensity = cap
        assertEquals(cap, state.effectiveChromeTintIntensity().percent)
    }

    @Test
    fun `chromeTintingGroupExpanded round-trips`() {
        val state = freshState()
        state.chromeTintingGroupExpanded = true
        assertTrue(state.chromeTintingGroupExpanded)
        state.chromeTintingGroupExpanded = false
        assertFalse(state.chromeTintingGroupExpanded)
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

    // Workspace defaults flags

    @Test
    fun `workspaceDefaultsApplied is false by default`() {
        val state = freshState()
        assertFalse(state.workspaceDefaultsApplied)
    }

    @Test
    fun `panel width modes default to DEFAULT`() {
        val state = freshState()
        assertEquals(
            PanelWidthMode.DEFAULT.name,
            state.projectPanelWidthMode,
        )
        assertEquals(
            PanelWidthMode.DEFAULT.name,
            state.commitPanelWidthMode,
        )
        assertEquals(
            PanelWidthMode.DEFAULT.name,
            state.gitPanelWidthMode,
        )
    }

    @Test
    fun `hideProjectRootPath and hideHScrollbar default to false`() {
        val state = freshState()
        assertFalse(state.hideProjectRootPath)
        assertFalse(state.hideProjectViewHScrollbar)
    }

    // Font preset defaults

    @Test
    fun `font preset defaults`() {
        val state = freshState()
        assertFalse(state.fontPresetEnabled)
        assertEquals("AMBIENT", state.fontPresetName)
        assertFalse(state.fontApplyToConsole)
    }

    // Integration defaults

    @Test
    fun `indent rainbow integration defaults`() {
        val state = freshState()
        assertFalse(state.irIntegrationEnabled)
        assertEquals("AMBIENT", state.indentPresetName)
        assertTrue(state.irErrorHighlightEnabled)
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

    @Test
    fun `lastWhatsNewShownVersion defaults to null and round-trips`() {
        // Generic per-version persistent gate for the What's New tab. Null
        // default = "never shown" so a fresh install with v2.5.0 (no record
        // yet) gets its tab. Round-trip the value to lock the persisted shape.
        val state = freshState()
        assertEquals(null, state.lastWhatsNewShownVersion, "default must be null")
        state.lastWhatsNewShownVersion = "2.5.0"
        assertEquals("2.5.0", state.lastWhatsNewShownVersion)
        state.lastWhatsNewShownVersion = "2.6.0"
        assertEquals("2.6.0", state.lastWhatsNewShownVersion, "field must accept version updates across releases")
        state.lastWhatsNewShownVersion = null
        assertEquals(null, state.lastWhatsNewShownVersion, "null assignment must clear the field")
    }
}
