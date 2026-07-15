package dev.ayuislands.licensing

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.LicensingFacade
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.reapply.ReapplyReason
import dev.ayuislands.reapply.ReapplyStep
import dev.ayuislands.reapply.ThemeReapplication
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.CommitPathDisplayMode
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import dev.ayuislands.vcs.VcsColorPreset
import org.jetbrains.annotations.VisibleForTesting
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object LicenseChecker {
    const val PRODUCT_CODE = "PAYUISLANDS"

    private val LOG = logger<LicenseChecker>()
    private const val NOTIFICATION_GROUP = "Ayu Islands"
    private val verifier = LicenseVerifier()

    /**
     * Test seam for the wall-clock reads inside [isLicensedOrGrace].
     *
     * Production code uses the real system clock; tests can pin it to a deterministic
     * value to exercise the rollback guard and grace-window boundaries without relying
     * on real-time jitter. Always restore the default supplier in a tearDown to avoid
     * leaking state across tests.
     */
    @VisibleForTesting
    @Volatile
    internal var nowMsSupplier: () -> Long = System::currentTimeMillis

    /**
     * Test seam for the UTC "today" read inside [getTrialDaysRemaining]. Same rules
     * as [nowMsSupplier] — overridable in tests, must be reset in teardown.
     */
    @VisibleForTesting
    @Volatile
    internal var todayUtcSupplier: () -> LocalDate = { LocalDate.now(ZoneId.of("UTC")) }

    /**
     * Check license state.
     *
     * @return true if licensed/trial active, false if not licensed,
     *         null if LicensingFacade not yet initialized.
     */
    fun isLicensed(): Boolean? {
        if (isDevBuild()) return true
        val facade = LicensingFacade.getInstance() ?: return null
        val stamp = facade.getConfirmationStamp(PRODUCT_CODE)
        if (stamp == null) {
            LOG.info("License stamp: null (no confirmation from Marketplace)")
            return false
        }
        val result =
            when {
                stamp.startsWith("key:") -> verifier.isKeyValid(stamp.substring(KEY_PREFIX_LENGTH))
                stamp.startsWith("stamp:") -> verifier.isStampValid(stamp.substring(STAMP_PREFIX_LENGTH))
                stamp.startsWith("eval:") -> true
                else -> false
            }
        if (!result) {
            LOG.info("License stamp validation failed: ${stamp.take(STAMP_LOG_PREFIX_LENGTH)}...")
        }
        return result
    }

    /**
     * Treat null (not initialized) as licensed per grace-period policy.
     *
     * Also provides a 48-hour offline grace window: if the license was confirmed
     * within the last 48 hours but the current check returns false (e.g. offline,
     * server unreachable), the user keeps pro features until the grace window
     * expires. This prevents a single offline restart from locking out a paid user.
     *
     * **Anti-cheat guards** (see `LicenseCheckerAntiCheatTest`):
     *  - Monotonic clamp on write (`maxOf`): a future-fabricated `lastKnownLicensedMs`
     *    from hand-edited XML will only ever be replaced by a real `now` on the next
     *    legitimate licensed check, never moved further forward.
     *  - Rollback detection on read: if `lastKnownLicensedMs > now`, we assume the
     *    system clock was rolled back or the stamp was tampered to the future, so
     *    we revoke grace and clear the stamp. Combined with monotonic clamp, this
     *    means an attacker cannot stretch the 48 h grace window beyond real time.
     *  - XML signing is out of scope (key would live in the jar, trivially
     *    extractable for a solo plugin). Tampering remains possible but self-corrects
     *    the moment Marketplace confirms a stamp.
     */
    fun isLicensedOrGrace(): Boolean {
        val licensed = isLicensed()
        val state = AyuIslandsSettings.getInstance().state
        val now = nowMsSupplier()
        if (licensed == true) {
            state.lastKnownLicensedMs = maxOf(state.lastKnownLicensedMs, now)
            return true
        }
        if (licensed == null) return true
        if (state.lastKnownLicensedMs > now) {
            LOG.warn(
                "Clock rollback or lastKnownLicensedMs tamper detected " +
                    "(stamp=${state.lastKnownLicensedMs}, now=$now); " +
                    "revoking grace and resetting stamp",
            )
            state.lastKnownLicensedMs = 0L
            return false
        }
        val elapsed = now - state.lastKnownLicensedMs
        if (state.lastKnownLicensedMs > 0 && elapsed in 0 until OFFLINE_GRACE_MS) {
            LOG.info(
                "License check returned false but within ${elapsed / MS_PER_HOUR}h " +
                    "offline grace (${OFFLINE_GRACE_HOURS}h window) — treating as licensed",
            )
            return true
        }
        return false
    }

    /** Open the JetBrains registration / purchase dialog. */
    fun requestLicense(message: String) {
        ApplicationManager.getApplication().invokeLater({
            LOG.info("Opening Ayu Islands Marketplace license page: $message")
            BrowserUtil.browse(MARKETPLACE_URL)
        }, ModalityState.nonModal())
    }

    /** Show one-time conversion-oriented notification after trial expiry. */
    fun notifyTrialExpired(project: Project?) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Ayu Islands trial ended",
                "Glow, accent toggles, auto-fit, and plugin sync " +
                    "reverted to defaults. " +
                    "A license brings them back \u2014 one-time, forever.",
                NotificationType.INFORMATION,
            ).addAction(
                object : NotificationAction("Get license") {
                    override fun actionPerformed(
                        e: AnActionEvent,
                        notification: Notification,
                    ) {
                        notification.expire()
                        requestLicense(NOTIFICATION_GROUP)
                    }
                },
            ).notify(project)
    }

    /**
     * Enable all Pro features on genuine first-time license activation.
     *
     * Guarded by `everBeenPro` which survives trial expiry, so a re-purchase does
     * NOT overwrite user customizations. `proDefaultsApplied` is still set to
     * prevent redundant calls within the same license period.
     */
    fun enableProDefaults() {
        val state = AyuIslandsSettings.getInstance().state
        if (state.everBeenPro) {
            state.proDefaultsApplied = true
            return
        }
        state.glowEnabled = true
        state.glowStyle = GlowStyle.SHARP_NEON.name
        state.glowPreset = GlowPreset.CUSTOM.name
        state.sharpNeonIntensity = PRO_DEFAULT_NEON_INTENSITY
        state.sharpNeonWidth = PRO_DEFAULT_NEON_WIDTH
        state.glowAnimation = GlowAnimation.BREATHE.name
        state.glowEditor = true
        state.glowProject = true
        state.glowTerminal = true
        state.glowRun = true
        state.glowDebug = true
        state.glowGit = true
        state.glowServices = true
        state.glowFocusRing = true
        state.everBeenPro = true
        state.proDefaultsApplied = true
    }

    /**
     * Apply workspace defaults (auto-fit, hide path/VCS).
     * Callers must guard with the workspaceDefaultsApplied flag.
     */
    fun applyWorkspaceDefaults() {
        val state = AyuIslandsSettings.getInstance().state
        state.projectPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.commitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.hideProjectRootPath = true
        state.hideProjectViewHScrollbar = true
        state.workspaceDefaultsApplied = true
    }

    /**
     * Revert paid features to free defaults for the given variant.
     *
     * State mutations are wrapped in `synchronized(state)` to tolerate rare
     * concurrent callers (unlicensed path from `StartupLicenseHandler`, license
     * transition listener, and any future direct callers). `BaseState` itself
     * is not thread-safe for parallel writes, and the writes here are idempotent
     * resets — a lost update would produce the same terminal state, but the lock
     * prevents a half-committed toggle map from leaking. The downstream
     * [ThemeReapplication.reapply] call (accent, glow, VCS colors) intentionally
     * stays *outside* the lock: it hops to EDT and can take other platform
     * locks, so holding `state` across it would risk deadlock.
     */
    fun revertToFreeDefaults(variant: AyuVariant) {
        val state = AyuIslandsSettings.getInstance().state

        synchronized(state) {
            // Disable glow (premium feature)
            state.glowEnabled = false

            // Reset VCS color customization — premium feature. The master toggle
            // goes off so the EditorColorsScheme falls back to stock XML on the
            // next applier pass; every per-section preset returns to AMBIENT,
            // every per-category slider returns to AMBIENT_SLIDER, and every
            // section-expanded flag returns to its default-constructor value.
            //
            // Reset covers all 11 categories — including the placeholder
            // intensities (merge 3-way, inline diff, local history, branch
            // indicator, branches popup, commit highlights) — so when those
            // categories ship, a user who downgraded mid-cycle starts from the
            // same baseline as a fresh install. Without that, the re-purchase
            // experience would surface non-default sliders from a stale premium
            // session.
            state.vcsColorEnabled = false
            state.vcsDiffPreset = VcsColorPreset.AMBIENT.name
            state.vcsMergePreset = VcsColorPreset.AMBIENT.name
            state.vcsBlamePreset = VcsColorPreset.AMBIENT.name
            state.vcsDiffIntensity = VcsColorPreset.AMBIENT_SLIDER
            state.vcsProjectViewIntensity = VcsColorPreset.AMBIENT_SLIDER
            state.vcsGutterIntensity = VcsColorPreset.AMBIENT_SLIDER
            state.vcsConflictMarkerIntensity = VcsColorPreset.AMBIENT_SLIDER
            state.vcsBlameIntensity = VcsColorPreset.AMBIENT_SLIDER
            state.vcsMerge3WayIntensity = VcsColorPreset.AMBIENT_SLIDER
            state.vcsInlineDiffIntensity = VcsColorPreset.AMBIENT_SLIDER
            state.vcsLocalHistoryIntensity = VcsColorPreset.AMBIENT_SLIDER
            state.vcsBranchIndicatorIntensity = VcsColorPreset.AMBIENT_SLIDER
            state.vcsBranchesPopupIntensity = VcsColorPreset.AMBIENT_SLIDER
            state.vcsCommitHighlightIntensity = VcsColorPreset.AMBIENT_SLIDER
            // Diff section opens expanded by default; the other two collapse on
            // downgrade to match a fresh-install layout.
            state.vcsDiffSectionExpanded = true
            state.vcsMergeSectionExpanded = false
            state.vcsBlameSectionExpanded = false

            // Reset workspace settings to free defaults
            state.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
            state.commitPanelWidthMode = PanelWidthMode.DEFAULT.name
            state.gitPanelWidthMode = PanelWidthMode.DEFAULT.name
            state.commitPanelPathDisplayMode = CommitPathDisplayMode.INLINE.name
            state.commitPanelPathMinHiddenLevels = AyuIslandsState.DEFAULT_COMMIT_PATH_MIN_HIDDEN_LEVELS
            state.commitPanelPathMaxHiddenLevels = AyuIslandsState.DEFAULT_COMMIT_PATH_MAX_HIDDEN_LEVELS
            state.hideProjectRootPath = false
            state.hideProjectViewHScrollbar = false

            // Disable plugin integrations (premium features)
            state.cgpIntegrationEnabled = false
            state.irIntegrationEnabled = false

            // Restore the Quick Switcher widget to its free-tier default (visible
            // by default). A user who hid the chip while licensed and then
            // downgraded would otherwise lose their only Settings affordance to
            // re-enable it; Pattern G symmetric reset puts the chip back per default.
            state.quickSwitcherWidgetEnabled = true

            // Stop accent rotation (premium feature)
            state.accentRotationEnabled = false
        }

        resetSyntaxIntensityToFreeDefaults()

        ApplicationManager.getApplication().getService(AccentRotationService::class.java)?.stopRotation()

        // Re-apply accent (free-tier feature stays), sync glow, and revert VCS colors
        // through the shared reapplication seam so this caller matches the LAF-listener
        // and rotation-tick paths. `LicenseRevert`'s plan is [ApplyExplicitHex, Glow,
        // VcsRevert] (see `ThemeReapplication.planFor`) — same order as before this migration.
        val freeHex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        ThemeReapplication.reapply(ReapplyReason.LicenseRevert(freeHex)) { result ->
            for (failure in result.failures) {
                LOG.warn("License revert step=${failure.step} failed", failure.error)
            }
            if (result.failed(ReapplyStep.ApplyExplicitHex)) {
                notifyRevertIncomplete(
                    "Accent revert incomplete",
                    "Some accent colors could not be reverted. " +
                        "Restart your IDE to complete the reset.",
                )
            }
            if (result.failed(ReapplyStep.Glow)) {
                notifyRevertIncomplete(
                    "Glow sync incomplete",
                    "Glow overlays could not be updated after license change. " +
                        "Restart your IDE to complete the reset.",
                )
            }
            // VcsRevert failures stay LOG.warn-only (above), matching pre-migration behavior.
        }
    }

    private fun notifyRevertIncomplete(
        title: String,
        body: String,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, body, NotificationType.WARNING)
            .notify(null)
    }

    private fun resetSyntaxIntensityToFreeDefaults() {
        try {
            val syntaxState = SyntaxIntensityState.getInstance().state
            syntaxState.selectedPreset = SyntaxPreset.AMBIENT.name
            syntaxState.subordinatePreset = SyntaxPreset.AMBIENT.name
            syntaxState.customOverrides.clear()
            syntaxState.customStyles.clear()
            syntaxState.dimComments = false
            syntaxState.softenDocumentation = false
            syntaxState.quietOperators = false
            syntaxState.emphasizeDeclarations = false
            SyntaxIntensityService
                .getInstance()
                .apply(
                    SyntaxPreset.AMBIENT,
                    emptyMap(),
                    SyntaxPreset.AMBIENT,
                    emptyMap(),
                    SyntaxReadabilityOptions.DEFAULT,
                )
        } catch (exception: RuntimeException) {
            LOG.warn("Syntax intensity revert after license downgrade failed", exception)
        }
    }

    /**
     * Calculate remaining trial days from [LicensingFacade] expiration date.
     *
     * JetBrains Marketplace stores the expiration as UTC midnight. Converting to
     * [ZoneId.systemDefault] before extracting the date shifts it 1 day backward
     * for timezones west of UTC, causing premature trial lockout. We extract the
     * date in UTC instead so the day boundary matches the Marketplace's intent.
     *
     * @return days remaining (>= 0), or null if not on trial / facade unavailable / already expired.
     */
    fun getTrialDaysRemaining(): Long? {
        val facade = LicensingFacade.getInstance() ?: return null
        if (!facade.isEvaluationLicense) return null
        val expirationDate = facade.getExpirationDate(PRODUCT_CODE) ?: return null
        val expirationDay = expirationDate.toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
        val today = todayUtcSupplier()
        val days = ChronoUnit.DAYS.between(today, expirationDay)
        return if (days >= 0) days else null
    }

    /** Show a two-stage trial expiry warning (7-day and 3-day thresholds). */
    fun checkTrialExpiryWarning(project: Project?) {
        val daysRemaining = getTrialDaysRemaining() ?: return
        val state = AyuIslandsSettings.getInstance().state

        val shouldWarn7Day = daysRemaining <= TRIAL_WARNING_7_DAY_THRESHOLD && !state.trialExpiryWarningShown
        val shouldWarn3Day = daysRemaining <= TRIAL_WARNING_3_DAY_THRESHOLD && !state.trialExpiry3DayWarningShown

        if (!shouldWarn7Day && !shouldWarn3Day) return

        if (shouldWarn3Day) state.trialExpiry3DayWarningShown = true
        if (shouldWarn7Day) state.trialExpiryWarningShown = true

        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Ayu Islands trial: $daysRemaining days remaining",
                "Glow, accent toggles, auto-fit, and plugin sync " +
                    "will revert to defaults when your trial ends. " +
                    "A license keeps them \u2014 one-time, forever.",
                NotificationType.INFORMATION,
            ).addAction(
                object : NotificationAction("Get license") {
                    override fun actionPerformed(
                        e: AnActionEvent,
                        notification: Notification,
                    ) {
                        notification.expire()
                        requestLicense(NOTIFICATION_GROUP)
                    }
                },
            ).notify(project)
    }

    private const val KEY_PREFIX_LENGTH = 4
    private const val STAMP_PREFIX_LENGTH = 6
    private const val STAMP_LOG_PREFIX_LENGTH = 10
    private const val PRO_DEFAULT_NEON_INTENSITY = 100
    private const val PRO_DEFAULT_NEON_WIDTH = 2
    private const val TRIAL_WARNING_7_DAY_THRESHOLD = 7L
    private const val TRIAL_WARNING_3_DAY_THRESHOLD = 3L
    private const val MS_PER_HOUR = 3_600_000L
    private const val OFFLINE_GRACE_HOURS = 48L
    private const val OFFLINE_GRACE_MS = OFFLINE_GRACE_HOURS * MS_PER_HOUR
    private const val MARKETPLACE_URL = "https://plugins.jetbrains.com/plugin/30373-ayu-islands"

    /**
     * Dev mode: requires all three gates to match. Each gate alone is bypassable
     * with an end-user sysprop; all three together demand a Gradle-produced sandbox
     * install, which requires compiling from source — at which point the user is
     * already a developer and "bypassing" is moot.
     *
     *  1. `-Dayu.islands.dev=true` — explicit opt-in.
     *  2. `PathManager.getConfigPath()` under `idea-sandbox` — the config dir that
     *     `runIde` creates. An attacker can forge this with `-Didea.config.path`,
     *     so it is insufficient on its own.
     *  3. Plugin install path under `idea-sandbox` — `runIde` installs the plugin
     *     under `build/idea-sandbox/plugins/`. Production installs sit under the
     *     user's JetBrains plugins dir or are bundled in the IDE. The IDE discovers
     *     plugin paths from its filesystem scan, not from a JVM sysprop, so this
     *     gate is not trivially forgeable.
     */
    private fun isDevBuild(): Boolean {
        if (System.getProperty("ayu.islands.dev") != "true") return false
        val configPath = PathManager.getConfigPath()
        if (!configPath.contains("idea-sandbox")) return false
        // Reaching this branch means the operator explicitly requested dev
        // mode AND IDE is in a sandbox config — so a `false` return from here
        // is a SURPRISING demotion and must be auditable. INFO (not DEBUG)
        // so the message lands in `idea.log` without enabling category-level
        // debug; the two preconditions above guarantee this branch can't spam
        // regular users (only dev sandboxes ever reach it). Both descriptor-
        // null and pluginPath-null paths are logged for symmetry.
        val descriptor = AyuPlugin.findLoadedPlugin(AyuPlugin.ID)
        val pluginPath = descriptor?.pluginPath?.toString().orEmpty()
        val isDev = pluginPath.contains("idea-sandbox")
        if (!isDev) {
            LOG.info(
                "isDevBuild: returning false despite dev sandbox request — " +
                    "descriptor=${descriptor != null}, pluginPath=$pluginPath",
            )
        }
        return isDev
    }
}
