package dev.ayuislands.accent

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import dev.ayuislands.ui.ComponentTreeRefresher
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javax.swing.UIManager

object AccentApplicator {
    /** Notification group ID declared in plugin.xml — shared with [dev.ayuislands.rotation.AccentRotationService]. */
    private const val NOTIFICATION_GROUP_ID = "Ayu Islands"

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
    private val DARK_FOREGROUND = JBColor(DARK_FOREGROUND_HEX, DARK_FOREGROUND_HEX)
    private const val TAB_ACCENT_BG_ALPHA = 50
    private const val KEY_TAB_BACKGROUND = "EditorTabs.underlinedTabBackground"
    private const val KEY_TAB_UNDERLINE_HEIGHT = "EditorTabs.underlineHeight"
    private const val KEY_TAB_UNDERLINE_ARC = "EditorTabs.underlineArc"
    private const val DEFAULT_UNDERLINE_ARC = 8
    private val TRANSPARENT_TAB_BACKGROUND =
        JBColor(
            ColorUtil.toAlpha(JBColor.BLACK, 0),
            ColorUtil.toAlpha(JBColor.BLACK, 0),
        )

    // CGP viewport defaults live in [CodeGlanceProIntegration] — they are
    // exclusively read inside that peer object. See
    // [CodeGlanceProIntegration.CGP_DEFAULT_VIEWPORT_COLOR] for the javap provenance and
    // re-verification recipe.

    private val EMPTY_TEXT_ATTRIBUTES = TextAttributes()

    // CodeGlance Pro reflection state and apply/revert workers live in
    // [CodeGlanceProIntegration] to keep this object below the TooManyFunctions threshold.
    // Only the cross-object test seam (`codeGlanceProRevertHook` + `resetCodeGlanceProRevertHookForTests`)
    // and the swap-path entry stay here because existing tests bind those names
    // to `AccentApplicator`.

    /**
     * Per-thread revert observer for [CodeGlanceProIntegration.revertCodeGlanceProViewport].
     * Production path: null → reflection writes fire against the real CGP service.
     * Test path: a non-null supplier records the three default values the revert
     * would have written, bypassing the reflection chain because CGP is not on
     * the test classpath.
     *
     * Why ThreadLocal rather than `@Volatile var`? Gradle runs JUnit tests in
     * parallel workers; a shared volatile slot leaks a pinned observer from
     * one test into a concurrent sibling's revert call, producing intermittent
     * "looks like the other test's fake received my invocation" failures.
     * Matches [ChromeDecorationsProbe.osSupplier].
     *
     * Tests MUST use try/finally + [resetCodeGlanceProRevertHookForTests] — `@AfterEach`
     * does NOT run after an assertion failure that exits the worker mid-test,
     * so per-test cleanup is mandatory.
     */
    internal val codeGlanceProRevertHook: ThreadLocal<((String, String, Int) -> Unit)?> =
        ThreadLocal.withInitial { null }

    /** Restore the production [codeGlanceProRevertHook] on the calling thread. Intended for test teardown. */
    @TestOnly
    internal fun resetCodeGlanceProRevertHookForTests() {
        codeGlanceProRevertHook.remove()
    }

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

