package dev.ayuislands.settings.mappings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.ui.ComponentTreeRefresher
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/**
 * Focus-swap bridge between [AccentResolver] (per-project override store) and
 * [AccentApplicator] (global UIManager). UIManager is a single JVM-wide table,
 * so the "current accent" is whatever was applied last. Without this service,
 * switching between two project windows would leave the accent stuck on
 * whichever project happened to trigger the most recent apply — meaning the
 * per-project override feature would not visibly isolate between windows.
 *
 * Listens for [WindowEvent.WINDOW_ACTIVATED] via [AWTEventListener] and, when
 * the activated window maps to a project, re-applies the resolved accent hex
 * if it differs from the last applied value. Idempotent and safe to install
 * from every [com.intellij.openapi.startup.ProjectActivity] call.
 */
class ProjectAccentSwapService : Disposable {
    @Volatile
    private var lastAppliedProject: Project? = null

    @Volatile
    private var lastAppliedHex: String? = null

    private var listener: AWTEventListener? = null

    /**
     * First-warn-only gate for `findProjectForWindow` exceptions. The listener fires on
     * every WINDOW_ACTIVATED — one broken frame would otherwise spam idea.log on every
     * alt-tab / dialog / popup. After the first warning, subsequent failures degrade to
     * DEBUG so they're still captured when a user enables debug logging for a report.
     */
    @Volatile
    private var frameResolutionFailureLogged: Boolean = false

    fun install() {
        if (listener != null) return
        val awtListener =
            AWTEventListener { event ->
                if (event.id == WindowEvent.WINDOW_ACTIVATED) {
                    SwingUtilities.invokeLater { onWindowActivated(event) }
                }
            }
        Toolkit.getDefaultToolkit().addAWTEventListener(awtListener, AWTEvent.WINDOW_EVENT_MASK)
        listener = awtListener
        LOG.info("ProjectAccentSwapService installed")
    }

    private fun onWindowActivated(event: AWTEvent) {
        // Wrap the entire handler so a single exception (e.g. Color.decode on a corrupted stored
        // hex, WindowManager state inconsistency during shutdown) does not escape to the AWT
        // dispatcher. AWT does not remove failing listeners, so the listener would keep firing
        // — but each failure would dump a generic uncaught-exception trace into idea.log (SEVERE
        // in some IDE builds, which triggers a user-visible error balloon) with no project /
        // hex context. The catch here converts that into an actionable LOG.error.
        try {
            handleWindowActivated(event)
        } catch (exception: RuntimeException) {
            LOG.error("Project accent swap failed on window activation", exception)
        }
    }

    private fun handleWindowActivated(event: AWTEvent) {
        val window = (event as? WindowEvent)?.window ?: return
        val project = findProjectForWindow(window) ?: return
        if (project.isDisposed || project.isDefault) return
        if (project === lastAppliedProject) return

        val variant = AyuVariant.detect() ?: return
        val effectiveHex = AccentResolver.resolve(project, variant)

        lastAppliedProject = project
        if (effectiveHex == lastAppliedHex) return

        lastAppliedHex = effectiveHex
        AccentApplicator.apply(effectiveHex)

        // AccentApplicator updates UIManager + editor scheme. UIManager-only components
        // (toolbar, tab underlines, scrollbar chrome, focus rings) hold cached JBColor
        // resolutions captured at construction time and will not re-read UIManager on a
        // plain `repaint()`. ComponentTreeRefresher does the component-tree LAF refresh
        // AND fires ComponentTreeRefreshedTopic so managers whose customizations got
        // reset by the walk (scrollbar hiders etc.) reapply themselves.
        ComponentTreeRefresher.walkAndNotify(project, window)
        LOG.info("Project accent swapped to $effectiveHex for ${project.name}")
    }

    /**
     * Notify after an external apply (settings panel, rotation, startup activity) so the
     * cache matches the current UIManager state and we don't skip the next real swap.
     */
    fun notifyExternalApply(hex: String) {
        lastAppliedHex = hex
    }

    private fun findProjectForWindow(window: Window): Project? {
        // WindowManager.allProjectFrames can contain frames mid-dispose (shutdown race, window
        // closed between enumeration and access). Guard each frame access so one bad frame
        // doesn't escape the listener. Skip frames whose project is already disposed — we'd
        // short-circuit in handleWindowActivated anyway, but skipping here avoids pointless work.
        val windowManager = WindowManager.getInstance()
        for (frame in windowManager.allProjectFrames) {
            try {
                val project = frame.project ?: continue
                if (project.isDisposed) continue
                val frameWindow = SwingUtilities.getWindowAncestor(frame.component) ?: continue
                if (frameWindow === window) return project
            } catch (exception: RuntimeException) {
                // Warn on the first failure so user-submitted logs surface it, then degrade to
                // DEBUG — WINDOW_ACTIVATED fires on every alt-tab / dialog open, so one broken
                // frame would otherwise spam idea.log. The loop continues so one bad frame
                // doesn't block resolution of a healthy one.
                if (!frameResolutionFailureLogged) {
                    frameResolutionFailureLogged = true
                    LOG.warn(
                        "Skipping frame during window-to-project resolution " +
                            "(further failures logged at DEBUG)",
                        exception,
                    )
                } else {
                    LOG.debug("Skipping frame during window-to-project resolution", exception)
                }
            }
        }
        return null
    }

    override fun dispose() {
        listener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
            listener = null
        }
        lastAppliedProject = null
        lastAppliedHex = null
        LOG.info("ProjectAccentSwapService disposed")
    }

    companion object {
        private val LOG = logger<ProjectAccentSwapService>()

        fun getInstance(): ProjectAccentSwapService =
            ApplicationManager
                .getApplication()
                .getService(ProjectAccentSwapService::class.java)
    }
}
