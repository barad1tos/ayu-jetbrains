package dev.ayuislands.licensing

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
import com.intellij.openapi.project.Project
import com.intellij.ui.LicensingFacade
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode

object LicenseChecker {
    const val PRODUCT_CODE = "PAYUISLANDS"

    private val LOG = logger<LicenseChecker>()
    private const val NOTIFICATION_GROUP = "Ayu Islands"
    private val verifier = LicenseVerifier()

    /**
     * Check license state.
     *
     * @return true if licensed/trial active, false if not licensed,
     *         null if LicensingFacade not yet initialized.
     */
    fun isLicensed(): Boolean? {
        if (isDevBuild()) return true
        val facade = LicensingFacade.getInstance() ?: return null
        val stamp = facade.getConfirmationStamp(PRODUCT_CODE) ?: return false
        return when {
            stamp.startsWith("key:") -> verifier.isKeyValid(stamp.substring(KEY_PREFIX_LENGTH))
            stamp.startsWith("stamp:") -> verifier.isStampValid(stamp.substring(STAMP_PREFIX_LENGTH))
            stamp.startsWith("eval:") -> true
            else -> false
        }
    }

    /** Treat null (not initialized) as licensed per grace-period policy. */
    fun isLicensedOrGrace(): Boolean = isLicensed() != false

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
                object : com.intellij.notification.NotificationAction("Get license") {
                    override fun actionPerformed(
                        e: AnActionEvent,
                        notification: com.intellij.notification.Notification,
                    ) {
                        notification.expire()
                        requestLicense(NOTIFICATION_GROUP)
                    }
                },
            ).notify(project)
    }

    /** Show one-time welcome notification for new trial/license activations. */
    fun notifyTrialWelcome(project: Project?) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Ayu Islands Premium is unlocked",
                "30 days of glow, auto-fit, accent control, and plugin sync. " +
                    "Open Settings \u2192 Ayu Islands to explore.",
                NotificationType.INFORMATION,
            ).notify(project)
    }

    /** Enable all Pro features on the first license activation (one-time). */
    fun enableProDefaults() {
        val state = AyuIslandsSettings.getInstance().state
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
        applyWorkspaceDefaults()
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

    /** Revert paid features to free defaults for the given variant. */
    fun revertToFreeDefaults(variant: AyuVariant) {
        val state = AyuIslandsSettings.getInstance().state

        // Disable glow (premium feature)
        state.glowEnabled = false

        // Reset tab accent to free default (underline only, no tinted background)
        state.glowTabMode = "MINIMAL"

        // Reset per-element toggles to defaults (all ON)
        for (id in AccentElementId.entries) {
            state.setToggle(id, true)
        }

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
        }
    }

    private const val KEY_PREFIX_LENGTH = 4
    private const val STAMP_PREFIX_LENGTH = 6
    private const val PRO_DEFAULT_NEON_INTENSITY = 100
    private const val PRO_DEFAULT_NEON_WIDTH = 2

    /** Dev mode: requires BOTH system property AND Gradle sandbox environment. */
    private fun isDevBuild(): Boolean {
        if (System.getProperty("ayu.islands.dev") != "true") return false
        val configPath = PathManager.getConfigPath()
        return configPath.contains("idea-sandbox")
    }
}