    /**
     * Applies [accentHex] across UIManager, editor keys, and the EP chain.
     *
     * Takes an [AccentHex] whose `#RRGGBB` shape is proven by construction —
     * no internal regex, no `NumberFormatException` path through [Color.decode].
     * Callers with a raw `String` from an untrusted boundary should use the
     * top-level [applyFromHexString] helper which centralizes the
     * notification-on-bad-hex + `false` return contract.
     *
     * Cache write ordering: [AyuIslandsState.lastAppliedAccentHex] is written BEFORE the EP
     * iteration runs, not after — a mid-EP throw would otherwise drop the anti-flicker
     * cache and re-flash Gold on the next startup. The paired
     * [AyuIslandsState.lastApplyOk] flag is reset to `false` before EP iteration and
     * flipped to `true` only by the [AccentApplyStep.MarkApplyClean] step, so the
     * startup listener can distinguish "cached hex from a clean apply" from "cached hex
     * from a torn apply" and fall back to the resolver in the torn case. A throwing
     * step aborts the plan ([AccentApplyFailurePolicy.AbortOnFirstFailure]) and is
     * contained into an [AccentApplyOutcome.Torn] WARN — it never reaches the caller.
     *
     * ### Threading contract
     *
     * Synchronous when called on the EDT; posts the full plan via
     * [AccentApplyPlanRunner] when called off-EDT. The ordering invariant —
     * applyElements / editor keys / repaint all happen BEFORE the method
     * returns on EDT — is load-bearing for callers that publish follow-on
     * cache state (for example [ProjectAccentSwapService.notifyExternalApply]
     * reached via [applyForFocusedProject]): those callers MUST already be on
     * the EDT so their cache write happens after the full apply sequence.
     * Off-EDT callers get scheduling semantics only — paint completion is
     * asynchronous, and the `lastApplyOk` flag on [AyuIslandsState] is the
     * correct signal for "apply finished cleanly" in those flows.
     */
    fun apply(accentHex: AccentHex) {
        val trimmedHex = accentHex.value
        val accent = accentHex.toColor()
        val state = AyuIslandsSettings.getInstance().state
        val context = AccentContext.detect()

        // Persist BEFORE the EP iteration so the cache survives a mid-EP
        // throw. Clear the clean-apply flag here; only the MarkApplyClean step
        // sets it true — the startup listener reads the pair and falls through
        // to the resolver when the flag is false.
        state.lastAppliedAccentHex = trimmedHex
        state.lastApplyOk = false

        // Step order AND gating live only in [applyPlanFor] (including the
        // IR-before-CGP-before-notify integration lock shared with revertAll);
        // this map binds every step unconditionally to its side-effecting
        // worker. Context-shaped steps guard with checkNotNull so a plan bug
        // that schedules them without their input fails with a precise message
        // (surfaced as that step's failure) instead of an NPE.
        val workers: Map<AccentApplyStep, () -> Unit> =
            buildMap {
                put(AccentApplyStep.ApplyAlwaysOnUiKeys) { applyAlwaysOnUiKeys(state, accent) }
                put(AccentApplyStep.ApplyElements) {
                    applyElements(
                        state,
                        accent,
                        checkNotNull(context) { "ApplyElements planned without accent context" },
                    )
                }
                put(AccentApplyStep.ApplyTabUnderline) {
                    applyTabUnderline(
                        state,
                        checkNotNull(context) { "ApplyTabUnderline planned without accent context" },
                    )
                }
                put(AccentApplyStep.SyncIndentRainbow) {
                    IndentRainbowSync.apply(
                        checkNotNull(context) { "SyncIndentRainbow planned without accent context" },
                        trimmedHex,
                    )
                }
                put(AccentApplyStep.SyncCodeGlanceProViewport) {
                    CodeGlanceProIntegration.syncCodeGlanceProViewport(trimmedHex, context)
                }
                put(AccentApplyStep.ApplyAlwaysOnEditorKeys) { applyAlwaysOnEditorKeys(accent) }
                put(AccentApplyStep.NotifyComponentTrees) {
                    // Mirror of the refresh hook in revertAll. Re-publish
                    // ComponentTreeRefreshedTopic after EP apply so subscribers
                    // (EditorScrollbarManager, ProjectViewScrollbarManager) re-read
                    // UIManager state. Chrome surfaces do NOT subscribe to this topic —
                    // they own their own live-refresh path.
                    //
                    // This site MUST NOT publish LafManagerListener.TOPIC — that broadcast
                    // would re-enter the LAF cycle. notifyOnly only.
                    for (project in ProjectManager.getInstance().openProjects) {
                        if (!project.isUsable()) continue
                        ComponentTreeRefresher.notifyOnly(project)
                    }
                }
                put(AccentApplyStep.RepaintWindows) { repaintAllWindows(Window.getWindows()) }
                // A throw in any earlier step aborts the plan and skips this
                // write, leaving the flag false so the startup listener
                // (AyuIslandsAppListener.appFrameCreated) falls through to the
                // resolver rather than trusting the cached hex.
                put(AccentApplyStep.MarkApplyClean) { state.lastApplyOk = true }
                put(AccentApplyStep.PublishAccentChanged) { publishAccentChanged(accentHex) }
            }

        AccentApplyPlanRunner.run(
            plan = applyPlanFor(context),
            executeStep = { step -> workers.getValue(step)() },
        ) { failures ->
            val outcome = AccentApplyOutcome.of(accentHex, failures)
            if (outcome is AccentApplyOutcome.Torn) {
                val first = outcome.failures.first()
                log.warn(
                    "Accent apply torn at ${first.step} (hex=$trimmedHex, " +
                        "lastApplyOk=${state.lastApplyOk}); later steps (if any) were skipped",
                    first.error,
                )
            }
        }
    }

/**
     * Publish [AccentChangedTopic.TOPIC] once per usable open project AFTER
     * `state.lastApplyOk = true` so subscribers (toolbar stripe, toolbar chip)
     * only fire on a fully-painted apply. Extracted from [apply] to keep the
     * outer method's cognitive complexity below the IDE inspector's cap.
     *
     * Application-scoped: one apply may legitimately affect every open window;
     * per-project filtering belongs to the subscriber. Per-project try/catch
     * isolates Pattern B — a throwing subscriber must NOT tear down the apply
     * pipeline. Source is re-resolved per project so subscribers see THIS
     * window's resolution layer (project A may carry a project-override while
     * project B is global).
     */
    private fun publishAccentChanged(accentHex: AccentHex) {
        val publisher =
            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(AccentChangedTopic.TOPIC)
        for (openProject in ProjectManager.getInstance().openProjects) {
            if (!openProject.isUsable()) continue
            try {
                val source = AccentResolver.source(openProject)
                // Pattern K — the [AccentHex] parameter is the validated
                // proof; pass it straight through to the listener so
                // subscribers receive the typed wrapper and never see a raw
                // `String`.
                publisher.accentChanged(openProject, accentHex, source)
            } catch (exception: RuntimeException) {
                log.warn(
                    "AccentChangedTopic publish failed for ${openProject.name}",
                    exception,
                )
            }
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
     * inner [apply] call hops to the EDT internally via [AccentApplyPlanRunner], and that hop
     * does NOT rescue the preceding [resolveFocusedProject] + [AccentResolver.resolve]
     * steps (the first traverses EDT-only platform APIs, the second reads settings state
     * that may race off-EDT). [ProjectAccentSwapService.notifyExternalApply] is likewise
     * a bare volatile write with no dispatch. Callers MUST already be on the EDT.
     */
    @RequiresEdt
    fun applyForFocusedProject(context: AccentContext): String {
        val focusedProject = resolveFocusedProject()
        val hex = AccentResolver.resolve(focusedProject, context)
        // apply returns a validation flag. If the resolver hands back a hex
        // that fails shape validation (corrupted per-project override, manual
        // XML edit, future resolver bug), skip the swap-cache publish so the
        // cache does not drift to a hex that was never actually painted; the
        // apply call surfaces the user-visible notification for that case.
        // The torn-apply half of the same invariant (valid hex, mid-step
        // throw) is gated inside [ProjectAccentSwapService.notifyExternalApply],
        // which consults the persisted clean flag before recording the hex.
        val applied = applyFromHexString(hex)
        // Pattern D — regression lock: if you remove this gate, the swap cache
        // will publish hexes that `applyFromHexString` rejected (malformed XML,
        // manual edits, rotation palette bug) and drift from the paint state.
        // The `AccentApplicatorFocusedProjectTest` "skips swap cache publish
        // when applyFromHexString rejects the resolver output" test must fail
        // first if you touch this branch.
        if (applied) {
            ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
        }
        return hex
    }

    @RequiresEdt
    fun applyForFocusedProject(variant: AyuVariant): String = applyForFocusedProject(AccentContext.Ayu(variant))

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
                // Platform contract: `Project.getName()` is a safe bookmarked-name
                // accessor that does not throw even on a mid-dispose instance. A
                // prior `runCatching { project.name }.getOrNull()` here swallowed
                // the entire [Throwable] surface (Pattern B) for no defensive
                // benefit — drop the wrapper and read the property directly.
                probedName = project.name
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

    fun revertAll() {
        // Step order lives in [revertPlan]: integrations unwind
        // in the same IR-before-CGP-before-notify order the apply plan uses, and
        // there is deliberately NO repaint step — revertAll runs inside
        // lookAndFeelChanged before the new theme finishes loading, and forcing
        // a repaint NPEs in platform code (HeaderToolbarButtonLook) because UI
        // keys are cleared but not yet replaced. The platform repaints
        // everything after the theme switch.
        val workers: Map<AccentApplyStep, () -> Unit> =
            buildMap {
                put(AccentApplyStep.ClearUiAndExtensions) { clearReverseUiAndExtensions() }
                put(AccentApplyStep.RevertAlwaysOnEditorKeys) { revertAlwaysOnEditorKeys() }
                put(AccentApplyStep.RevertIndentRainbow) { IndentRainbowSync.revert() }
                put(AccentApplyStep.RevertCodeGlanceProViewport) {
                    CodeGlanceProIntegration.revertCodeGlanceProViewport()
                }
                put(AccentApplyStep.NotifyComponentTrees) {
                    // Cached JBColor instances survive a bare UIManager.put(key, null)
                    // clear. Publish the refresh topic per usable open project so
                    // subscribers (e.g. EditorScrollbarManager) reapply their
                    // customizations against the freshly-reverted UIManager state.
                    // notifyOnly stops short of IJSwingUtilities.updateComponentTreeUI —
                    // that path would crash here because LAF refresh is still
                    // mid-flight. Publishing a topic lets subscribers decide when to
                    // repaint.
                    for (project in ProjectManager.getInstance().openProjects) {
                        if (!project.isUsable()) continue
                        ComponentTreeRefresher.notifyOnly(project)
                    }
                }
            }

        // The revert plan bundles ContinuePerStep: each surface unwinds
        // independently (Pattern B) — one integration's failure must not block
        // the others or the downstream notifyOnly loop.
        AccentApplyPlanRunner.run(
            plan = revertPlan(),
            executeStep = { step -> workers.getValue(step)() },
        ) { failures ->
            for (failure in failures) {
                log.warn("Accent revert step ${failure.step} failed; remaining steps still ran", failure.error)
            }
        }
    }

    private fun clearReverseUiAndExtensions() {
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
        UIManager.put(KEY_TAB_UNDERLINE_HEIGHT, null)
        UIManager.put(KEY_TAB_UNDERLINE_ARC, null)

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

    private fun isExternalTintBlocked(
        state: AyuIslandsState,
        context: AccentContext,
    ): Boolean {
        val blocked = context == AccentContext.External && !state.isExternalChromeTintAllowed()
        if (blocked) {
            log.debug("External chrome tint allowance off; reverting tinted chrome surfaces")
        }
        return blocked
    }

    private fun applyElements(
        state: AyuIslandsState,
        accent: Color,
        context: AccentContext,
    ) {
        val variant = context.variant
        val externalTintBlocked = isExternalTintBlocked(state, context)
        for (element in EP_NAME.extensionList) {
            val enabled = !externalTintBlocked && ChromeTintContext.isToggleEnabled(state, element.id)
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
                // A partial apply can leave UIManager / live peers in a
                // mixed tinted+stock state; a subsequent `ChromeBaseColors.get()`
                // would capture those tinted values as the stock baseline and
                // poison the cache for the rest of the session. Roll back this
                // element so the next apply starts from a clean slate.
                // Narrow the catch to RuntimeException so
                // Error / CancellationException still propagate and don't get
                // demoted to a WARN line.
                try {
                    element.revert()
                } catch (revertException: RuntimeException) {
                    log.warn(
                        "Revert after failed apply also failed for ${element.displayName}",
                        revertException,
                    )
                }
            }
        }
    }

    /**
     * Thread [state] in from the outer [apply] capture rather than re-fetching via
     * `AyuIslandsSettings.getInstance()`: the outer call already read state at the
     * apply entry, so re-reading here risked observing a mid-apply settings mutation
     * and splitting behaviour between the EP chain and the tab-mode resolution.
     */
    private fun applyAlwaysOnUiKeys(
        state: AyuIslandsState,
        accent: Color,
    ) {
        for (key in ALWAYS_ON_UI_KEYS) {
            UIManager.put(key, accent)
        }

        // Contrast foreground for accent-background elements (GotItTooltip, buttons)
        val contrastForeground = if (ColorUtil.isDark(accent)) JBColor.WHITE else DARK_FOREGROUND
        UIManager.put("GotItTooltip.foreground", contrastForeground)
        UIManager.put("GotItTooltip.Button.foreground", contrastForeground)
        UIManager.put("GotItTooltip.Header.foreground", contrastForeground)

        // Darkened accent for default button borders (~15% darker)
        val darkenedAccent = ColorUtil.darker(accent, 1)
        UIManager.put("Button.default.focusedBorderColor", darkenedAccent)
        UIManager.put("Button.default.startBorderColor", darkenedAccent)
        UIManager.put("Button.default.endBorderColor", darkenedAccent)

        // Editor tab background tint (respects persisted tab mode, not gated by license)
        val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "MINIMAL")
        when (tabMode) {
            GlowTabMode.MINIMAL -> {
                UIManager.put(KEY_TAB_BACKGROUND, TRANSPARENT_TAB_BACKGROUND)
            }

            GlowTabMode.FULL -> {
                val tintedColor = ColorUtil.toAlpha(accent, TAB_ACCENT_BG_ALPHA)
                val tinted = JBColor(tintedColor, tintedColor)
                UIManager.put(KEY_TAB_BACKGROUND, tinted)
            }

            GlowTabMode.OFF -> {
                val variant = AyuVariant.detect()
                val neutralColor = variant?.let { Color.decode(it.neutralGray) }
                UIManager.put("EditorTabs.underlinedBorderColor", neutralColor)
                UIManager.put(KEY_TAB_BACKGROUND, TRANSPARENT_TAB_BACKGROUND)
            }
        }
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
        // Wrapped in a read action because Jupyter's NotebookEditorColorsListener
        // accesses PSI from globalSchemeChange, which requires read access.
        val application = ApplicationManager.getApplication()
        application.runReadAction {
            application
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
        }
    }

    private fun applyTabUnderline(
        state: AyuIslandsState,
        context: AccentContext,
    ) {
        if (isExternalTintBlocked(state, context)) {
            UIManager.put(KEY_TAB_UNDERLINE_HEIGHT, null)
            UIManager.put(KEY_TAB_UNDERLINE_ARC, null)
            return
        }
        val variant = context.variant
        val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "MINIMAL")
        if (tabMode == GlowTabMode.OFF && variant != null) {
            val scheme = EditorColorsManager.getInstance().globalScheme
            scheme.setColor(ColorKey.find("TAB_UNDERLINE"), Color.decode(variant.neutralGray))
        }

        val height = resolveUnderlineHeight(state)
        UIManager.put(KEY_TAB_UNDERLINE_HEIGHT, Integer.valueOf(height))

        val arc = UIManager.getInt("Island.arc").let { if (it > 0) it else DEFAULT_UNDERLINE_ARC }
        UIManager.put(KEY_TAB_UNDERLINE_ARC, Integer.valueOf(arc))

        log.info("Tab underline: height=${height}px, arc=${arc}px")
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

        val application = ApplicationManager.getApplication()
        application.runReadAction {
            application
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
        }
    }

