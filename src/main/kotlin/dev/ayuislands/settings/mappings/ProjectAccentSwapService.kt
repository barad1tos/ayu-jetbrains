package dev.ayuislands.settings.mappings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.ui.ComponentTreeRefresher
import org.jetbrains.annotations.TestOnly
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

    /**
     * Paired gate for [WindowManager.getInstance] returning null on shutdown
     * race. Mirrors the [AccentApplicator.windowManagerUnavailableLogged]
     * convention — first failure WARN, subsequent DEBUG, so a user-submitted
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
                    SwingUtilities.invokeLater { onWindowActivated(event) }
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

        // Re-resolve on every activation. Alt-tab away to a non-IDE app and back reports the
        // same project, but any external apply (rotation tick, Settings panel Apply, LAF
        // change — anything that reaches `notifyExternalApply`) may have pushed a different
        // color into the JVM-wide UIManager/globalScheme since the last activation. The
        // resolver call is cheap (canonicalPath + HashMap lookup); the hex gate below still
        // skips the expensive apply when the resolver output matches.
        val variant = AyuVariant.detect() ?: return
        val effectiveHex = AccentResolver.resolve(project, variant)

        val hexChanged = effectiveHex != lastAppliedHex
        if (hexChanged) {
            // Different hex from last apply: re-run the full apply path. UIManager
            // writes, EP elements, editor keys, AND integrations are all refreshed
            // through AccentApplicator.applyFromHexString -> apply.
            val applied = AccentApplicator.applyFromHexString(effectiveHex)
            if (!applied) {
                LOG.warn("Skipping swap publish: applyFromHexString rejected '$effectiveHex'")
                return
            }
            lastAppliedHex = effectiveHex
        } else {
            // Same hex but a different project just gained focus (or alt-tab back to the
            // same project). The app-scoped CGP `CodeGlanceConfigService` and IR `IrConfig`
            // caches still hold whoever wrote last; force-refresh them so the newly-focused
            // minimap + indent panels paint the correct per-project accent (Bug B fix, D-07).
            //
            // walkAndNotify alone CANNOT close Bug B because CGP and IR do not subscribe to
            // ComponentTreeRefreshedTopic — they read from the app-scoped cache directly.
            // The two calls below push the per-project hex into those caches without
            // re-running the apply path's UIManager work (already correct for the unchanged
            // hex). Resolves RESEARCH §Open Questions §1 (direct integration call).
            //
            // Pattern J — gate IR refresh on the integration toggle. IndentRainbowSync.apply
            // itself reverts when irIntegrationEnabled is false, so calling it on every
            // alt-tab from a user who disabled IR would silently re-stamp IR's IrConfig
            // with a DEFAULT palette write per focus swap. Skip the call entirely so a
            // disabled integration is truly disabled, not "disabled with a side-effect on
            // every focus swap". CGP gates internally and short-circuits the same way.
            AccentApplicator.syncCodeGlanceProViewportForSwap(effectiveHex)
            if (AyuIslandsSettings.getInstance().state.irIntegrationEnabled) {
                IndentRainbowSync.apply(variant, effectiveHex)
            }
        }

        // Always refresh the component tree on focus swap — preserves the
        // "WINDOW_ACTIVATED always refreshes subscribers" invariant. AccentApplicator
        // updates UIManager + editor scheme but UIManager-only components (toolbar, tab
        // underlines, scrollbar chrome, focus rings) hold cached JBColor resolutions
        // captured at construction time. ComponentTreeRefresher does the component-tree
        // LAF refresh AND fires ComponentTreeRefreshedTopic so managers whose
        // customizations got reset by the walk (scrollbar hiders etc.) reapply themselves.
        ComponentTreeRefresher.walkAndNotify(project, window)
        LOG.info(
            "Project accent refreshed for ${project.name} (hex=$effectiveHex, changed=$hexChanged)",
        )
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
        //
        // Pattern A — log-once gate. WindowManager.getInstance() can return null
        // during application shutdown after services have started disposing but
        // before AWT stops dispatching. Mirror the
        // [AccentApplicator.osActiveProjectFrame] convention: WARN on the first
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
