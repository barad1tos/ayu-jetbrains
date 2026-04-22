package dev.ayuislands.accent

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ColorUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import dev.ayuislands.ui.ComponentTreeRefresher
import java.awt.Color
import java.awt.Window
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javax.swing.UIManager

object AccentApplicator {
    private val EP_NAME =
        ExtensionPointName<AccentElement>(
            "com.ayuislands.theme.accentElement",
        )

    private val log = logger<AccentApplicator>()

    // First-WARN-then-DEBUG gates for osActiveProjectFrame error paths. Without them, a
    // broken frame mid-shutdown or an unavailable WindowManager would either spam idea.log
    // on every apply (LAF listener, rotation tick, Settings panel Apply all go through the
    // OS-active scan) or silently degrade with no trace.
    //
    // Rotation runs on `AppScheduledExecutorService`; the other callers are on EDT.
    // Concurrent first-failure across threads is rare but real, so the gate uses
    // `AtomicBoolean.compareAndSet` — not `@Volatile Boolean` — so exactly one caller wins
    // the WARN even when two threads race to log the same shutdown-race failure.
    internal val osActiveFrameFailureLogged = AtomicBoolean(false)

    // Paired gate; see osActiveFrameFailureLogged above for the WARN/DEBUG rationale.
    internal val windowManagerUnavailableLogged = AtomicBoolean(false)

    private const val DARK_FOREGROUND_HEX = 0x1F2430
    private val DARK_FOREGROUND = Color(DARK_FOREGROUND_HEX)
    private const val TAB_ACCENT_BG_ALPHA = 50
    private const val KEY_TAB_BACKGROUND = "EditorTabs.underlinedTabBackground"
    private const val DEFAULT_UNDERLINE_ARC = 8
    private const val CGP_RESOLUTION_FAILED = "method resolution failed"
    private const val CGP_SYNC_FAILED = "sync failed"
    private val EMPTY_TEXT_ATTRIBUTES = TextAttributes()

    // Cached CodeGlance Pro reflection objects (resolved once per session)
    @Volatile private var cgpService: Any? = null

    @Volatile private var cgpGetState: Method? = null

    @Volatile private var cgpSetViewportColor: Method? = null

    @Volatile private var cgpSetViewportBorderColor: Method? = null

    @Volatile private var cgpSetViewportBorderThickness: Method? = null

    @Volatile private var cgpMethodsResolved = false

    // Always-on UIManager keys (not per-element toggleable)
    private val ALWAYS_ON_UI_KEYS =
        listOf(
            // GotItTooltip
            "GotItTooltip.background",
            "GotItTooltip.borderColor",
            // Default button
            "Button.default.startBackground",
            "Button.default.endBackground",
            // Focus border
            "Component.focusedBorderColor",
            "Component.focusColor",
            // Drag and drop
            "DragAndDrop.borderColor",
            // Trial widget
            "TrialWidget.Alert.borderColor",
            "TrialWidget.Alert.foreground",
            // Split editor border
            "OnePixelDivider.background",
            // Tab underlines (always accent, not toggleable)
            "ToolWindow.HeaderTab.underlineColor",
            "TabbedPane.underlineColor",
            "EditorTabs.underlinedBorderColor",
        )

    // Always-on editor ColorKeys (not per-element toggleable)
    private val ALWAYS_ON_EDITOR_COLOR_KEYS =
        listOf(
            ColorKey.find("BUTTON_BACKGROUND"),
            ColorKey.find("WARNING_FOREGROUND"),
            ColorKey.find("TAB_UNDERLINE"),
        )

    private data class AttrOverride(
        val key: String,
        val foreground: Boolean = false,
        val effectColor: Boolean = false,
        val errorStripe: Boolean = false,
    )

