package dev.ayuislands.accent

import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.elements.AbstractChromeElement
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.integration.propagateFailure
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeChange
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.ui.ComponentTreeRefresher
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Window
import java.util.concurrent.CancellationException
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

    private const val KEY_TAB_UNDERLINE_HEIGHT = "EditorTabs.underlineHeight"
    private const val KEY_TAB_UNDERLINE_ARC = "EditorTabs.underlineArc"
    private const val DEFAULT_UNDERLINE_ARC = 8
    private val EDITOR_SCHEME_ELEMENTS =
        setOf(
            AccentElementId.INLAY_HINTS,
            AccentElementId.CARET_ROW,
            AccentElementId.PROGRESS_BAR,
            AccentElementId.SCROLLBAR,
            AccentElementId.LINKS,
            AccentElementId.BRACKET_MATCH,
            AccentElementId.MATCHING_TAG,
        )
    // CGP viewport defaults live in [CodeGlanceProIntegration] — they are
    // exclusively read inside that peer object. See
    // [CodeGlanceProIntegration.CGP_DEFAULT_VIEWPORT_COLOR] for the javap provenance and
    // re-verification recipe.

    private val alwaysOnOwner = EditorSchemeOwner.AlwaysOn

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

    // Always-on editor ColorKeys (not per-element toggleable)
    private val ALWAYS_ON_EDITOR_COLOR_KEYS =
        listOf(
            ColorKey.find("BUTTON_BACKGROUND"),
            ColorKey.find("WARNING_FOREGROUND"),
            ColorKey.find("TAB_UNDERLINE"),
        )

    private enum class AttributeTarget {
        FOREGROUND,
        EFFECT_COLOR,
        ERROR_STRIPE,
    }

    private data class AttributeOverride(
        val key: String,
        val targets: Set<AttributeTarget>,
    )

    // Always-on editor TextAttributesKey overrides
    private val ALWAYS_ON_EDITOR_ATTR_OVERRIDES =
        listOf(
            AttributeOverride("BOOKMARKS_ATTRIBUTES", setOf(AttributeTarget.ERROR_STRIPE)),
            AttributeOverride("DEBUGGER_INLINED_VALUES_MODIFIED", setOf(AttributeTarget.FOREGROUND)),
            AttributeOverride("LIVE_TEMPLATE_ATTRIBUTES", setOf(AttributeTarget.EFFECT_COLOR)),
            AttributeOverride("LOG_INFO_OUTPUT", setOf(AttributeTarget.FOREGROUND)),
            AttributeOverride("RUNTIME_ERROR", setOf(AttributeTarget.EFFECT_COLOR)),
            AttributeOverride("SMART_COMPLETION_STATISTICAL_MATCHED_ITEM", setOf(AttributeTarget.FOREGROUND)),
            AttributeOverride("TEXT_STYLE_WARNING", setOf(AttributeTarget.EFFECT_COLOR)),
            AttributeOverride("TODO_DEFAULT_ATTRIBUTES", setOf(AttributeTarget.FOREGROUND)),
            AttributeOverride(
                "WARNING_ATTRIBUTES",
                setOf(AttributeTarget.EFFECT_COLOR, AttributeTarget.ERROR_STRIPE),
            ),
        )

    /** Apply synchronously on EDT, retaining restart metadata and returning this invocation's outcome. */
    @RequiresEdt
    internal fun apply(
        accentHex: AccentHex,
        participation: AccentApplyParticipation? = null,
    ): AccentApplyOutcome {
        check(SwingUtilities.isEventDispatchThread()) { "Accent application must run on EDT" }
        if (AccentApplyScope.active != null) {
            val failure = IllegalStateException("Accent application is already in progress")
            log.warn("Skipping nested accent application", failure)
            return AccentApplyOutcome.Torn(
                accentHex,
                listOf(AccentApplyStepFailure(AccentApplyStep.ApplyAlwaysOnUiKeys, failure)),
            )
        }
        val scope = AccentApplyScope()
        try {
            scope.open()
            participation?.let { scope.record(it.checkpoint) }
            val trimmedHex = accentHex.value
            val accent = accentHex.toColor()
            val state = AyuIslandsSettings.getInstance().state
            val context = AccentContext.detect()
            val isChromeAllowed: Boolean by lazy(LazyThreadSafetyMode.NONE) {
                LicenseChecker.isLicensedOrGrace()
            }

            // Cancellation can prevent outcome delivery after any paint or publication step.
            ProjectAccentSwapService.getInstance().invalidateAppliedAccent()

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
            val containedFailures = mutableListOf<AccentApplyStepFailure>()
            val recordFailure: (AccentApplyStepFailure) -> Unit = { failure ->
                containedFailures += failure
                log.warn("Accent apply torn at ${failure.step} (hex=$trimmedHex)", failure.error)
            }
            val workers: Map<AccentApplyStep, () -> Unit> =
                buildMap {
                    put(AccentApplyStep.ApplyAlwaysOnUiKeys) {
                        applyAlwaysOnUiKeys(state, accent, context, isChromeAllowed, scope.uiDefaults)
                    }
                    put(AccentApplyStep.ApplyElements) {
                        applyElements(
                            state,
                            accent,
                            checkNotNull(context) { "ApplyElements planned without accent context" },
                            isChromeAllowed,
                            onFailure = recordFailure,
                        )
                    }
                    put(AccentApplyStep.ApplyTabUnderline) {
                        applyTabUnderline(
                            state,
                            checkNotNull(context) { "ApplyTabUnderline planned without accent context" },
                            isChromeAllowed,
                            scope.uiDefaults,
                        )
                    }
                    put(AccentApplyStep.SyncIndentRainbow) {
                        IndentRainbowSync
                            .apply(
                                checkNotNull(context) { "SyncIndentRainbow planned without accent context" },
                                trimmedHex,
                            ).propagateFailure()
                    }
                    put(AccentApplyStep.SyncCodeGlanceProViewport) {
                        CodeGlanceProIntegration
                            .syncCodeGlanceProViewport(trimmedHex, context)
                            .propagateFailure()
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
                    put(AccentApplyStep.MarkApplyClean) {
                        participation?.beforeAcceptance(AccentApplyOutcome.of(accentHex, containedFailures))
                        scope.checkWritable()
                        state.lastApplyOk = containedFailures.isEmpty()
                        // Every write ran: a contained element failure stays torn but keeps its colors.
                        if (participation == null) scope.commit() else participation.commit(scope)
                    }
                    put(AccentApplyStep.PublishAccentChanged) {
                        if (containedFailures.isEmpty()) {
                            publishAccentChanged(accentHex, recordFailure)
                        }
                    }
                }

            val failures =
                scope.restoringOnFailure {
                    AccentApplyPlanRunner.runNow(
                        plan = applyPlanFor(context),
                        executeStep = { step -> scope.runStep { workers.getValue(step)() } },
                    )
                }
            for ((step, error) in failures) {
                log.warn("Accent apply torn at $step (hex=$trimmedHex)", error)
            }
            val outcome = AccentApplyOutcome.of(accentHex, containedFailures + failures)
            if (!outcome.visualsApplied) {
                scope
                    .restoreAfterTear((containedFailures + failures).map(AccentApplyStepFailure::error))
                    ?.let { recovery ->
                        recordFailure(AccentApplyStepFailure(AccentApplyStep.ApplyAlwaysOnUiKeys, recovery))
                    }
                // Restored defaults reach already painted surfaces only on repaint.
                runCatchingPreservingCancellation { repaintAllWindows(Window.getWindows()) }
                    .onFailure { error -> recordFailure(AccentApplyStepFailure(AccentApplyStep.RepaintWindows, error)) }
            }
            return AccentApplyOutcome.of(accentHex, containedFailures + failures)
        } finally {
            scope.close()
            AccentLifecycle.afterApply()
        }
    }

    /** Resolve the focused project's existing override chain and update the cache from its outcome. */
    @RequiresEdt
    internal fun applyForFocusedProject(
        context: AccentContext,
        participation: AccentApplyParticipation? = null,
    ): AccentApplyOutcome {
        val focusedProject = resolveFocusedProject()
        val hex = AccentResolver.resolve(focusedProject, context)
        val outcome = applyFromHexString(hex, participation)
        ProjectAccentSwapService.getInstance().notifyExternalApply(outcome)
        return outcome
    }

    @RequiresEdt
    internal fun applyForFocusedProject(variant: AyuVariant): AccentApplyOutcome =
        applyForFocusedProject(AccentContext.Ayu(variant))

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
     *  1. [ProjectUtil.getActiveProject] maps the active AWT window back through its owner
     *     chain to the project frame. This keeps project-modal dialogs such as Settings
     *     bound to the project that opened them even while the owner frame itself is inactive.
     *  2. First `WindowManager.allProjectFrames` whose ancestor window reports
     *     `Window.isActive`. Iterates all project frames, calls
     *     `SwingUtilities.getWindowAncestor(frame.component)`, and matches where
     *     `window.isActive` is true. (The sibling swap-service uses the same frame
     *     enumeration but matches by reference equality against a listener-provided
     *     window — identical iteration, different predicate.)
     *  3. [IdeFocusManager.lastFocusedFrame]'s project — fallback when the OS-active
     *     scan returns null for any reason: the IDE is alt-tabbed out, the window
     *     ancestor lookup is temporarily unavailable, or `WindowManager` itself is
     *     null during startup / shutdown.
     *  4. First non-default non-disposed open project — pre-focus-manager startup or
     *     shutdown edge cases.
     *  5. `null` — no project open; resolver will return the global accent.
     */
    @RequiresEdt
    internal fun resolveFocusedProject(): com.intellij.openapi.project.Project? {
        ProjectUtil.getActiveProject()?.takeIf { it.isUsable() }?.let { return it }
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
        AccentApplyPlanRunner.dispatch { AccentLifecycle.request { revertNow() } }
    }

    internal fun revertNow() {
        // Step order lives in [revertPlan]: integrations unwind
        // in the same IR-before-CGP-before-notify order the apply plan uses, and
        // there is deliberately NO repaint step — revertAll runs inside
        // lookAndFeelChanged before the new theme finishes loading, and forcing
        // a repaint NPEs in platform code (HeaderToolbarButtonLook) because UI
        // keys are cleared but not yet replaced. The platform repaints
        // everything after the theme switch.
        val workers: Map<AccentApplyStep, () -> Unit> =
            buildMap {
                put(AccentApplyStep.ClearUiAndExtensions) {
                    AyuEditorSchemeScope.beginAccentCleanup()
                    val clearResult =
                        runCatchingPreservingCancellation {
                            UiDefaultsRecovery.restorePending()
                            clearReverseUiAndExtensions()
                        }
                    clearResult.exceptionOrNull()?.let { error ->
                        AyuEditorSchemeScope.retainAllAccentClaims()
                        throw error
                    }
                    val failure = clearResult.getOrThrow() ?: return@put
                    val hasUnattributedEditorFailure =
                        failure.elementIds.any { it in EDITOR_SCHEME_ELEMENTS } &&
                            !AyuEditorSchemeScope.hasAccentCleanupFailures()
                    if (hasUnattributedEditorFailure) {
                        AyuEditorSchemeScope.retainAllAccentClaims()
                    }
                    throw failure
                }
                put(AccentApplyStep.RevertAlwaysOnEditorKeys) {
                    try {
                        revertAlwaysOnEditorKeys()
                    } finally {
                        AyuEditorSchemeScope.releaseCleanAccentClaims()
                    }
                }
                put(AccentApplyStep.RevertIndentRainbow) {
                    IndentRainbowSync.revert().propagateFailure()
                }
                put(AccentApplyStep.RevertCodeGlanceProViewport) {
                    CodeGlanceProIntegration
                        .revertCodeGlanceProViewport()
                        .propagateFailure()
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
                    ProjectManager
                        .getInstance()
                        .openProjects
                        .filter { it.isUsable() }
                        .forEach(ComponentTreeRefresher::notifyOnly)
                }
            }

        // The revert plan bundles ContinuePerStep: each surface unwinds
        // independently (Pattern B) — one integration's failure must not block
        // the others or the downstream notifyOnly loop.
        AccentApplyPlanRunner.run(
            plan = revertPlan(),
            executeStep = { step -> workers.getValue(step)() },
        ) { failures ->
            for ((step, error) in failures) {
                log.warn("Accent revert step $step failed; remaining steps still ran", error)
            }
        }
    }

    private fun clearReverseUiAndExtensions(): ElementRevertFailure? {
        val failedExternalReverts = mutableSetOf<AccentElementId>()
        var firstFailure = captureCleanupFailure { uiDefaultsOwner.restore() }
        for (element in EP_NAME.extensionList) {
            val failure = captureCleanupFailure { element.revert() } ?: continue
            failedExternalReverts.add(element.id)
            firstFailure = combineUiFailures(firstFailure, failure)
            if (failure is RuntimeException && failure.isAccentCancellation()) continue
            log.warn(
                "Failed to revert ${element.displayName}",
                failure,
            )
        }
        captureCleanupFailure { ExternalChromeOwnership.finishRevert(failedExternalReverts) }?.let {
            firstFailure = combineUiFailures(firstFailure, it)
        }
        val failure = firstFailure ?: return null
        if (failure is RuntimeException) failure.rethrowIfCancelled()
        return ElementRevertFailure(failedExternalReverts, failure)
    }

    private fun applyElements(
        state: AyuIslandsState,
        accent: Color,
        context: AccentContext,
        isPremiumAllowed: Boolean,
        onFailure: (AccentApplyStepFailure) -> Unit,
    ) {
        if (context == AccentContext.External) {
            EP_NAME.extensionList
                .filter { it.id in EDITOR_SCHEME_ELEMENTS }
                .forEach { element ->
                    AyuEditorSchemeScope.observeElementEnabled(
                        element.id,
                        ChromeTintContext.isToggleEnabled(state, element.id),
                    )
                }
            ExternalChromeOwnership.apply(
                elements = EP_NAME.extensionList,
                state = state,
                accent = accent,
                isAllowed = canTintExternalChrome(state, context, isPremiumAllowed),
                onFailure = onFailure,
            )
            return
        }
        ExternalChromeOwnership.clearOwnership()

        val variant = context.variant
        for (element in EP_NAME.extensionList) {
            withAccentSegment { applyElement(state, element, accent, variant, isPremiumAllowed) }?.let(onFailure)
        }
    }

    private fun applyElement(
        state: AyuIslandsState,
        element: AccentElement,
        accent: Color,
        variant: AyuVariant?,
        isPremiumAllowed: Boolean,
    ): AccentApplyStepFailure? {
        val checkpointRestore = element.checkpointRollback()
        AyuEditorSchemeScope.observeElementEnabled(
            element.id,
            ChromeTintContext.isToggleEnabled(state, element.id),
        )
        if (element.id.group == AccentGroup.CHROME && !isPremiumAllowed) {
            return neutralizeOrRevert(element, variant, checkpointRestore ?: {})
        }
        if (!isElementEnabled(state, element.id, isPremiumAllowed)) {
            return neutralizeOrRevert(element, variant, checkpointRestore ?: {})
        }
        val conflict = ConflictRegistry.getConflictFor(element.id)
        if (conflict != null && !canForceOverride(state, element.id, isPremiumAllowed)) {
            return neutralizeOrRevert(element, variant, checkpointRestore ?: {})
        }
        if (conflict != null) {
            log.warn(
                "Force-overriding ${conflict.pluginDisplayName} conflict for ${element.displayName}",
            )
        }
        return captureAccentFailure(
            step = AccentApplyStep.ApplyElements,
            operation = "Apply ${element.displayName}",
            rollback = checkpointRestore ?: { element.revert() },
        ) { element.apply(accent) }
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
        context: AccentContext?,
        isChromeAllowed: Boolean,
        uiDefaults: UiDefaultsCheckpoint,
    ) {
        val tabMode = effectiveTabMode(state, isChromeAllowed)
        val policy =
            accentUiPolicy(
                context == AccentContext.External,
                state.externalThemeAccentOverrideEnabled,
                state.isExternalChromeTintAllowed() && isChromeAllowed,
                tabMode,
            )
        val palette =
            accentUiPalette(
                AccentUiInput(
                    accent = accent,
                    neutral = context?.variant?.let { Color.decode(it.neutralGray) },
                    tabMode = tabMode,
                    foregroundOnDark = JBColor.WHITE.rgb,
                    policy = policy,
                ),
            )
        uiDefaultsOwner.restore(AccentUiKeys.colors - palette.keys, uiDefaults)
        for ((key, rgba) in palette) {
            val color = rgba?.let { JBColor(Color(it, true), Color(it, true)) }
            uiDefaultsOwner.put(key, color, uiDefaults)
        }
    }

    private fun applyAlwaysOnEditorKeys(accent: Color) {
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return

        // ColorKey entries
        for (colorKey in ALWAYS_ON_EDITOR_COLOR_KEYS) {
            AyuEditorSchemeScope.writeColor(scheme, alwaysOnOwner, colorKey, accent)
        }

        // TextAttributesKey entries -- clone existing, override only accent properties
        for ((key, targets) in ALWAYS_ON_EDITOR_ATTR_OVERRIDES) {
            val attrKey = TextAttributesKey.find(key)
            val existing = scheme.getAttributes(attrKey)
            val updated = existing?.clone() ?: TextAttributes()
            if (AttributeTarget.FOREGROUND in targets) updated.foregroundColor = accent
            if (AttributeTarget.EFFECT_COLOR in targets) updated.effectColor = accent
            if (AttributeTarget.ERROR_STRIPE in targets) updated.errorStripeColor = accent
            AyuEditorSchemeScope.writeAttributes(scheme, alwaysOnOwner, attrKey, updated)
        }

        EditorSchemeChange.publish()
    }

    private fun applyTabUnderline(
        state: AyuIslandsState,
        context: AccentContext,
        isChromeAllowed: Boolean,
        uiDefaults: UiDefaultsCheckpoint,
    ) {
        val variant = context.variant
        val tabMode = effectiveTabMode(state, isChromeAllowed)
        val policy =
            accentUiPolicy(
                context == AccentContext.External,
                state.externalThemeAccentOverrideEnabled,
                state.isExternalChromeTintAllowed() && isChromeAllowed,
                tabMode,
            )
        if (!policy.overrideTabGeometry) {
            uiDefaultsOwner.restore(setOf(KEY_TAB_UNDERLINE_HEIGHT, KEY_TAB_UNDERLINE_ARC), uiDefaults)
            return
        }
        if (tabMode == GlowTabMode.OFF && variant != null) {
            val scheme = AyuEditorSchemeScope.activeScheme()
            if (scheme != null) {
                AyuEditorSchemeScope.writeColor(
                    scheme,
                    alwaysOnOwner,
                    ColorKey.find("TAB_UNDERLINE"),
                    Color.decode(variant.neutralGray),
                )
            }
        }

        val height =
            if (isChromeAllowed) {
                resolveUnderlineHeight(state, tabMode)
            } else {
                AyuIslandsState.DEFAULT_TAB_UNDERLINE_HEIGHT
            }
        uiDefaultsOwner.put(KEY_TAB_UNDERLINE_HEIGHT, Integer.valueOf(height), uiDefaults)

        val arc = UIManager.getInt("Island.arc").let { if (it > 0) it else DEFAULT_UNDERLINE_ARC }
        uiDefaultsOwner.put(KEY_TAB_UNDERLINE_ARC, Integer.valueOf(arc), uiDefaults)

        log.info("Tab underline: height=${height}px, arc=${arc}px")
    }

    private fun revertAlwaysOnEditorKeys() {
        AyuEditorSchemeScope.restore(alwaysOnOwner)

        EditorSchemeChange.publish()
    }

    private class ElementRevertFailure(
        val elementIds: Set<AccentElementId>,
        cause: Throwable,
    ) : RuntimeException(cause)

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

    /** Reject malformed input without mutating preferences or the restart cache. */
    @RequiresEdt
    internal fun applyFromHexString(
        accentHex: String,
        participation: AccentApplyParticipation? = null,
    ): AccentApplyOutcome {
        check(SwingUtilities.isEventDispatchThread()) { "Accent application must run on EDT" }
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
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: RuntimeException) {
                    log.warn("AccentApplicator invalid-hex notification failed to post", exception)
                }
                return AccentApplyOutcome.Rejected(accentHex)
            }
        return apply(accent, participation)
    }

    /** Complete inline on EDT or after one queued EDT turn; cancellation does not invoke the callback. */
    internal fun requestApply(
        accentHex: String,
        onComplete: (AccentApplyOutcome) -> Unit,
    ) {
        AccentApplyPlanRunner.dispatch { onComplete(applyFromHexString(accentHex)) }
    }
}

