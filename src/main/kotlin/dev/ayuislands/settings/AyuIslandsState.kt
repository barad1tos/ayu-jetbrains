package dev.ayuislands.settings

import com.intellij.openapi.components.BaseState
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.rotation.AccentRotationMode

enum class PanelWidthMode {
    DEFAULT,
    AUTO_FIT,
    FIXED,
    ;

    companion object {
        fun fromString(value: String?): PanelWidthMode = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

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
    var workspaceDefaultsApplied by property(false)
    var trialWelcomeShown by property(false)

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

    // Per-island toggles (all ON by default — glow visibility is controlled by glowEnabled)
    var glowEditor by property(true)
    var glowProject by property(true)
    var glowTerminal by property(true)
    var glowRun by property(true)
    var glowDebug by property(true)
    var glowGit by property(true)
    var glowServices by property(true)

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

    // Project View tweaks
    var hideProjectRootPath by property(false)
    var hideProjectViewHScrollbar by property(false)

    // Editor scrollbar visibility
    var hideEditorVScrollbar by property(false)
    var hideEditorHScrollbar by property(false)

    /**
     * Legacy migration field — migrated to [projectPanelWidthMode] in v2.0.
     * Retained for [migrateWidthModes] to upgrade pre-v2.0 settings.
     * Safe to remove after v4.0+ (2027) when pre-v2.0 installs are negligible.
     */
    var autoFitProjectPanelWidth by property(false)
    var autoFitMaxWidth by property(DEFAULT_AUTO_FIT_MAX_WIDTH)

    /**
     * Legacy migration field — migrated to [commitPanelWidthMode] in v2.0.
     * Retained for [migrateWidthModes] to upgrade pre-v2.0 settings.
     * Safe to remove after v4.0+ (2027) when pre-v2.0 installs are negligible.
     */
    var autoFitCommitPanelWidth by property(false)
    var autoFitCommitMaxWidth by property(DEFAULT_AUTO_FIT_MAX_WIDTH)

    // Panel width mode (3-state: DEFAULT / AUTO_FIT / FIXED)
    var projectPanelWidthMode by string(PanelWidthMode.DEFAULT.name)
    var projectPanelAutoFitMinWidth by property(DEFAULT_PROJECT_AUTO_FIT_MIN_WIDTH)
    var projectPanelFixedWidth by property(DEFAULT_FIXED_WIDTH)
    var commitPanelWidthMode by string(PanelWidthMode.DEFAULT.name)
    var commitPanelAutoFitMinWidth by property(DEFAULT_COMMIT_AUTO_FIT_MIN_WIDTH)
    var commitPanelFixedWidth by property(DEFAULT_FIXED_WIDTH)
    var gitPanelWidthMode by string(PanelWidthMode.DEFAULT.name)
    var gitPanelAutoFitMaxWidth by property(DEFAULT_GIT_AUTO_FIT_MAX_WIDTH)
    var gitPanelAutoFitMinWidth by property(DEFAULT_GIT_AUTO_FIT_MIN_WIDTH)
    var gitPanelFixedWidth by property(DEFAULT_FIXED_WIDTH)

    // Font preset
    var fontPresetEnabled by property(false)
    var fontPresetName by string("AMBIENT")
    var fontApplyToConsole by property(false)
    var fontInstallTerminal by string("BUILTIN")

    // Per-preset custom settings: key = preset name, value = "size|spacing|ligatures|weight"
    var fontPresetCustomizations by map<String, String>()

    // Accent rotation
    var accentRotationEnabled by property(false)
    var accentRotationMode by string(AccentRotationMode.PRESET.name)
    var accentRotationIntervalHours by property(DEFAULT_ROTATION_INTERVAL_HOURS)
    var accentRotationLastSwitchMs by property(0L)
    var accentRotationPresetIndex by property(0)

    // Last shuffle random color (13th swatch persistence)
    var lastShuffleColor by string(null)

    // Update notification (shown once per version upgrade)
    var lastSeenVersion by string(null)

    // Settings tab selection (persisted across settings opens)
    var settingsSelectedTab by property(0)

    // Workspace tab: collapsible group expanded states
    var workspaceProjectViewExpanded by property(true)
    var workspaceCommitPanelExpanded by property(false)
    var workspaceEditorExpanded by property(false)
    var workspaceGitPanelExpanded by property(false)

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

    fun migrateWidthModes() {
        if (autoFitProjectPanelWidth && projectPanelWidthMode == PanelWidthMode.DEFAULT.name) {
            projectPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        }
        if (autoFitCommitPanelWidth && commitPanelWidthMode == PanelWidthMode.DEFAULT.name) {
            commitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        }
    }

    companion object {
        const val DEFAULT_TAB_UNDERLINE_HEIGHT = 4
        const val DEFAULT_AUTO_FIT_MAX_WIDTH = 400
        const val DEFAULT_PROJECT_AUTO_FIT_MIN_WIDTH = 250
        const val DEFAULT_COMMIT_AUTO_FIT_MIN_WIDTH = 250
        const val DEFAULT_GIT_AUTO_FIT_MIN_WIDTH = 400
        const val DEFAULT_GIT_AUTO_FIT_MAX_WIDTH = 500
        const val DEFAULT_FIXED_WIDTH = 300
        const val DEFAULT_ROTATION_INTERVAL_HOURS = 6
        private const val DEFAULT_SOFT_INTENSITY = 20
        private const val DEFAULT_SHARP_NEON_INTENSITY = 50
        private const val DEFAULT_GRADIENT_INTENSITY = 30
        private const val DEFAULT_SOFT_WIDTH = 4
        private const val DEFAULT_SHARP_NEON_WIDTH = 4
        private const val DEFAULT_GRADIENT_WIDTH = 6
    }
}
