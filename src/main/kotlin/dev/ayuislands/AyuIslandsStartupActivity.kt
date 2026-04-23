package dev.ayuislands

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import dev.ayuislands.ui.ComponentTreeRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities

internal class AyuIslandsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val themeName = AyuVariant.currentThemeName()
        LOG.info("Ayu Islands loaded — active theme: $themeName, project: ${project.name}")

        val variant = AyuVariant.fromThemeName(themeName) ?: return
        val settings = AyuIslandsSettings.getInstance()

        // Belt-and-suspenders: accent is pre-applied in appFrameCreated() (no gold flash),
        // but project-dependent features (BracketFadeManager, editor TextAttributesKey overrides)
        // need this second idempotent call once a project context exists.
        // Project/language overrides win over the global accent; AccentResolver centralizes the chain.
        //
        // Resolve for the FOCUSED project rather than THIS project — with multiple projects booting
        // together, each StartupActivity writes to the single JVM-wide UIManager, so last-writer-wins.
        // If core-terraform (Lavender override) and ayu-jetbrains (Cyan override) open simultaneously
        // and core-terraform's activity finishes last, chrome ends up Lavender even when the user's
        // focused window is ayu-jetbrains. Reading the focused project here aligns the write with
        // what the user actually sees. Falls back to THIS project when focus cascade hasn't settled
        // yet (single-project launch or pre-focus-manager startup).
        //
        // Dispatch to the EDT: every step in this triplet has an EDT precondition.
        //  - AccentApplicator.resolveFocusedProject is @RequiresEdt — it traverses
        //    WindowManager, IdeFocusManager, ProjectManager, all EDT-only APIs.
        //  - AccentApplicator.apply is @RequiresEdt — its KDoc lines 200-205
        //    explicitly state the helper does NOT self-dispatch the pre-apply steps,
        //    and the inner invokeLaterSafe hop inside apply() only batches the
        //    UIManager/editor writes; it does not rescue anything that runs before.
        //  - ProjectAccentSwapService.install registers an AWT listener whose
        //    WINDOW_ACTIVATED handler expects to fire after the initial apply's
        //    UIManager state is visible; calling it off the EDT would let the first
        //    real activation race an in-flight apply.
        //  - ProjectAccentSwapService.notifyExternalApply is a bare volatile write
        //    with no dispatch; per the AccentApplicator KDoc (lines 203-205) callers
        //    must already be on the EDT so the lastAppliedHex cache publishes in the
        //    same ordering as the apply that preceded it — otherwise the first
        //    WINDOW_ACTIVATED after startup would re-apply the same color it just
        //    painted.
        // ProjectActivity.execute runs on a background coroutine, so the full
        // compute-apply-publish triplet must be wrapped in a single EDT block.
        // Isolate the triplet so a throw in resolveFocusedProject / apply /
        // install / notifyExternalApply doesn't abort the rest of startup
        // (font preset apply, language-detector warmup, ModuleRootListener
        // subscribe, scrollbar manager init, ComponentTreeRefresher,
        // ConflictRegistry, license checks, onboarding). Extracted into
        // `runStartupAccentOnEdt` to keep `execute` within detekt's cyclomatic
        // complexity budget. See Phase 40 review Round 3 C-5 and review-loop
        // Round 1 HIGH-2/HIGH-3/MEDIUM-3.
        runStartupAccentOnEdt(project, variant)

        // Warm the language-detector cache so Settings → Accent → Overrides
        // shows real per-language proportions on first open. Gated on the same
        // `languageAccents.isNotEmpty()` predicate `AccentResolver.findOverride`
        // uses: users with no language pins never paid for the scan before this
        // feature landed, and still shouldn't pay for it just because the
        // Settings panel has a new informational row. When no pins exist the
        // panel's own `buildGroup` warmup (EDT-safe bail-out) handles the first
        // open with one extra cache miss. Both the `AccentMappingsSettings`
        // state read and the detector call run under the same runCatching so a
        // transient failure in either (corrupt persistent-state XML, plugin
        // unload race, disposed scanner, etc.) can't short-circuit the rest of
        // startup — ModuleRootListener subscription, font-preset apply, license
        // checks, and onboarding all live below this block.
        runCatchingPreservingCancellation {
            val pinnedLanguages = AccentMappingsSettings.getInstance().state.languageAccents
            if (pinnedLanguages.isNotEmpty()) {
                ProjectLanguageDetector.dominant(project)
            }
        }.onFailure { exception ->
            LOG.warn("Language detector warmup failed; will retry on next resolve", exception)
        }

        // Drop the language-detector cache when the project's module / content-root
        // structure changes (gradle sync adds a module, user edits sourceSets, etc.)
        // so the next language-override resolution re-scans instead of serving a stale
        // dominant language from a scan taken before the change. Bus connection is
        // tied to `project`, so the subscription auto-disposes on project close.
        project.messageBus
            .connect(project)
            .subscribe(
                ModuleRootListener.TOPIC,
                object : ModuleRootListener {
                    override fun rootsChanged(event: ModuleRootEvent) {
                        ProjectLanguageDetector.invalidate(project)
                    }
                },
            )

        // Focus-swap listener install() + cache sync via notifyExternalApply(hex)
        // are now inside the withContext(Dispatchers.EDT) block above so they share an
        // atomic EDT turn with the initial apply — see that block's KDoc for the
        // ordering rationale.

        // Eager-instantiate per-project scrollbar managers BEFORE firing the first refresh event.
        // Their init{} blocks subscribe to ComponentTreeRefreshedTopic; without this, the
        // walkAndNotify below publishes into an empty subscriber list and the managers — lazily
        // created later by StartupLicenseHandler.initWorkspaceServices on a subsequent EDT turn —
        // miss the initial refresh (plus any editors already opened before they subscribed to
        // EditorFactoryListener). Services are no-op when their toggles are off and are cheap to
        // instantiate, so gating on settings would only add complexity.
        EditorScrollbarManager.getInstance(project)
        ProjectViewScrollbarManager.getInstance(project)

        // Force a component-tree LAF refresh on the project frame so already-rendered toolbar,
        // tab underlines, scrollbar chrome, and focus rings pick up the resolved accent.
        // AccentApplicator only updates UIManager + editor scheme; cached JBColor instances on
        // already-painted components otherwise keep the global-accent values they captured when
        // the frame first rendered.
        SwingUtilities.invokeLater {
            val frame = WindowManager.getInstance().getFrame(project) ?: return@invokeLater
            ComponentTreeRefresher.walkAndNotify(project, frame)
        }

        // Apply persisted font preset (FontPresetApplicator ensures EDT internally)
        // Migrate legacy preset names (GLOW_WRITER→WHISPER, CLEAN→AMBIENT, etc.)
        val fontPreset = FontPreset.fromName(settings.state.fontPresetName)
        if (fontPreset.name != settings.state.fontPresetName) {
            settings.state.fontPresetName = fontPreset.name
        }
        FontPreset.migrateCustomizations(settings.state.fontPresetCustomizations)

        // Seed installedFonts from the JVM font registry on first run so returning
        // users who pre-installed via the Settings panel aren't re-prompted by the wizard.
        settings.seedInstalledFontsFromDiskIfNeeded()

        FontPresetApplicator.applyFromState()

        // Log detected third-party plugin conflicts
        val conflicts = ConflictRegistry.detectConflicts()
        if (conflicts.isNotEmpty()) {
            LOG.info("Ayu Islands detected third-party plugins: ${conflicts.joinToString { it.pluginDisplayName }}")
        }

        // Snapshot BEFORE UpdateNotifier can mutate it (prevents fresh-install false positive)
        val isReturningUser = settings.state.lastSeenVersion != null

        // Check license state and initialize workspace services (inside EDT callback)
        checkLicenseState(project, variant, settings, isReturningUser)

        // Auto-switch theme to match macOS Light/Dark mode
        if (settings.state.followSystemAppearance) {
            AppearanceSyncService.getInstance().syncIfNeeded()
        }

        // Start accent rotation if enabled (premium feature)
        try {
            if (settings.state.accentRotationEnabled && LicenseChecker.isLicensedOrGrace()) {
                val rotationService = AccentRotationService.getInstance()
                val lastSwitch = settings.state.accentRotationLastSwitchMs
                val intervalMs = settings.state.accentRotationIntervalHours * MS_PER_HOUR
                val elapsed = System.currentTimeMillis() - lastSwitch
                if (lastSwitch == 0L || elapsed >= intervalMs) {
                    rotationService.rotateNow()
                } else {
                    val remainingMs = intervalMs - elapsed
                    rotationService.startRotationWithDelay(remainingMs)
                }
            }
        } catch (exception: RuntimeException) {
            LOG.error("Accent rotation startup failed", exception)
        }

        // Show a one-time update notification if the plugin version changed
        SwingUtilities.invokeLater { UpdateNotifier.showIfUpdated(project) }

        // Initialize the glow overlay system if the glow is enabled
        // Uses ApplicationManager.invokeLater with project.disposed condition to skip
        // if the project closes before the EDT processes this (execute() runs on a background coroutine)
        if (settings.state.glowEnabled) {
            ApplicationManager.getApplication().invokeLater(
                { GlowOverlayManager.getInstance(project).initialize() },
                project.disposed,
            )
        }
    }

    /**
     * EDT triplet that must run atomically to avoid startup races: resolve the
     * focused project, apply the accent, then install the focus-swap listener
     * and publish the swap cache. Split from [execute] to keep cyclomatic
     * complexity under detekt's threshold and to let the tests exercise this
     * sequence in isolation (see Phase 40 review-loop Round 1 test-gap list).
     *
     * Round 1 refinements on top of the initial Round 3 C-5 fix:
     *  - Capture projectName BEFORE the EDT hop so a mid-hop `project.isDisposed`
     *    race cannot NPE inside the error logger and swallow the original
     *    exception (MEDIUM-3).
     *  - Split apply-vs-install into two [runCatchingPreservingCancellation]
     *    blocks so the ERROR message pins which half failed — apply fail is a
     *    visual regression the user sees; install fail silently disables
     *    focus-swap cycling for the session (HIGH-2).
     *  - Bail early if project/application already disposed to avoid hitting
     *    platform APIs that rewrap PCE as AlreadyDisposedException, which the
     *    coroutine cancellation helper can't unwrap (HIGH-3 mitigation at the
     *    call site).
     */
    private suspend fun runStartupAccentOnEdt(
        project: Project,
        variant: AyuVariant,
    ) {
        val projectName = runCatching { project.name }.getOrElse { "<disposed>" }
        withContext(Dispatchers.EDT) {
            if (project.isDisposed || ApplicationManager.getApplication().isDisposed) {
                return@withContext
            }
            val hex =
                runCatchingPreservingCancellation {
                    val focusedProject = AccentApplicator.resolveFocusedProject() ?: project
                    val resolved = AccentResolver.resolve(focusedProject, variant)
                    AccentApplicator.apply(resolved)
                    resolved
                }.onFailure { exception ->
                    LOG.error(
                        "Startup accent apply failed for project '$projectName'; " +
                            "chrome may look un-themed until the next user-triggered apply",
                        exception,
                    )
                }.getOrNull() ?: return@withContext
            runCatchingPreservingCancellation {
                val swapService = ProjectAccentSwapService.getInstance()
                swapService.install()
                swapService.notifyExternalApply(hex)
            }.onFailure { exception ->
                LOG.error(
                    "Startup accent-swap install/notify failed for project '$projectName' " +
                        "AFTER a successful apply; multi-project focus-swap cycling will " +
                        "be inert this session until the user re-triggers apply",
                    exception,
                )
            }
        }
    }

    private fun checkLicenseState(
        project: Project,
        variant: AyuVariant,
        settings: AyuIslandsSettings,
        isReturningUser: Boolean,
    ) {
        val isLicensed = LicenseChecker.isLicensedOrGrace()
        LOG.info("Ayu Islands license check: ${if (isLicensed) "licensed" else "not licensed"}")

        val trialDays = LicenseChecker.getTrialDaysRemaining()
        if (trialDays != null) {
            LOG.info("Ayu Islands trial: $trialDays days remaining")
        }

        // Compute adaptive delay on background thread (execute() coroutine)
        val adaptiveDelayMs = StartupLicenseHandler.computeAdaptiveDelay()

        // Each step runs under its own named try/catch so an exception in one stage doesn't
        // mask the others and the log line identifies which step actually broke. A single
        // catch used to produce a generic "License defaults failed" with no way to tell
        // whether migration, orchestrator routing, defaults, workspace init, wizard
        // scheduling, or trial warning blew up.
        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            var wizardAction: dev.ayuislands.onboarding.WizardAction? = null

            runStep("onboarding-migration") { StartupLicenseHandler.runOnboardingMigration(settings) }
            runStep("resolve-onboarding") {
                wizardAction = StartupLicenseHandler.resolveOnboarding(isLicensed, settings, isReturningUser)
            }
            if (isLicensed) {
                runStep("apply-licensed-defaults") { StartupLicenseHandler.applyLicensedDefaults(settings) }
            } else {
                runStep("apply-unlicensed-defaults") {
                    StartupLicenseHandler.applyUnlicensedDefaults(project, variant, settings)
                }
            }
            runStep("migrate-width-modes") { settings.state.migrateWidthModes() }
            runStep("init-workspace-services") { StartupLicenseHandler.initWorkspaceServices(project, settings) }
            runStep("handle-wizard-action") {
                wizardAction?.let {
                    StartupLicenseHandler.handleWizardAction(it, project, adaptiveDelayMs, settings)
                }
            }
            if (isLicensed) {
                runStep("check-trial-expiry") { LicenseChecker.checkTrialExpiryWarning(project) }
            }
        }
    }

    /**
     * Invokes [block] with fine-grained error handling so each startup step gets its own
     * log line, and the JVM's error-escalation path stays intact for genuinely fatal errors.
     *
     * All production call sites run inside `SwingUtilities.invokeLater { ... }` (see
     * [checkLicenseState]), so the surrounding Runnable is dispatched by `IdeEventQueue`:
     *
     *  - [RuntimeException] — logged, swallowed; the next step runs.
     *  - [VirtualMachineError] (OutOfMemoryError, StackOverflowError, InternalError,
     *    UnknownError) — logged and rethrown. These indicate the JVM is in an unrecoverable
     *    state; any further work is undefined behavior. Rethrowing surfaces the error back
     *    to `IdeEventQueue.dispatchException`, which logs at ERROR and may surface a
     *    fatal-error dialog — the one place a VM-level failure should still be visible.
     *  - Other [Error] (NoClassDefFoundError, LinkageError, ExceptionInInitializerError,
     *    AssertionError) — logged, swallowed; the next step runs. These usually indicate
     *    a class-loading or static-initializer problem in an individual step's transitive
     *    closure (e.g. a lazily-loaded service whose class body references a renamed
     *    platform API, surfacing as `NoClassDefFoundError` only once that service is first
     *    touched); the plugin's other startup steps should continue independently.
     */
    @Suppress("TooGenericExceptionCaught") // VM error rethrown; generic Error logged-and-continue
    private inline fun runStep(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (exception: RuntimeException) {
            LOG.error("License startup step '$name' failed", exception)
        } catch (error: VirtualMachineError) {
            LOG.error("License startup step '$name' failed with VM error", error)
            throw error
        } catch (error: Error) {
            LOG.error("License startup step '$name' failed with Error", error)
        }
    }

    /**
     * Test-only hook around [runStep]. Private functions are unreachable from test classes
     * in other packages; this thin wrapper exposes the catch semantics without widening
     * [runStep] itself.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun runStepForTest(
        name: String,
        block: () -> Unit,
    ) = runStep(name, block)

    companion object {
        private val LOG = logger<AyuIslandsStartupActivity>()
        private const val MS_PER_HOUR = 3_600_000L
    }
}