internal object ExternalChromeOwnership {
    private val log = logger<ExternalChromeOwnership>()
    private val ownedElements: MutableSet<AccentElementId> =
        java.util.EnumSet.noneOf(AccentElementId::class.java)
    private val typeMismatchLogged: MutableSet<AccentElementId> =
        java.util.EnumSet.noneOf(AccentElementId::class.java)
    private val pending = mutableListOf<OwnershipCheckpoint>()
    private var target: UiDefaultsTarget? = null

    fun apply(
        elements: List<AccentElement>,
        state: AyuIslandsState,
        accent: Color,
        isAllowed: Boolean,
        onFailure: (AccentApplyStepFailure) -> Unit,
    ) {
        prepareTarget()
        if (!isAllowed) {
            revertAll(elements, onFailure)
            return
        }
        for (element in elements) {
            if (element.id.group != AccentGroup.CHROME) continue
            withAccentSegment {
                withRecoveredOwnership(element) { applyElement(element, state, accent) }
            }?.let(onFailure)
        }
    }

    fun clearOwnership() {
        clearMembership(emptySet())
    }

    fun finishRevert(failedElements: Set<AccentElementId>) {
        clearMembership(failedElements)
    }

    @TestOnly
    fun resetForTests() {
        uiDefaultsOwner.restore()
        ownedElements.clear()
        pending.clear()
        target = null
        typeMismatchLogged.clear()
    }

