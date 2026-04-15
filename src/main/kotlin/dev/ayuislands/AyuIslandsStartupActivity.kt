package dev.ayuislands

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import dev.ayuislands.ui.ComponentTreeRefresher
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
        val accentHex = AccentResolver.resolve(project, variant)
        AccentApplicator.apply(accentHex)
        // Install the focus-swap listener once per IDE lifetime; subsequent calls are no-ops.
        // Also sync the cache so the listener doesn't skip the first real WINDOW_ACTIVATED event.
        val swapService = ProjectAccentSwapService.getInstance()
        swapService.install()
        swapService.notifyExternalApply(accentHex)

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
     * log line, and the JVM's error-escalation path stays intact for genuinely fatal errors:
     *
     *  - [RuntimeException] — logged, swallowed; the next step runs.
     *  - [VirtualMachineError] (OutOfMemoryError, StackOverflowError, InternalError,
     *    UnknownError) — logged and rethrown. These mean the JVM is in an unrecoverable
     *    state; continuing risks cascading corruption, and IntelliJ's crash reporter keys
     *    off uncaught VM errors.
     *  - Other [Error] (LinkageError, NoClassDefFoundError, ExceptionInInitializerError,
     *    AssertionError) — logged, swallowed; the next step runs. These usually mean an
     *    optional plugin dependency didn't load or a plugin extension point initializer
     *    threw — the plugin's own startup should continue, not abort.
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
     * Test-only hook around [runStep]. Inline private functions can't be called from a
     * test classpath; this thin wrapper exposes the catch semantics without leaking the
     * helper itself.
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