    // Always-on editor TextAttributesKey overrides
    private val ALWAYS_ON_EDITOR_ATTR_OVERRIDES =
        listOf(
            AttrOverride("BOOKMARKS_ATTRIBUTES", errorStripe = true),
            AttrOverride("DEBUGGER_INLINED_VALUES_MODIFIED", foreground = true),
            AttrOverride("LIVE_TEMPLATE_ATTRIBUTES", effectColor = true),
            AttrOverride("LOG_INFO_OUTPUT", foreground = true),
            AttrOverride("RUNTIME_ERROR", effectColor = true),
            AttrOverride("SMART_COMPLETION_STATISTICAL_MATCHED_ITEM", foreground = true),
            AttrOverride("TEXT_STYLE_WARNING", effectColor = true),
            AttrOverride("TODO_DEFAULT_ATTRIBUTES", foreground = true),
            AttrOverride("WARNING_ATTRIBUTES", effectColor = true, errorStripe = true),
        )

    fun apply(accentHex: String) {
        val accent = Color.decode(accentHex)
        val state = AyuIslandsSettings.getInstance().state
        val variant = AyuVariant.detect()

        // All work batched into a single EDT dispatch (UIManager.put is not
        // thread-safe, and elements previously posted their own invokeLater)
        val work =
            Runnable {
                applyAlwaysOnUiKeys(accent)

                applyElements(state, accent, variant)
                syncCodeGlanceProViewport(accentHex)
                if (variant != null) {
                    IndentRainbowSync.apply(variant, accentHex)
                }
                applyAlwaysOnEditorKeys(accent)
                overrideTabUnderlineForOffMode(state, variant)
                applyTabUnderlineStyle(state)

                // Gap-4 mirror of the D-15 hook in revertAll. Re-publish
                // ComponentTreeRefreshedTopic after EP apply so subscribers
                // (EditorScrollbarManager, ProjectViewScrollbarManager) re-read
                // UIManager state. Chrome surfaces do NOT subscribe to this topic —
                // their live-refresh happens in plan 40-14 Level 2. This hook is the
                // architectural symmetry primitive; 40-14 is the visible fix.
                //
                // Per 40-12 research §A verdict=UNSAFE, this site MUST NOT publish
                // LafManagerListener.TOPIC — that broadcast would re-enter the LAF
                // cycle. notifyOnly only.
                for (project in ProjectManager.getInstance().openProjects) {
                    if (!project.isUsable()) continue
                    ComponentTreeRefresher.notifyOnly(project)
                }

                repaintAllWindows(Window.getWindows())
            }

        if (SwingUtilities.isEventDispatchThread()) {
            work.run()
        } else {
            invokeLaterSafe(work)
        }
    }

    /**
     * Convenience wrapper around [AccentResolver.resolve] + [apply] for the "currently focused
     * project" use case. Called from the settings panels (Accent / Elements / Plugins), the
     * LAF listener, and the rotation tick. Pre-helper, those sites hand-wired variants of
     * the same sequence and were *inconsistent*: only the rotation path called
     * [ProjectAccentSwapService.notifyExternalApply]; the panels and LAF listener skipped it,
     * leaving the swap-cache stale and causing one redundant apply on the next WINDOW_ACTIVATED.
     *
     * Centralizing the sequence makes focused-project selection, override-priority resolution,
     * and swap-cache synchronization uniformly correct across callers. Returns the applied
     * hex so callers can log or display it.
     *
     * Note: [dev.ayuislands.AyuIslandsStartupActivity] is NOT a caller — it operates on the
     * specific project the platform hands it, not the focused one, so it bypasses this helper
     * and calls [AccentResolver.resolve] + [apply] directly with that project.
     *
     * EDT-only. Neither this helper nor [resolveFocusedProject] self-dispatch; only the
     * inner [apply] call hops to the EDT internally via [invokeLaterSafe], and that hop
     * does NOT rescue the preceding [resolveFocusedProject] + [AccentResolver.resolve]
     * steps (the first traverses EDT-only platform APIs, the second reads settings state
     * that may race off-EDT). [ProjectAccentSwapService.notifyExternalApply] is likewise
     * a bare volatile write with no dispatch. Callers MUST already be on the EDT.
     */
    @RequiresEdt
    fun applyForFocusedProject(variant: AyuVariant): String {
        val focusedProject = resolveFocusedProject()
        val hex = AccentResolver.resolve(focusedProject, variant)
        apply(hex)
        ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
        return hex
    }