    private fun clearMembership(failedElements: Set<AccentElementId>) {
        prepareTarget()
        val ids = (ownedElements.toList() + pending.map { it.id }).distinct().filterNot { it in failedElements }
        var failure: Throwable? = null
        for (id in ids) {
            val recovery =
                captureCleanupFailure {
                    withAccentSegment {
                        recoverOwnership(id)
                        changeOwnership(id, false)
                    }
                }
            if (recovery != null) failure = combineUiFailures(failure, recovery)
        }
        failure?.let { throw it }
    }

    private fun prepareTarget() {
        val scope = AccentApplyScope.active?.takeUnless { it.isCommitted }
        scope?.checkWritable()
        if (target?.isCurrent() != true) {
            ownedElements.clear()
            target = UiDefaultsTarget()
        }
    }

    private fun recoverOwnership(id: AccentElementId) {
        val related = pending.filter { it.id == id }.toMutableList()
        if (related.isEmpty()) return
        val recovery = {
            try {
                restoreAccentCheckpoints(related)
            } finally {
                pending.removeAll { !it.hasPending }
            }
        }
        val scope = AccentApplyScope.active?.takeUnless { it.isCommitted }
        if (scope == null) recovery() else scope.recover(recovery)
    }

    private fun withRecoveredOwnership(
        element: AccentElement,
        action: () -> AccentApplyStepFailure?,
    ): AccentApplyStepFailure? {
        val recovery =
            captureAccentFailure(AccentApplyStep.ApplyElements, "Recover external chrome ${element.displayName}") {
                recoverOwnership(element.id)
            }
        return recovery ?: action()
    }

