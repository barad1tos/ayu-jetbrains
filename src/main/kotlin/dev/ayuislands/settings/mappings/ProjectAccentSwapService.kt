package dev.ayuislands.settings.mappings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.IJSwingUtilities
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
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

        // AccentApplicator updates UIManager + editor scheme. Editor and CodeGlance Pro refresh via
        // their own message-bus topics, but UIManager-only components (toolbar, tab underlines,
        // scrollbar chrome, focus rings) hold cached JBColor resolutions at construction time and
        // do not re-read from UIManager on plain `repaint()`. Force a component-tree LAF refresh
        // on the focused window so the accent change propagates to every painted descendant.
        // IJSwingUtilities skips non-UIResource components — the safe IntelliJ variant.
        try {
            IJSwingUtilities.updateComponentTreeUI(window)
        } catch (exception: RuntimeException) {
            LOG.warn("Component tree UI refresh failed for ${project.name}", exception)
        }
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
        val windowManager = WindowManager.getInstance()
        for (frame in windowManager.allProjectFrames) {
            val frameWindow = SwingUtilities.getWindowAncestor(frame.component)
            if (frameWindow === window) {
                return frame.project
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