    /**
     * Resolves the *actually* focused project — the one whose window is currently on top
     * for the user. The cascade walks from strongest signal (OS-level window activity) to
     * weakest (first non-disposed open project) so rotation ticks, settings Apply, LAF
     * changes, and any UI entry point route through the window the user is visually on,
     * not the one the focus manager happened to bookmark most recently.
     *
     * Exposed as `internal` so every UI entry point that needs the focused project shares
     * one cascade instead of hand-rolling `ProjectManager.openProjects.firstOrNull` — that
     * pattern silently binds to the enumeration-first project in multi-window setups,
     * producing stale status-label readouts in the Accent settings panel that don't match
     * the visible window. Most callers reach this via [applyForFocusedProject]; settings
     * panels that only need to READ the focused project (without applying) call it directly.
     *
     * Must run on the EDT: traverses `WindowManager`, `IdeFocusManager`, and
     * `ProjectManager`, all of which are EDT-only platform APIs. Annotated with
     * [RequiresEdt] so accidental off-EDT callers surface through IntelliJ's threading
     * checker rather than deadlocking or throwing deep inside the platform.
     *
     *  1. First `WindowManager.allProjectFrames` whose ancestor window reports
     *     `Window.isActive`. Iterates all project frames, calls
     *     `SwingUtilities.getWindowAncestor(frame.component)`, and matches where
     *     `window.isActive` is true. (The sibling swap-service uses the same frame
     *     enumeration but matches by reference equality against a listener-provided
     *     window — identical iteration, different predicate.)
     *  2. [IdeFocusManager.lastFocusedFrame]'s project — fallback when the OS-active
     *     scan returns null for any reason: the IDE is alt-tabbed out, the window
     *     ancestor lookup is temporarily unavailable, or `WindowManager` itself is
     *     null during startup / shutdown.
     *  3. First non-default non-disposed open project — pre-focus-manager startup or
     *     shutdown edge cases.
     *  4. `null` — no project open; resolver will return the global accent.
     */
    @RequiresEdt
    internal fun resolveFocusedProject(): com.intellij.openapi.project.Project? {
        osActiveProjectFrame()?.let { return it }
        IdeFocusManager
            .getGlobalInstance()
            .lastFocusedFrame
            ?.project
            ?.takeIf { it.isUsable() }
            ?.let { return it }
        return ProjectManager
            .getInstance()
            .openProjects
            .firstOrNull { it.isUsable() }
    }

    /**
     * Scans open project frames for the one whose ancestor window is OS-active. Each frame
     * access is wrapped in try/catch because frames can be mid-dispose during a shutdown
     * race; one bad frame must not break the scan for a healthy one. Error paths — a null
     * `WindowManager` service and per-frame failures — log at WARN on first occurrence then
     * degrade to DEBUG so user-submitted idea.log captures the pathology while interactive
     * sessions do not drown in noise across repeated Apply / LAF-change / rotation-tick
     * calls.
     */
    private fun osActiveProjectFrame(): com.intellij.openapi.project.Project? {
        val windowManager = WindowManager.getInstance()
        if (windowManager == null) {
            val context = "thread=${Thread.currentThread().name}"
            if (windowManagerUnavailableLogged.compareAndSet(false, true)) {
                log.warn(
                    "WindowManager unavailable during OS-active project resolution ($context); " +
                        "falling back to IdeFocusManager cascade (further occurrences at DEBUG)",
                )
            } else {
                log.debug("WindowManager unavailable during OS-active project resolution ($context)")
            }
            return null
        }
        for ((index, frame) in windowManager.allProjectFrames.withIndex()) {
            var probedName: String? = null
            try {
                val project = frame.project ?: continue
                probedName = runCatching { project.name }.getOrNull()
                if (!project.isUsable()) continue
                val window = SwingUtilities.getWindowAncestor(frame.component) ?: continue
                if (window.isActive) return project
            } catch (exception: RuntimeException) {
                val context =
                    "index=$index, project=${probedName ?: "<unresolved>"}, " +
                        "thread=${Thread.currentThread().name}"
                if (osActiveFrameFailureLogged.compareAndSet(false, true)) {
                    log.warn(
                        "Skipping frame during OS-active resolution ($context) " +
                            "(further failures logged at DEBUG)",
                        exception,
                    )
                } else {
                    log.debug("Skipping frame during OS-active resolution ($context)", exception)
                }
            }
        }
        return null
    }