    private fun changeOwnership(
        id: AccentElementId,
        owned: Boolean,
    ) {
        val scope = AccentApplyScope.active?.takeUnless { it.isCommitted }
        scope?.checkWritable()
        val previous = id in ownedElements
        if (previous == owned) return
        scope?.record(OwnershipCheckpoint(id, previous, owned, scope.segment))
        if (owned) ownedElements.add(id) else ownedElements.remove(id)
    }

    private fun applyElement(
        element: AccentElement,
        state: AyuIslandsState,
        accent: Color,
    ): AccentApplyStepFailure? {
        val conflict = ConflictRegistry.getConflictFor(element.id)
        val isForced = element.id.name in state.forceOverrides
        if (!ChromeTintContext.isToggleEnabled(state, element.id) || (conflict != null && !isForced)) {
            return revertOne(element)
        }
        if (conflict != null) {
            log.warn("Force-overriding ${conflict.pluginDisplayName} conflict for ${element.displayName}")
        }
        val chromeElement = element as? AbstractChromeElement
        if (chromeElement == null) {
            logTypeMismatch(element)
            return null
        }
        val checkpointRestore = element.checkpointRollback()
        return captureAccentFailure(
            step = AccentApplyStep.ApplyElements,
            operation = "Apply external chrome ${element.displayName}",
            rollback =
                checkpointRestore ?: {
                    val recovery = revertOne(element)
                    if (recovery != null) throw recovery.error
                },
        ) {
            chromeElement.applyExternal(accent) { changeOwnership(element.id, true) }
        }
    }

