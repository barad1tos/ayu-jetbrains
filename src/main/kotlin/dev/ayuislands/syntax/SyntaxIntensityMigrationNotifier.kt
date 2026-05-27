package dev.ayuislands.syntax

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * One-shot migration notification announcing the syntax-intensity preset row
 * (Whisper / Ambient / Neon / Cyberpunk + Custom pill) to users upgrading
 * from the prior syntax-mood release.
 *
 * Flag key `ayu.syntax.intensity.notified` is DISTINCT from the legacy
 * `ayu.syntax.notified` (D-14): users who saw the older mood-row notification
 * still get this migration message exactly once on first launch after the
 * intensity preset rewrite ships. `PropertiesComponent` is the persistence
 * surface — a single boolean, install-scoped, no separate @Service @State
 * XML file for one flag.
 *
 * `GROUP_ID` reuses the existing `<notificationGroup id="Ayu Islands"
 * displayType="BALLOON"/>` registration in `plugin.xml` — a Pattern L
 * source-regex test locks the binding so a registration rename surfaces
 * before runtime.
 *
 * Pattern B catch: the bus-publish path catches `RuntimeException` only
 * (broader catches are forbidden); failures are logged WARN and swallowed
 * so a notify fault cannot abort the startup-activity chain.
 */
object SyntaxIntensityMigrationNotifier {
    private const val FLAG_KEY = "ayu.syntax.intensity.notified"
    private const val GROUP_ID = "Ayu Islands"
    private const val TITLE = "Ayu Islands -- Syntax customization updated"
    private const val BODY =
        "New presets available: Whisper, Ambient, Neon, Cyberpunk. " +
            "Open Settings -> Ayu Islands -> Syntax to pick one."
    private const val ACTION_LABEL = "Open Syntax tab"
    private const val CONFIGURABLE_DISPLAY_NAME = "Ayu Islands"
    private val log = Logger.getInstance(SyntaxIntensityMigrationNotifier::class.java)

    fun maybeFire(project: Project? = null) {
        val props = PropertiesComponent.getInstance()
        if (props.getBoolean(FLAG_KEY, false)) return
        try {
            val notification = Notification(GROUP_ID, TITLE, BODY, NotificationType.INFORMATION)
            notification.addAction(
                NotificationAction.createSimple(ACTION_LABEL) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, CONFIGURABLE_DISPLAY_NAME)
                },
            )
            Notifications.Bus.notify(notification, project)
            props.setValue(FLAG_KEY, true)
        } catch (exception: RuntimeException) {
            log.warn("SyntaxIntensityMigrationNotifier.maybeFire failed to publish notification", exception)
        }
    }
}
