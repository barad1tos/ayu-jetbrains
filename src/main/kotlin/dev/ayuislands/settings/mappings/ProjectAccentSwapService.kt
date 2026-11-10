package dev.ayuislands.settings.mappings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import dev.ayuislands.accent.rethrowIfCancelled
import dev.ayuislands.studio.StudioService
import org.jetbrains.annotations.TestOnly
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/** Retains the installed AWT lifecycle while the shared runtime owns focus transitions and native refresh. */
@Service(Service.Level.APP)
class ProjectAccentSwapService : Disposable {
    private var listener: AWTEventListener? = null

    /**
     * First-warn-only gate for `findProjectForWindow` exceptions. The listener fires on
     * every WINDOW_ACTIVATED — one broken frame would otherwise spam idea.log on every
     * alt-tab / dialog / popup. After the first warning, subsequent failures degrade to
     * DEBUG so they're still captured when a user enables debug logging for a report.
     */
    @Volatile
    private var frameResolutionFailureLogged: Boolean = false

    /**
     * Paired gate for [WindowManager.getInstance] returning null on shutdown
     * race. First failure is WARN, subsequent failures are DEBUG, so a user-submitted
     * idea.log captures the pathology while every alt-tab during shutdown does
     * not flood the log. Pattern A — log-once gate.
     */
    @Volatile
    private var windowManagerUnavailableLogged: Boolean = false

    fun install() {
        if (listener != null) return
        val awtListener =
            AWTEventListener { event ->
                if (event.id == WindowEvent.WINDOW_ACTIVATED) {
                    ApplicationManager
                        .getApplication()
                        .invokeLater({ onWindowActivated(event) }, ModalityState.nonModal())
                }
            }
        Toolkit.getDefaultToolkit().addAWTEventListener(awtListener, AWTEvent.WINDOW_EVENT_MASK)
        listener = awtListener
        LOG.info("ProjectAccentSwapService installed")
    }

    /**
     * Test seam exposing the `RuntimeException`-catching handler entry point. Production
     * code reaches this via the AWTEventListener registered in [install]; tests invoke it
     * directly without bringing up the AWT dispatch loop. Marked `@TestOnly` so the IDE
     * inspection flags any non-test callers.
     */
    @TestOnly
    internal fun onWindowActivatedForTest(event: AWTEvent) = onWindowActivated(event)

    private fun onWindowActivated(event: AWTEvent) {
        // Wrap the entire handler so a single exception (e.g. Color.decode on a corrupted stored
        // hex, WindowManager state inconsistency during shutdown) does not escape to the AWT
        // dispatcher. AWT does not remove failing listeners, so the listener would keep firing
        // — but each failure would dump a generic uncaught-exception trace into idea.log (SEVERE
        // in some IDE builds, which triggers a user-visible error balloon) with no project /
        // hex context. Ordinary failures become actionable LOG.error entries;
        // platform and coroutine cancellation still propagate to the caller.
        try {
            handleWindowActivated(event)
        } catch (exception: RuntimeException) {
            exception.rethrowIfCancelled()
            LOG.error("Project accent swap failed on window activation", exception)
        }
    }

    private fun handleWindowActivated(event: AWTEvent) {
        val window = (event as? WindowEvent)?.window ?: return
        val project = findProjectForWindow(window) ?: return
        if (project.isDisposed || project.isDefault) return

        StudioService.getInstance().focusChanged()
    }

    private fun findProjectForWindow(window: Window): Project? {
        // WindowManager.allProjectFrames can contain frames mid-dispose (shutdown race, window
        // closed between enumeration and access). Guard each frame access so one bad frame
        // doesn't escape the listener. Skip frames whose project is already disposed — we'd
        // short-circuit in handleWindowActivated anyway, but skipping here avoids pointless work.
        //
        // Pattern A — log-once gate. WindowManager.getInstance() can return null
        // during application shutdown after services have started disposing but
        // before AWT stops dispatching. Mirror the
        // AccentApplicator OS-active frame convention: WARN on the first
        // race so user-submitted logs surface it, DEBUG thereafter.
        val windowManager =
            WindowManager.getInstance() ?: run {
                if (!windowManagerUnavailableLogged) {
                    windowManagerUnavailableLogged = true
                    LOG.warn(
                        "WindowManager unavailable during window-to-project resolution " +
                            "(further occurrences logged at DEBUG)",
                    )
                } else {
                    LOG.debug("WindowManager unavailable during window-to-project resolution")
                }
                return null
            }
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