    /**
     * Write-side repaint pass over the JVM's window list. Filters by
     * [Window.isDisplayable] — DELIBERATELY laxer than
     * [LiveChromeRefresher.forEachShowingWindow]'s `isShowing` filter:
     * a window that's displayable but not yet showing (e.g. mid-attach,
     * popup behind a modal) still has a valid AWT peer, so calling
     * `repaint()` is safe and the queued paint lands when the window flips
     * to showing. The chrome refresher's `isShowing` filter is read-side —
     * it walks descendants to collect peers, where an offscreen window has
     * no useful contribution and skipping is correct. Two predicates, two
     * purposes; do NOT collapse them into one helper.
     *
     * The `isDisplayable` check exists for a different reason: a disposed
     * window lingers briefly in [Window.getWindows] during shutdown races,
     * and calling `repaint()` on a disposed window is a no-op at best and
     * throws on some LAFs at worst. So this filter rejects only fully
     * disposed peers, NOT not-yet-shown ones.
     */
    private fun repaintAllWindows(windows: Array<Window>) {
        // Predicate intentionally differs from
        // LiveChromeRefresher.forEachShowingWindow.isShowing (read-side).
        // Write-side tolerates not-yet-showing windows so queued paint
        // lands when the peer flips to showing — see KDoc above.
        for (window in windows) {
            if (!window.isDisplayable) continue
            window.repaint()
        }
    }

