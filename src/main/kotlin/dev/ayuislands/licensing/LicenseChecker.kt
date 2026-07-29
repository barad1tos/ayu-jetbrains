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
import dev.ayuislands.settings.PanelWidthMode
import org.jetbrains.annotations.VisibleForTesting
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal enum class LicenseEntitlement {
    LICENSED,
    UNLICENSED,
    UNKNOWN,
}

internal data class EntitlementResult(
    val entitlement: LicenseEntitlement,
    val lastLicensedMs: Long,
    val isStampReset: Boolean = false,
)

/**
 * Resolves the effective runtime entitlement without reading or mutating application state.
 *
 * | Marketplace result | Stored stamp | Runtime entitlement | Stored stamp result |
 * |---|---|---|---|
 * | `true` | any | `LICENSED` | monotonic maximum with `nowMs` |
 * | `null` | any | `UNKNOWN` | unchanged |
 * | `false` | future | `UNLICENSED` | reset to zero |
 * | `false` | within 48-hour grace | `LICENSED` | unchanged |
 * | `false` | expired or absent | `UNLICENSED` | unchanged |
 */
internal fun resolveEntitlement(
    rawLicense: Boolean?,
    lastLicensedMs: Long,
    nowMs: Long,
): EntitlementResult =
    when {
        rawLicense == true ->
            EntitlementResult(
                entitlement = LicenseEntitlement.LICENSED,
                lastLicensedMs = maxOf(lastLicensedMs, nowMs),
            )
        rawLicense == null ->
            EntitlementResult(
                entitlement = LicenseEntitlement.UNKNOWN,
                lastLicensedMs = lastLicensedMs,
            )
        lastLicensedMs > nowMs ->
            EntitlementResult(
                entitlement = LicenseEntitlement.UNLICENSED,
                lastLicensedMs = 0,
                isStampReset = true,
            )
        lastLicensedMs > 0 && nowMs - lastLicensedMs in 0 until OFFLINE_GRACE_MS ->
            EntitlementResult(
                entitlement = LicenseEntitlement.LICENSED,
                lastLicensedMs = lastLicensedMs,
            )
        else ->
            EntitlementResult(
                entitlement = LicenseEntitlement.UNLICENSED,
                lastLicensedMs = lastLicensedMs,
            )
    }

object LicenseChecker {
    const val PRODUCT_CODE = "PAYUISLANDS"

    private val LOG = logger<LicenseChecker>()
    private const val NOTIFICATION_GROUP = "Ayu Islands"
    private val verifier = LicenseVerifier()

    /**
     * Test seam for the wall-clock reads inside [currentEntitlement].
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
     * Returns the effective runtime entitlement while keeping an uninitialized
     * Marketplace facade distinct from a confirmed licensed state.
     *
     * A confirmed license advances the offline-grace timestamp monotonically.
     * A future timestamp on an unlicensed check revokes grace and resets the stamp.
     * [LicenseEntitlement.UNKNOWN] never changes the stamp and must not trigger a
     * lifecycle transition.
     */
    internal fun currentEntitlement(): LicenseEntitlement {
        val rawLicense = isLicensed()
        val state = AyuIslandsSettings.getInstance().state
        val now = nowMsSupplier()
        val previousStamp = state.lastKnownLicensedMs
        val result = resolveEntitlement(rawLicense, previousStamp, now)

        if (result.lastLicensedMs != previousStamp) {
            state.lastKnownLicensedMs = result.lastLicensedMs
        }
        if (result.isStampReset) {
            LOG.warn(
                "Clock rollback or lastKnownLicensedMs tamper detected " +
                    "(stamp=$previousStamp, now=$now); " +
                    "revoking grace and resetting stamp",
            )
        }
        if (rawLicense == false && result.entitlement == LicenseEntitlement.LICENSED) {
            val elapsed = now - result.lastLicensedMs
            LOG.info(
                "License check returned false but within ${elapsed / MS_PER_HOUR}h " +
                    "offline grace (${OFFLINE_GRACE_HOURS}h window) — treating as licensed",
            )
        }
        return result.entitlement
    }

    /** Treat unknown Marketplace startup state as runtime fail-open without confirming a transition. */
    fun isLicensedOrGrace(): Boolean = currentEntitlement() != LicenseEntitlement.UNLICENSED

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

    /** Apply the free runtime presentation without changing saved premium preferences. */
    fun revertToFreeDefaults(variant: AyuVariant) {
        ApplicationManager.getApplication().getService(AccentRotationService::class.java)?.stopRotation()

        // Re-apply accent (free-tier feature stays), sync glow, and revert VCS colors
        // through the shared reapplication seam so this caller matches the LAF-listener
        // and rotation-tick paths.
        val freeHex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        ThemeReapplication.reapply(ReapplyReason.LicenseRevert(freeHex)) { result ->
            for ((step, error) in result.failures) {
                LOG.warn("License revert step=$step failed", error)
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

private const val MS_PER_HOUR = 3_600_000L
private const val OFFLINE_GRACE_HOURS = 48L
private const val OFFLINE_GRACE_MS = OFFLINE_GRACE_HOURS * MS_PER_HOUR
