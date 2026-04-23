package dev.ayuislands.licensing

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.ui.LicensingFacade
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
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
            val actionManager = ActionManager.getInstance()
            val action =
                actionManager.getAction("RegisterPlugins")
                    ?: actionManager.getAction("Register")
                    ?: return@invokeLater

            val dataContext =
                DataContext { dataId ->
                    when (dataId) {
                        "register.product-descriptor.code" -> PRODUCT_CODE
                        "register.message" -> message
                        else -> null
                    }
                }
            val event =
                AnActionEvent.createEvent(
                    dataContext,
                    Presentation(),
                    "",
                    ActionUiKind.NONE,
                    null,
                )
            ActionUtil.invokeAction(action, event, null)
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
     * prevents a half-committed toggle map from leaking. Downstream calls
     * (`AccentApplicator.apply`, `GlowOverlayManager.syncGlowForAllProjects`)
     * intentionally stay *outside* the lock: both hop to EDT and can take other
     * platform locks, so holding `state` across them would risk deadlock.
     */
    fun revertToFreeDefaults(variant: AyuVariant) {
        val state = AyuIslandsSettings.getInstance().state

        synchronized(state) {
            // Disable glow (premium feature)
            state.glowEnabled = false

            // Reset tab accent to free default (underline only, no tinted background)
            state.glowTabMode = "MINIMAL"

            // Reset per-element toggles to defaults. VISUAL/INTERACTIVE groups are
            // free-tier features that default to ON; the CHROME group is a premium-only
            // chrome-tinting surface (phase 40) that must be forcibly disabled on the
            // free tier so unlicensed users never see tinted chrome after a downgrade.
            for (id in AccentElementId.entries) {
                val defaultForFree = id.group != AccentGroup.CHROME
                state.setToggle(id, defaultForFree)
            }

            // Reset chrome-tinting auxiliary state to defaults (intensity baseline,
            // group collapsed). The WCAG foreground contrast is now always-on so no
            // toggle needs resetting.
            state.chromeTintIntensity = AyuIslandsState.DEFAULT_CHROME_TINT_INTENSITY
            state.chromeTintingGroupExpanded = false

            // Reset workspace settings to free defaults
            state.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
            state.commitPanelWidthMode = PanelWidthMode.DEFAULT.name
            state.gitPanelWidthMode = PanelWidthMode.DEFAULT.name
            state.hideProjectRootPath = false
            state.hideProjectViewHScrollbar = false

            // Disable plugin integrations (premium features)
            state.cgpIntegrationEnabled = false
            state.irIntegrationEnabled = false

            // Stop accent rotation (premium feature)
            state.accentRotationEnabled = false
        }

        ApplicationManager.getApplication().getService(AccentRotationService::class.java)?.stopRotation()

        // Re-apply accent with reset toggles (accent color itself stays — it's free)
        try {
            val accentHex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
            AccentApplicator.apply(accentHex)
        } catch (exception: RuntimeException) {
            LOG.warn("Revert to free defaults failed", exception)
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    "Accent revert incomplete",
                    "Some accent colors could not be reverted. " +
                        "Restart your IDE to complete the reset.",
                    NotificationType.WARNING,
                ).notify(null)
        }

        try {
            GlowOverlayManager.syncGlowForAllProjects()
        } catch (exception: RuntimeException) {
            LOG.warn("Glow sync after license revert failed", exception)
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    "Glow sync incomplete",
                    "Glow overlays could not be updated after license change. " +
                        "Restart your IDE to complete the reset.",
                    NotificationType.WARNING,
                ).notify(null)
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
        val pluginId = PluginId.getId("com.ayuislands.theme")
        val pluginPath =
            PluginManagerCore
                .getPlugin(pluginId)
                ?.pluginPath
                ?.toString()
                .orEmpty()
        return pluginPath.contains("idea-sandbox")
    }
}