    /**
     * Public-to-the-module entry into [CodeGlanceProIntegration.syncCodeGlanceProViewport]
     * for the project-focus-swap path.
     * [ProjectAccentSwapService.handleWindowActivated] calls this on a same-hex
     * focus swap to push the per-project accent into the app-scoped CGP
     * `CodeGlanceConfigService` cache without re-running the full UIManager
     * apply (which is already correct for the unchanged hex).
     *
     * `ComponentTreeRefresher.walkAndNotify` alone cannot push the new accent
     * into the app-scoped CGP cache because CGP does not subscribe to
     * `ComponentTreeRefreshedTopic`. Calling this wrapper directly from the
     * swap service achieves the cache write without the apply path's
     * redundant work.
     */
    internal fun syncCodeGlanceProViewportForSwap(
        hex: String,
        context: AccentContext? = null,
    ) {
        CodeGlanceProIntegration.syncCodeGlanceProViewport(hex, context)
    }

    /**
     * String-accepting entry point for callers that hold a raw hex from an
     * untrusted boundary (persisted XML, settings-panel input, resolver
     * output that is still `String`).
     * Validates the shape via [AccentHex.of], surfaces the user-visible
     * notification + returns `false` on rejection, and forwards to [apply]
     * on success.
     *
     * Deliberately NOT named `apply(String)` — MockK's `every { apply(any()) }`
     * can't resolve across overloads of the same name, so tests that lock in
     * the cache-publish ordering against the apply path would all break.
     */
    fun applyFromHexString(accentHex: String): Boolean {
        val accent =
            AccentHex.of(accentHex) ?: run {
                log.warn("AccentApplicator.apply: invalid hex '$accentHex' — skipping apply")
                // User-visible notification for the rejected hex so per-project
                // XML corruption, manual edits, and rotation palette bugs do not
                // silently turn chrome tinting off. Wrapped in a narrow try/catch
                // so a notification subsystem hiccup cannot cascade into the
                // caller (settings panel, rotation tick, startup).
                //
                // Pattern B + log-level escalation: narrow the catch to
                // [RuntimeException] so [OutOfMemoryError] /
                // [NoClassDefFoundError] propagate, and raise the fallback log
                // from DEBUG to WARN — this is the user-visible fallback for
                // per-project XML corruption / manual edits / rotation palette
                // bugs. If THAT notification fails to post, the user sees a
                // silent no-op; the WARN trace is the only thread to pull on
                // during triage.
                try {
                    Notifications.Bus.notify(
                        Notification(
                            NOTIFICATION_GROUP_ID,
                            "Ayu accent rejected",
                            "Hex '$accentHex' is not a valid #RRGGBB.",
                            NotificationType.WARNING,
                        ),
                    )
                } catch (exception: RuntimeException) {
                    log.warn("AccentApplicator invalid-hex notification failed to post", exception)
                }
                return false
            }
        apply(accent)
        return true
    }
}

internal fun resolveUnderlineHeight(state: AyuIslandsState): Int {
    val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "MINIMAL")
    if (tabMode == GlowTabMode.OFF) return state.tabUnderlineHeight

    if (state.tabUnderlineGlowSync && state.glowEnabled) {
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        return state.getWidthForStyle(style)
    }
    return state.tabUnderlineHeight
}

/**
 * File-scope extension because [AccentApplicator] is at the cap of its
 * `TooManyFunctions` budget; moving this trivial guard out of the object
 * frees one function slot for `publishAccentChanged` extracted from [AccentApplicator.apply].
 * Shape preserved exactly so the 6+ existing call-sites and the tests'
 * commentary stay accurate.
 */
private fun com.intellij.openapi.project.Project.isUsable(): Boolean = !isDefault && !isDisposed
