package dev.ayuislands.settings

import com.intellij.openapi.components.BaseState
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowStyle

class AyuIslandsState : BaseState() {
    // Per-variant accent colors
    var mirageAccent by string("#FFCC66")
    var darkAccent by string("#E6B450")
    var lightAccent by string("#F29718")
    var trialExpiredNotified by property(false)

    // Per-element accent toggles (all ON by default)
    var tabUnderlines by property(true)
    var caretRow by property(true)
    var progressBar by property(true)
    var scrollbar by property(true)
    var links by property(true)
    var bracketMatch by property(true)
    var searchResults by property(true)
    var checkboxes by property(true)

    // Glow effect
    var glowEnabled by property(false)

    // Glow style
    var glowStyle by string(GlowStyle.SOFT.name)

    // Per-style intensity (0-100)
    var softIntensity by property(40)
    var sharpNeonIntensity by property(85)
    var gradientIntensity by property(50)

    // Per-style width (4-32)
    var softWidth by property(10)
    var sharpNeonWidth by property(20)
    var gradientWidth by property(12)

    // Animation
    var glowAnimation by string(GlowAnimation.NONE.name)

    // Per-island toggles (editor ON by default, others OFF)
    var glowEditor by property(true)
    var glowProject by property(false)
    var glowTerminal by property(false)
    var glowRun by property(false)
    var glowDebug by property(false)
    var glowGit by property(false)
    var glowServices by property(false)

    // User presets (serialized string)
    var glowUserPresets by string("")

    // Tab glow mode: UNDERLINE (underline only), FULL_BORDER (all sides), OFF
    var glowTabMode by string("UNDERLINE")

    // Focused input focus-ring glow (subtle, less intense than island glow)
    var glowFocusRing by property(true)

    // Floating panels — controls whether floating (undocked) tool windows get glow
    var glowFloatingPanels by property(false)

    // Onboarding
    var glowOnboardingShown by property(false)

    // CodeGlancePro integration (opt-in, default OFF)
    var cgpIntegrationEnabled by property(false)

    // Force overrides for conflicting elements (element ID names)
    var forceOverrides by stringSet()

    fun isToggleEnabled(id: AccentElementId): Boolean {
        return when (id) {
            AccentElementId.TAB_UNDERLINES -> tabUnderlines
            AccentElementId.CARET_ROW -> caretRow
            AccentElementId.PROGRESS_BAR -> progressBar
            AccentElementId.SCROLLBAR -> scrollbar
            AccentElementId.LINKS -> links
            AccentElementId.BRACKET_MATCH -> bracketMatch
            AccentElementId.SEARCH_RESULTS -> searchResults
            AccentElementId.CHECKBOXES -> checkboxes
        }
    }

    fun setToggle(id: AccentElementId, enabled: Boolean) {
        when (id) {
            AccentElementId.TAB_UNDERLINES -> tabUnderlines = enabled
            AccentElementId.CARET_ROW -> caretRow = enabled
            AccentElementId.PROGRESS_BAR -> progressBar = enabled
            AccentElementId.SCROLLBAR -> scrollbar = enabled
            AccentElementId.LINKS -> links = enabled
            AccentElementId.BRACKET_MATCH -> bracketMatch = enabled
            AccentElementId.SEARCH_RESULTS -> searchResults = enabled
            AccentElementId.CHECKBOXES -> checkboxes = enabled
        }
    }

    fun getIntensityForStyle(style: GlowStyle): Int = when (style) {
        GlowStyle.SOFT -> softIntensity
        GlowStyle.SHARP_NEON -> sharpNeonIntensity
        GlowStyle.GRADIENT -> gradientIntensity
    }

    fun setIntensityForStyle(style: GlowStyle, value: Int) {
        when (style) {
            GlowStyle.SOFT -> softIntensity = value
            GlowStyle.SHARP_NEON -> sharpNeonIntensity = value
            GlowStyle.GRADIENT -> gradientIntensity = value
        }
    }

    fun getWidthForStyle(style: GlowStyle): Int = when (style) {
        GlowStyle.SOFT -> softWidth
        GlowStyle.SHARP_NEON -> sharpNeonWidth
        GlowStyle.GRADIENT -> gradientWidth
    }

    fun setWidthForStyle(style: GlowStyle, value: Int) {
        when (style) {
            GlowStyle.SOFT -> softWidth = value
            GlowStyle.SHARP_NEON -> sharpNeonWidth = value
            GlowStyle.GRADIENT -> gradientWidth = value
        }
    }

    fun isIslandEnabled(toolWindowId: String): Boolean = when (toolWindowId) {
        "Editor" -> glowEditor
        "Project" -> glowProject
        "Terminal" -> glowTerminal
        "Run" -> glowRun
        "Debug" -> glowDebug
        "Git", "Version Control", "Commit" -> glowGit
        "Services" -> glowServices
        // Unknown tool windows inherit the global glow toggle
        else -> glowEnabled
    }

    fun setIslandEnabled(toolWindowId: String, enabled: Boolean) {
        when (toolWindowId) {
            "Editor" -> glowEditor = enabled
            "Project" -> glowProject = enabled
            "Terminal" -> glowTerminal = enabled
            "Run" -> glowRun = enabled
            "Debug" -> glowDebug = enabled
            "Git" -> glowGit = enabled
            "Services" -> glowServices = enabled
        }
    }
}
