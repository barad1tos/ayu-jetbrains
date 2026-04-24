package dev.ayuislands.settings

import com.intellij.openapi.components.BaseState
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.TintIntensity
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.rotation.AccentRotationMode
import java.io.File

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
    var trialExpiryWarningShown by property(false)
    var trialExpiry3DayWarningShown by property(false)
    var proDefaultsApplied by property(false)
    var everBeenPro by property(false)
    var lastKnownLicensedMs by property(0L)
    var workspaceDefaultsApplied by property(false)
    var trialWelcomeShown by property(false)
    var freeOnboardingShown by property(false)
    var premiumOnboardingShown by property(false)

    /**
     * Last accent hex applied by [dev.ayuislands.accent.AccentApplicator.apply].
     * Persisted so the next IDE startup can paint the correct color on the very
     * first frame (before project contexts load), eliminating the "Gold → override"
     * flicker users see when multiple project windows restore simultaneously with
     * distinct per-project overrides.
     *
     * Written by every `apply()`, read once in
     * [dev.ayuislands.AyuIslandsAppListener.appFrameCreated] and then effectively
     * ignored at runtime (the live resolver chain inside `AyuIslandsStartupActivity`
     * overrides it per project once contexts are available).
     *
     * Stored as hex string (e.g. `"#5CCFE6"`) for IDE-state simplicity — no color
     * serialization round-trip. `null` on first launch (pre-plugin-install history).
     */
    var lastAppliedAccentHex by string(null)

    /**
     * Whether the most recent [dev.ayuislands.accent.AccentApplicator.apply] call
     * completed its full EP iteration cleanly. Used by
     * [dev.ayuislands.AyuIslandsAppListener.appFrameCreated] as a trust gate around
     * the cached [lastAppliedAccentHex]: persisting the hex BEFORE the EP iteration
     * (Phase 40.2 H-2) makes startup anti-flicker robust to a failed apply, but
     * the cached value is only reliable when the previous session actually finished
     * painting. If a prior apply threw mid-EP and left the hex persisted without
     * a matching true here, the next startup resolves fresh instead of trusting
     * the partial cache.
     */
    var lastApplyOk by property(false)

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

    // Font families installed via the runtime FontInstaller (family name, e.g. "Maple Mono")
    var installedFonts by stringSet()

    // One-shot flag: installedFonts has been seeded from a filesystem/GraphicsEnvironment
    // probe so returning users who pre-installed fonts via the Settings panel aren't
    // re-prompted. Set to true after the first successful seed.
    var installedFontsSeeded by property(false)

    // Authoritative list of absolute file paths per installed family (D-08).
    // Values are "\n"-joined absolute paths. Flat map<String, String> shape matches
    // the existing `fontPresetCustomizations` encoding precedent and avoids
    // nested-collection XML serialization risk (BaseState silently drops
    // Map<String, List<String>> on some platform versions).
    // Use [encodeFontPaths] / [decodeFontPaths] for encode/decode — never split/join
    // inline.
    var installedFontFiles by map<String, String>()

    // Families the user has explicitly deleted via the Settings lifecycle UI (D-09).
    // seedInstalledFontsFromDiskIfNeeded() must skip any family in this set so the
    // plugin never re-adds a user-deleted font on a subsequent startup.
    var explicitlyUninstalledFonts by stringSet()

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

    // What's New tab (shown once per minor/major release that ships a manifest).
    // Generic field — single string tracks the highest version whose tab was shown,
    // so future releases (2.6, 3.0, …) need only drop a manifest under
    // resources/whatsnew/v{X.Y.Z}/ — no per-version booleans required.
    var lastWhatsNewShownVersion by string(null)

    // Settings tab selection (persisted across settings opens)
    var settingsSelectedTab by property(0)

    // Workspace tab: collapsible group expanded states
    var workspaceProjectViewExpanded by property(true)
    var workspaceCommitPanelExpanded by property(false)
    var workspaceEditorExpanded by property(false)
    var workspaceGitPanelExpanded by property(false)

    // Accent tab: collapsible group expanded states (persisted across settings opens)
    var systemGroupExpanded by property(false)
    var overridesGroupExpanded by property(false)
    var accentRotationGroupExpanded by property(true)
    var accentElementsGroupExpanded by property(false)

    // Force overrides for conflicting elements (element ID names)
    var forceOverrides by stringSet()

    // Chrome tinting (phase 40). Per-surface opt-in toggles and a global intensity.
    // WCAG foreground contrast is always-on (no persisted toggle). CHROME-group
    // AccentElementIds are driven by these fields — NOT by the Elements-panel toggle
    // map — so they never leak into the VISUAL/INTERACTIVE checkbox list. Defaults
    // are all OFF (premium, opt-in).
    var chromeStatusBar by property(false)
    var chromeMainToolbar by property(false)
    var chromeToolWindowStripe by property(false)
    var chromeNavBar by property(false)
    var chromePanelBorder by property(false)

    // Global tint intensity (0-MAX_CHROME_TINT_INTENSITY). See DEFAULT_CHROME_TINT_INTENSITY KDoc for the
    // 40 rationale. Raw reads of this field can return out-of-range values if the
    // persisted XML was hand-edited, migrated from an older schema, or otherwise
    // corrupted — the backing `BaseState.property(...)` delegate is unvalidated.
    // Call-sites that feed the HSB blender MUST read through
    // [effectiveChromeTintIntensity] so a garbage persisted value can't desaturate
    // or over-saturate chrome surfaces.
    var chromeTintIntensity by property(DEFAULT_CHROME_TINT_INTENSITY)

    // Chrome-tinting collapsible group expanded state (same shape as accentElementsGroupExpanded).
    var chromeTintingGroupExpanded by property(false)

    /**
     * Returns [chromeTintIntensity] wrapped in a [TintIntensity] — the wrapper's
     * `of(raw)` factory clamps to the user-visible slider range
     * `[TintIntensity.MIN, TintIntensity.MAX]`.
     *
     * The underlying field is delegated through [BaseState.property] which performs
     * no validation — a corrupted persisted XML (hand-edited, legacy-migrated, or
     * third-party-tool-written) can store values outside the slider bounds. Chrome
     * element apply sites feed this value into the HSB blender; out-of-range
     * garbage would desaturate or over-saturate the tinted surface. Reading through
     * this helper keeps every caller on the safe contract without breaking XML
     * serialization (which requires the raw delegate).
     */
    fun effectiveChromeTintIntensity(): TintIntensity = TintIntensity.of(chromeTintIntensity)

    /**
     * Returns [lastAppliedAccentHex] wrapped in an [AccentHex], or `null` when
     * the persisted string is absent or corrupted. Mirrors the
     * [effectiveChromeTintIntensity] pattern: the raw field stays `String?`
     * for `BaseState` XML serialization, and every read path consults this
     * helper instead of calling [AccentHex.of] at each site.
     *
     * Phase 40.3b: used by [dev.ayuislands.AyuIslandsAppListener.appFrameCreated]
     * as the single trust boundary for the first-frame anti-flicker cache;
     * [AccentHex.of] internally rejects hand-edited XML corruption and
     * truncated writes so the resolver fallback fires whenever the cache is
     * unusable.
     */
    fun effectiveLastAppliedAccentHex(): AccentHex? = AccentHex.of(lastAppliedAccentHex)

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
            AccentElementId.STATUS_BAR -> chromeStatusBar
            AccentElementId.MAIN_TOOLBAR -> chromeMainToolbar
            AccentElementId.TOOL_WINDOW_STRIPE -> chromeToolWindowStripe
            AccentElementId.NAV_BAR -> chromeNavBar
            AccentElementId.PANEL_BORDER -> chromePanelBorder
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
            AccentElementId.STATUS_BAR -> chromeStatusBar = enabled
            AccentElementId.MAIN_TOOLBAR -> chromeMainToolbar = enabled
            AccentElementId.TOOL_WINDOW_STRIPE -> chromeToolWindowStripe = enabled
            AccentElementId.NAV_BAR -> chromeNavBar = enabled
            AccentElementId.PANEL_BORDER -> chromePanelBorder = enabled
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
        private const val PATH_SEP = "\n"

        fun encodeFontPaths(paths: List<File>): String = paths.joinToString(PATH_SEP) { it.absolutePath }

        fun decodeFontPaths(raw: String?): List<String> = raw?.split(PATH_SEP)?.filter { it.isNotBlank() }.orEmpty()

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

        /**
         * Chrome tint intensity default (0-100). `40` is the Peacock-parity
         * opening balance per Phase 40 `CONTEXT.md §specifics` and sits above
         * VERIFICATION Gap 1's recommended floor of `35` — at `20` the tint
         * read as a 5% wash on the Mirage + Cyan pairing (runIde smoke
         * 2026-04-22), which failed the user-space quality bar. The HSB-space
         * blender (Phase 40-09 Task 2) makes this value visibly chromatic
         * across all 5 chrome surfaces without sacrificing foreground
         * readability. Users can adjust via the Chrome tinting settings slider.
         */
        const val DEFAULT_CHROME_TINT_INTENSITY = 40

        /**
         * Upper bound for [chromeTintIntensity] reads. Mirrors the user-visible
         * `AyuIslandsChromePanel.MAX_INTENSITY` slider cap (50) — the blender's
         * internal 0-100 math-safety clamp still guards the algorithm, but
         * [effectiveChromeTintIntensity] pre-clamps to the UX cap so legacy
         * persisted values (pre-cap sessions could save up to 100) and any
         * corrupted XML observe the same ceiling every live user can reach.
         */
        const val MAX_CHROME_TINT_INTENSITY = 50
    }
}