    private fun com.intellij.openapi.project.Project.isUsable(): Boolean = !isDefault && !isDisposed

    fun revertAll() {
        // All revert work batched into a single EDT dispatch
        val work =
            Runnable {
                for (key in ALWAYS_ON_UI_KEYS) {
                    UIManager.put(key, null)
                }
                UIManager.put("GotItTooltip.foreground", null)
                UIManager.put("GotItTooltip.Button.foreground", null)
                UIManager.put("GotItTooltip.Header.foreground", null)
                UIManager.put("Button.default.focusedBorderColor", null)
                UIManager.put("Button.default.startBorderColor", null)
                UIManager.put("Button.default.endBorderColor", null)
                UIManager.put(KEY_TAB_BACKGROUND, null)
                UIManager.put("EditorTabs.underlineHeight", null)
                UIManager.put("EditorTabs.underlineArc", null)

                for (element in EP_NAME.extensionList) {
                    try {
                        element.revert()
                    } catch (exception: RuntimeException) {
                        log.warn(
                            "Failed to revert ${element.displayName}",
                            exception,
                        )
                    }
                }

                revertAlwaysOnEditorKeys()

                // D-15: cached JBColor instances survive a bare UIManager.put(key, null)
                // clear. Publish the refresh topic per usable open project so subscribers
                // (e.g. EditorScrollbarManager) reapply their customizations against the
                // freshly-reverted UIManager state. notifyOnly stops short of
                // IJSwingUtilities.updateComponentTreeUI — that path would crash here
                // because LAF refresh is still mid-flight (see the no-repaint note
                // below). Publishing a topic lets subscribers decide when to repaint.
                for (project in ProjectManager.getInstance().openProjects) {
                    if (!project.isUsable()) continue
                    ComponentTreeRefresher.notifyOnly(project)
                }

                // No repaintAllWindows here: revertAll runs inside
                // lookAndFeelChanged, before the new theme finishes
                // loading. Forcing a repaint at this point causes
                // NPE in the platform code (HeaderToolbarButtonLook)
                // because UI keys are cleared but not yet replaced.
                // The platform repaints everything after the theme switch.
            }

        if (SwingUtilities.isEventDispatchThread()) {
            work.run()
        } else {
            invokeLaterSafe(work)
        }
    }

    private fun neutralizeOrRevert(
        element: AccentElement,
        variant: AyuVariant?,
    ) {
        try {
            if (variant != null) {
                element.applyNeutral(variant)
            } else {
                element.revert()
            }
        } catch (exception: RuntimeException) {
            log.warn("Failed to neutralize ${element.displayName}", exception)
        }
    }

    private fun applyElements(
        state: AyuIslandsState,
        accent: Color,
        variant: AyuVariant?,
    ) {
        for (element in EP_NAME.extensionList) {
            val enabled = state.isToggleEnabled(element.id)
            if (!enabled) {
                neutralizeOrRevert(element, variant)
                continue
            }
            val conflict = ConflictRegistry.getConflictFor(element.id)
            val forceOverride = element.id.name in state.forceOverrides
            if (conflict != null && !forceOverride) {
                neutralizeOrRevert(element, variant)
                continue
            }
            if (conflict != null) {
                log.warn(
                    "Force-overriding ${conflict.pluginDisplayName} conflict for ${element.displayName}",
                )
            }
            try {
                element.apply(accent)
            } catch (exception: RuntimeException) {
                log.warn(
                    "Failed to apply accent to ${element.displayName}",
                    exception,
                )
            }
        }
    }

