package dev.ayuislands.settings

import com.intellij.openapi.components.BaseState
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.indent.IndentPreset

class AyuIslandsState : BaseState() {
    // Per-variant accent colors
    var mirageAccent by string("#FFCC66")
    var darkAccent by string("#E6B450")
    var lightAccent by string("#F29718")
    var followSystemAccent by property(false)
    var followSystemAppearance by property(false)
    var lastDarkAppearanceTheme by string("Ayu Mirage (Islands UI)")
    var lastLightAppearanceTheme by string("Ayu Light (Islands UI)")
    var trialExpiredNotified by property(false)
    var proDefaultsApplied by property(false)

    // Per-element accent toggles (all ON by default)
    var inlayHints by property(true)
    var caretRow by property(true)
    var progressBar by property(true)
    var scrollbar by property(true)
    var links by property(true)
    var bracketMatch by property(true)
    var searchResults by property(true)
    var matchingTag by property(true)

    // Glow effect
    var glowEnabled by property(false)

    // Glow preset (null = legacy state, needs migration via GlowPreset.detect())
    var glowPreset by string(GlowPreset.WHISPER.name)

    // Glow style
    var glowStyle by string(GlowStyle.SOFT.name)

    // Per-style intensity (0-100)
    var softIntensity by property(DEFAULT_SOFT_INTENSITY)
    var sharpNeonIntensity by property(DEFAULT_SHARP_NEON_INTENSITY)
    var gradientIntensity by property(DEFAULT_GRADIENT_INTENSITY)

    // Per-style width (4-32)
    var softWidth by property(DEFAULT_SOFT_WIDTH)
    var sharpNeonWidth by property(DEFAULT_SHARP_NEON_WIDTH)
    var gradientWidth by property(DEFAULT_GRADIENT_WIDTH)

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

    // Tab glow mode: MINIMAL (underline only), FULL (underline + tinted bg), OFF
    var glowTabMode by string("MINIMAL")

    // Tab underline height (pixels): 2, 4, 6, or 8
    var tabUnderlineHeight by property(DEFAULT_TAB_UNDERLINE_HEIGHT)

    // Sync underline height with glow width (1:1)
    var tabUnderlineGlowSync by property(false)

    // Focused input focus-ring glow (subtle, less intense than an island glow)
    var glowFocusRing by property(true)

    // CodeGlancePro integration (opt-in, default OFF)
    var cgpIntegrationEnabled by property(false)

    // Indent Rainbow integration (opt-in, default OFF)
    var irIntegrationEnabled by property(false)

    // Indent preset name (persisted)
    var indentPresetName by string("AMBIENT")

    // Custom alpha for CUSTOM preset (0-255)
    var indentCustomAlpha by property(IndentPreset.DEFAULT_ALPHA)

    // Bracket scope gutter highlight (default ON)
    var bracketScopeEnabled by property(true)

    // IR error highlight toggle (true = red error color, false = accent gradient)
    var irErrorHighlightEnabled by property(true)

    // IR version that failed reflection (suppresses repeated notifications)
    var irFailedVersion by string(null)

    // Font preset
    var fontPresetEnabled by property(false)
    var fontPresetName by string("AMBIENT")
    var fontApplyToConsole by property(false)
    var fontInstallTerminal by string("BUILTIN")

    // Per-preset custom settings: key = preset name, value = "size|spacing|ligatures|weight"
    var fontPresetCustomizations by map<String, String>()

    // Update notification (shown once per version upgrade)
    var lastSeenVersion by string(null)

    // Settings tab selection (persisted across settings opens)
    var settingsSelectedTab by property(0)

    // Force overrides for conflicting elements (element ID names)
    var forceOverrides by stringSet()

    fun isToggleEnabled(id: AccentElementId): Boolean =
        when (id) {
            AccentElementId.INLAY_HINTS -> inlayHints
            AccentElementId.CARET_ROW -> caretRow
            AccentElementId.PROGRESS_BAR -> progressBar
            AccentElementId.SCROLLBAR -> scrollbar
            AccentElementId.LINKS -> links
            AccentElementId.BRACKET_MATCH -> bracketMatch
            AccentElementId.SEARCH_RESULTS -> searchResults
            AccentElementId.MATCHING_TAG -> matchingTag
        }

    fun setToggle(
        id: AccentElementId,
        enabled: Boolean,
    ) {
        when (id) {
            AccentElementId.INLAY_HINTS -> inlayHints = enabled
            AccentElementId.CARET_ROW -> caretRow = enabled
            AccentElementId.PROGRESS_BAR -> progressBar = enabled
            AccentElementId.SCROLLBAR -> scrollbar = enabled
            AccentElementId.LINKS -> links = enabled
            AccentElementId.BRACKET_MATCH -> bracketMatch = enabled
            AccentElementId.SEARCH_RESULTS -> searchResults = enabled
            AccentElementId.MATCHING_TAG -> matchingTag = enabled
        }
    }

    fun getIntensityForStyle(style: GlowStyle): Int =
        when (style) {
            GlowStyle.SOFT -> softIntensity
            GlowStyle.SHARP_NEON -> sharpNeonIntensity
            GlowStyle.GRADIENT -> gradientIntensity
        }

    fun setIntensityForStyle(
        style: GlowStyle,
        value: Int,
    ) {
        when (style) {
            GlowStyle.SOFT -> softIntensity = value
            GlowStyle.SHARP_NEON -> sharpNeonIntensity = value
            GlowStyle.GRADIENT -> gradientIntensity = value
        }
    }

    fun getWidthForStyle(style: GlowStyle): Int =
        when (style) {
            GlowStyle.SOFT -> softWidth
            GlowStyle.SHARP_NEON -> sharpNeonWidth
            GlowStyle.GRADIENT -> gradientWidth
        }

    fun setWidthForStyle(
        style: GlowStyle,
        value: Int,
    ) {
        when (style) {
            GlowStyle.SOFT -> softWidth = value
            GlowStyle.SHARP_NEON -> sharpNeonWidth = value
            GlowStyle.GRADIENT -> gradientWidth = value
        }
    }

    fun isIslandEnabled(toolWindowId: String): Boolean =
        when (toolWindowId) {
            "Editor" -> glowEditor
            "Project" -> glowProject
            "Terminal" -> glowTerminal
            "Run" -> glowRun
            "Debug" -> glowDebug
            "Git", "Version Control", "Commit" -> glowGit
            "Services" -> glowServices
            else -> false
        }

    fun setIslandEnabled(
        toolWindowId: String,
        enabled: Boolean,
    ) {
        when (toolWindowId) {
            "Editor" -> glowEditor = enabled
            "Project" -> glowProject = enabled
            "Terminal" -> glowTerminal = enabled
            "Run" -> glowRun = enabled
            "Debug" -> glowDebug = enabled
            "Git", "Version Control", "Commit" -> glowGit = enabled
            "Services" -> glowServices = enabled
        }
    }

    companion object {
        const val DEFAULT_TAB_UNDERLINE_HEIGHT = 4
        private const val DEFAULT_SOFT_INTENSITY = 20
        private const val DEFAULT_SHARP_NEON_INTENSITY = 50
        private const val DEFAULT_GRADIENT_INTENSITY = 30
        private const val DEFAULT_SOFT_WIDTH = 4
        private const val DEFAULT_SHARP_NEON_WIDTH = 4
        private const val DEFAULT_GRADIENT_WIDTH = 6
    }
}