    private fun logTypeMismatch(element: AccentElement) {
        if (!typeMismatchLogged.add(element.id)) return
        log.warn(
            "Skipping external chrome element ${element.displayName}: " +
                "implementation does not expose mutation ownership",
        )
    }

    private fun revertAll(
        elements: List<AccentElement>,
        onFailure: (AccentApplyStepFailure) -> Unit,
    ) {
        if (ownedElements.isEmpty() && pending.isEmpty()) return
        for (element in elements) {
            withAccentSegment { withRecoveredOwnership(element) { revertOne(element) } }?.let(onFailure)
        }
    }

    private fun revertOne(element: AccentElement): AccentApplyStepFailure? {
        if (element.id !in ownedElements) return null
        val checkpointRestore = element.checkpointRollback()
        changeOwnership(element.id, false)
        return captureAccentFailure(
            step = AccentApplyStep.ApplyElements,
            operation = "Restore external chrome ${element.displayName}",
            rollback = checkpointRestore ?: { changeOwnership(element.id, true) },
        ) { element.revert() }
    }

    private class OwnershipCheckpoint(
        val id: AccentElementId,
        private val previous: Boolean,
        private val expected: Boolean,
        private val segment: AccentApplyScope.Segment,
    ) : AccentCheckpoint {
        private val capturedTarget = checkNotNull(target)
        private val dependencies = mutableListOf<AccentCheckpoint>()
        override var hasPending = true
            private set

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            if (!hasPending) return
            if (!capturedTarget.isCurrent()) {
                commit()
                return
            }
            val related = blockedBy.filter { it in segment || it is OwnershipCheckpoint && it.id == id }
            for (checkpoint in related.asReversed()) {
                if (dependencies.none { it === checkpoint }) dependencies += checkpoint
            }
            check(related.isEmpty()) { "External chrome ownership is waiting for surface recovery: $id" }
            restoreAccentCheckpoints(dependencies)
            if ((id in ownedElements) == expected) {
                if (previous) ownedElements.add(id) else ownedElements.remove(id)
            }
            commit()
        }

