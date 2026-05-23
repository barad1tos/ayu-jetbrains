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
 * Phase 49 one-shot upgrade notification (SYNTAX-07, D-10). Fires once per
 * IDE install on first launch after the Syntax Moods feature ships. Uses
 * [PropertiesComponent] for the "shown" flag — single boolean, install-scoped
 * (RESEARCH Q5: avoids creating a sixth @Service @State XML file just for one
 * flag).
 *
 * GROUP_ID is bound to the plugin.xml `<notificationGroup id="Ayu Islands"/>`
 * registration; the binding is regression-locked by a Pattern L source-regex
 * test in `SyntaxModeUpgradeNotifierTest` (warning #6 fix).
 *
 * Pattern B compliance: the catch handler matches RuntimeException only —
 * never Throwable. Bus failures are logged WARN and swallowed so a notify
 * fault cannot break the AppListener's appFrameCreated startup path.
 */
object SyntaxModeUpgradeNotifier {
    private const val FLAG_KEY = "ayu.syntax.notified"
    private const val GROUP_ID = "Ayu Islands"
    private const val TITLE = "Ayu Islands -- Syntax Moods"
    private const val BODY =
        "New control: Minimal / Standard / Rich / Maximum + " +
            "style modifiers. Settings -> Ayu Islands -> Syntax."
    private const val ACTION_LABEL = "Open Syntax tab"
    private const val CONFIGURABLE_DISPLAY_NAME = "Ayu Islands"
    private val log = Logger.getInstance(SyntaxModeUpgradeNotifier::class.java)

    fun maybeFire(project: Project? = null) {
        val props = PropertiesComponent.getInstance()
        if (props.getBoolean(FLAG_KEY, false)) return
        props.setValue(FLAG_KEY, true)
        try {
            val notification = Notification(GROUP_ID, TITLE, BODY, NotificationType.INFORMATION)
            notification.addAction(
                NotificationAction.createSimple(ACTION_LABEL) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, CONFIGURABLE_DISPLAY_NAME)
                },
            )
            Notifications.Bus.notify(notification, project)
        } catch (exception: RuntimeException) {
            log.warn("SyntaxModeUpgradeNotifier.maybeFire failed to publish notification", exception)
        }
    }
}
