package dev.ayuislands.whatsnew

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction

/**
 * Tools-menu action that re-opens the What's New tab on demand.
 *
 * Behavior:
 *  - Disabled when no manifest exists for the current plugin version (no
 *    content to show — better to grey out the menu item than open an empty tab).
 *  - When clicked, calls [WhatsNewLauncher.openManually], which bypasses the
 *    persistent `lastWhatsNewShownVersion` gate (the user explicitly asked).
 */
internal class ShowWhatsNewAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        // Disable when no current-version manifest exists. Use the cheap probe;
        // the launcher does the same check before scheduling, so this is purely
        // for UI affordance. A null descriptor would mean the platform can't
        // find our own plugin — log it once so the anomaly is debuggable
        // without spamming the log on every BGT update tick.
        val descriptor =
            com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId
                    .getId("com.ayuislands.theme"),
            )
        if (descriptor == null) {
            if (descriptorNullLogged.compareAndSet(false, true)) {
                LOG.warn("Ayu What's New: plugin descriptor lookup returned null in update()")
            }
            event.presentation.isEnabledAndVisible = false
            return
        }
        val available = WhatsNewManifestLoader.manifestExists(descriptor.version)
        event.presentation.isEnabledAndVisible = available
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val scheduled = WhatsNewLauncher.openManually(project)
        if (!scheduled) {
            // update() should have hidden the menu when no manifest exists, but
            // BGT update can race with the click. The launcher returns false in
            // two cases: descriptor missing (a real anomaly — WARN) or no
            // manifest for current version (expected on patches — INFO).
            val descriptor =
                com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                    com.intellij.openapi.extensions.PluginId
                        .getId("com.ayuislands.theme"),
                )
            if (descriptor == null) {
                LOG.warn("Ayu What's New: manual open declined — plugin descriptor missing")
            } else {
                LOG.info("Ayu What's New: manual open declined — no manifest for ${descriptor.version}")
            }
        }
    }

    companion object {
        private val LOG = logger<ShowWhatsNewAction>()
        private val descriptorNullLogged =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
    }
}