        override fun commit() {
            hasPending = false
            dependencies.clear()
        }

        override fun retain() {
            if (hasPending && this !in pending) pending += this
        }
    }
}

/** Collects cleanup cancellation until independent entries settle; VM failures still abort immediately. */
private inline fun captureCleanupFailure(action: () -> Unit): Throwable? {
    val failure = runCatching(action).exceptionOrNull()
    if (failure is VirtualMachineError) throw failure
    return failure
}

private fun effectiveTabMode(
    state: AyuIslandsState,
    isChromeAllowed: Boolean,
): GlowTabMode =
    if (isChromeAllowed) {
        GlowTabMode.fromName(state.glowTabMode ?: GlowTabMode.MINIMAL.name)
    } else {
        GlowTabMode.MINIMAL
    }

private fun canTintExternalChrome(
    state: AyuIslandsState,
    context: AccentContext,
    isChromeAllowed: Boolean,
): Boolean =
    context != AccentContext.External ||
        (state.isExternalChromeTintAllowed() && isChromeAllowed)

private fun isElementEnabled(
    state: AyuIslandsState,
    elementId: AccentElementId,
    isPremiumAllowed: Boolean,
): Boolean = !isPremiumAllowed || ChromeTintContext.isToggleEnabled(state, elementId)

private fun canForceOverride(
    state: AyuIslandsState,
    elementId: AccentElementId,
    isPremiumAllowed: Boolean,
): Boolean = isPremiumAllowed && elementId.name in state.forceOverrides