    private fun applyAlwaysOnUiKeys(accent: Color) {
        for (key in ALWAYS_ON_UI_KEYS) {
            UIManager.put(key, accent)
        }

        // Contrast foreground for accent-background elements (GotItTooltip, buttons)
        val contrastForeground = if (ColorUtil.isDark(accent)) Color.WHITE else DARK_FOREGROUND
        UIManager.put("GotItTooltip.foreground", contrastForeground)
        UIManager.put("GotItTooltip.Button.foreground", contrastForeground)
        UIManager.put("GotItTooltip.Header.foreground", contrastForeground)

        // Darkened accent for default button borders (~15% darker)
        val darkenedAccent = ColorUtil.darker(accent, 1)
        UIManager.put("Button.default.focusedBorderColor", darkenedAccent)
        UIManager.put("Button.default.startBorderColor", darkenedAccent)
        UIManager.put("Button.default.endBorderColor", darkenedAccent)

        // Editor tab background tint (respects persisted tab mode, not gated by license)
        val state = AyuIslandsSettings.getInstance().state
        val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "MINIMAL")
        when (tabMode) {
            GlowTabMode.MINIMAL -> UIManager.put(KEY_TAB_BACKGROUND, Color(0, 0, 0, 0))
            GlowTabMode.FULL -> {
                val tinted = Color(accent.red, accent.green, accent.blue, TAB_ACCENT_BG_ALPHA)
                UIManager.put(KEY_TAB_BACKGROUND, tinted)
            }
            GlowTabMode.OFF -> {
                val variant = AyuVariant.detect()
                val neutralColor = variant?.let { Color.decode(it.neutralGray) }
                UIManager.put("EditorTabs.underlinedBorderColor", neutralColor)
                UIManager.put(KEY_TAB_BACKGROUND, Color(0, 0, 0, 0))
            }
        }
    }

    fun resolveUnderlineHeight(state: AyuIslandsState): Int {
        val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "MINIMAL")
        if (tabMode == GlowTabMode.OFF) return state.tabUnderlineHeight

        if (state.tabUnderlineGlowSync && state.glowEnabled) {
            val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
            return state.getWidthForStyle(style)
        }
        return state.tabUnderlineHeight
    }

    private fun applyAlwaysOnEditorKeys(accent: Color) {
        val scheme = EditorColorsManager.getInstance().globalScheme

        // ColorKey entries
        for (colorKey in ALWAYS_ON_EDITOR_COLOR_KEYS) {
            scheme.setColor(colorKey, accent)
        }

        // TextAttributesKey entries -- clone existing, override only accent properties
        for (override in ALWAYS_ON_EDITOR_ATTR_OVERRIDES) {
            val attrKey = TextAttributesKey.find(override.key)
            val existing = scheme.getAttributes(attrKey)
            val updated = existing?.clone() ?: TextAttributes()
            if (override.foreground) updated.foregroundColor = accent
            if (override.effectColor) updated.effectColor = accent
            if (override.errorStripe) updated.errorStripeColor = accent
            scheme.setAttributes(attrKey, updated)
        }

        // Notify editors to repaint with an updated scheme
        // Wrapped in ReadAction because Jupyter's NotebookEditorColorsListener
        // accesses PSI from globalSchemeChange, which requires read access.
        ReadAction.run<RuntimeException> {
            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
        }
    }

    private fun applyTabUnderlineStyle(state: AyuIslandsState) {
        val height = resolveUnderlineHeight(state)
        UIManager.put("EditorTabs.underlineHeight", Integer.valueOf(height))

        val arc = UIManager.getInt("Island.arc").let { if (it > 0) it else DEFAULT_UNDERLINE_ARC }
        UIManager.put("EditorTabs.underlineArc", Integer.valueOf(arc))

        log.info("Tab underline: height=${height}px, arc=${arc}px")
    }

    private fun overrideTabUnderlineForOffMode(
        state: AyuIslandsState,
        variant: AyuVariant?,
    ) {
        val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "MINIMAL")
        if (tabMode != GlowTabMode.OFF || variant == null) return
        val scheme = EditorColorsManager.getInstance().globalScheme
        scheme.setColor(ColorKey.find("TAB_UNDERLINE"), Color.decode(variant.neutralGray))
    }

    private fun revertAlwaysOnEditorKeys() {
        val scheme = EditorColorsManager.getInstance().globalScheme

        for (colorKey in ALWAYS_ON_EDITOR_COLOR_KEYS) {
            scheme.setColor(colorKey, null)
        }

        for (override in ALWAYS_ON_EDITOR_ATTR_OVERRIDES) {
            val attrKey = TextAttributesKey.find(override.key)
            val fallback = attrKey.fallbackAttributeKey
            val defaultAttrs =
                if (fallback != null) scheme.getAttributes(fallback) else null
            scheme.setAttributes(attrKey, defaultAttrs ?: EMPTY_TEXT_ATTRIBUTES)
        }

        ReadAction.run<RuntimeException> {
            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
        }
    }

    private fun invokeLaterSafe(work: Runnable) {
        val app = ApplicationManager.getApplication()
        if (app != null) {
            app.invokeLater(work, ModalityState.nonModal())
        } else {
            log.warn(
                "Application not available, " +
                    "falling back to SwingUtilities",
            )
            SwingUtilities.invokeLater(work)
        }
    }

    private fun repaintAllWindows(windows: Array<Window>) {
        for (window in windows) {
            window.repaint()
        }
    }

    private fun resolveCgpMethods() {
        if (cgpMethodsResolved) return
        cgpMethodsResolved = true

        try {
            val pluginId = PluginId.getId("com.nasller.CodeGlancePro")
            val cgpPlugin = PluginManagerCore.getPlugin(pluginId) ?: return
            val cgpClassLoader = cgpPlugin.pluginClassLoader ?: return

            val serviceClass =
                Class.forName(
                    "com.nasller.codeglance.config.CodeGlanceConfigService",
                    true,
                    cgpClassLoader,
                )

            val service = ApplicationManager.getApplication().getService(serviceClass) ?: return

            cgpService = service
            cgpGetState = service.javaClass.getMethod("getState")

            // Resolve config methods from the state object's class
            val config = cgpGetState!!.invoke(service) ?: return
            val configClass = config.javaClass
            cgpSetViewportColor = configClass.getMethod("setViewportColor", String::class.java)
            cgpSetViewportBorderColor = configClass.getMethod("setViewportBorderColor", String::class.java)
            cgpSetViewportBorderThickness = configClass.getMethod("setViewportBorderThickness", Int::class.java)
        } catch (exception: ReflectiveOperationException) {
            logCgpWarning(CGP_RESOLUTION_FAILED, exception)
        } catch (exception: RuntimeException) {
            logCgpWarning(CGP_RESOLUTION_FAILED, exception)
        }
    }

    private fun syncCodeGlanceProViewport(accentHex: String) {
        if (!AyuIslandsSettings.getInstance().state.cgpIntegrationEnabled) return

        resolveCgpMethods()

        val service = cgpService ?: return
        val getState = cgpGetState ?: return
        val setColor = cgpSetViewportColor ?: return
        val setBorderColor = cgpSetViewportBorderColor ?: return
        val setBorderThickness = cgpSetViewportBorderThickness ?: return

        try {
            val hexWithoutHash = accentHex.removePrefix("#")
            val config = getState.invoke(service) ?: return

            setColor.invoke(config, hexWithoutHash)
            setBorderColor.invoke(config, hexWithoutHash)
            setBorderThickness.invoke(config, 1)

            // CGP panels repaint via globalSchemeChange notification (no manual walk needed)
            log.info("CodeGlance Pro viewport color synced to $hexWithoutHash")
        } catch (exception: java.lang.reflect.InvocationTargetException) {
            logCgpWarning(CGP_SYNC_FAILED, exception.cause ?: exception)
        } catch (exception: ReflectiveOperationException) {
            logCgpWarning(CGP_SYNC_FAILED, exception)
        } catch (exception: RuntimeException) {
            logCgpWarning(CGP_SYNC_FAILED, exception)
        }
    }

    private fun logCgpWarning(
        action: String,
        exception: Throwable,
    ) {
        log.warn("CodeGlance Pro $action: ${exception.javaClass.simpleName}: ${exception.message}")
    }
}
