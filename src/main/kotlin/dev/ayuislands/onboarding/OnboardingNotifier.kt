@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.onboarding

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.settings.AyuIslandsSettings

/** Shows an actionable welcome notification with one-click preset buttons. */
object OnboardingNotifier {
    private val LOG = logger<OnboardingNotifier>()
    private const val NOTIFICATION_GROUP = "Ayu Islands"

    /** Display the trial welcome notification with Whisper, Neon, and Open Settings actions. */
    fun showWelcome(project: Project) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Welcome to Ayu Islands Premium",
                "Your 30-day trial is active. Try a preset \u2014 it changes glow and font in one click.",
                NotificationType.INFORMATION,
            ).addAction(
                object : NotificationAction("Whisper") {
                    override fun actionPerformed(
                        event: AnActionEvent,
                        notification: Notification,
                    ) {
                        notification.expire()
                        ApplicationManager.getApplication().invokeLater {
                            applyPreset(project, GlowPreset.WHISPER, FontPreset.WHISPER)
                        }
                    }
                },
            ).addAction(
                object : NotificationAction("Neon") {
                    override fun actionPerformed(
                        event: AnActionEvent,
                        notification: Notification,
                    ) {
                        notification.expire()
                        ApplicationManager.getApplication().invokeLater {
                            applyPreset(project, GlowPreset.NEON, FontPreset.NEON)
                        }
                    }
                },
            ).addAction(
                object : NotificationAction("Open Settings") {
                    override fun actionPerformed(
                        event: AnActionEvent,
                        notification: Notification,
                    ) {
                        notification.expire()
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ayu Islands")
                    }
                },
            ).notify(project)
    }

    private fun applyPreset(
        project: Project,
        glowPreset: GlowPreset,
        fontPreset: FontPreset,
    ) {
        val style = glowPreset.style ?: return
        val intensity = glowPreset.intensity ?: return
        val width = glowPreset.width ?: return
        val animation = glowPreset.animation ?: return
        val state = AyuIslandsSettings.getInstance().state

        state.glowEnabled = true
        state.glowStyle = style.name
        state.glowPreset = glowPreset.name
        state.setIntensityForStyle(style, intensity)
        state.setWidthForStyle(style, width)
        state.glowAnimation = animation.name

        state.fontPresetEnabled = true
        state.fontPresetName = fontPreset.name

        FontPresetApplicator.apply(
            FontSettings.decode(null, fontPreset).copy(applyToConsole = state.fontApplyToConsole),
        )

        GlowOverlayManager.getInstance(project).initialize()
        GlowOverlayManager.syncGlowForAllProjects()

        LOG.info("Onboarding preset applied: ${glowPreset.name}")
    }
}