internal fun resolveUnderlineHeight(
    state: AyuIslandsState,
    tabMode: GlowTabMode,
): Int {
    if (tabMode == GlowTabMode.OFF) return state.tabUnderlineHeight

    if (state.tabUnderlineGlowSync && state.glowEnabled) {
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        return state.getWidthForStyle(style)
    }
    return state.tabUnderlineHeight
}

/**
 * Publish [AccentChangedTopic.TOPIC] once per usable open project AFTER
 * `state.lastApplyOk = true` so subscribers (toolbar stripe, toolbar chip)
 * only fire on a fully-painted apply. Extracted from [AccentApplicator.apply] to keep the
 * outer method's cognitive complexity below the IDE inspector's cap.
 *
 * Application-scoped: one apply may legitimately affect every open window;
 * per-project filtering belongs to the subscriber. Per-project try/catch
 * isolates Pattern B — a throwing subscriber must NOT tear down the apply
 * pipeline. Source is re-resolved per project so subscribers see THIS
 * window's resolution layer (project A may carry a project-override while
 * project B is global).
 */
private fun publishAccentChanged(
    accentHex: AccentHex,
    onFailure: (AccentApplyStepFailure) -> Unit,
) {
    val publisher =
        ApplicationManager
            .getApplication()
            .messageBus
            .syncPublisher(AccentChangedTopic.TOPIC)
    for (openProject in ProjectManager.getInstance().openProjects) {
        if (!openProject.isUsable()) continue
        captureAccentFailure(
            AccentApplyStep.PublishAccentChanged,
            "Publish accent for ${openProject.name}",
        ) {
            val source = AccentResolver.source(openProject)
            publisher.accentChanged(openProject, accentHex, source)
        }?.let(onFailure)
    }
}

private fun com.intellij.openapi.project.Project.isUsable(): Boolean = !isDefault && !isDisposed
