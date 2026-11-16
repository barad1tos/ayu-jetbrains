package dev.ayuislands.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.ui.ComponentTreeRefreshedListener
import dev.ayuislands.ui.ComponentTreeRefreshedTopic
import java.awt.Dimension
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import javax.swing.JScrollBar
import javax.swing.JScrollPane

/**
 * Per-project service that manages editor scrollbar visibility.
 *
 * Instead of setting the scroll policy to NEVER (which disables scrolling entirely),
 * we override the scrollbar's [JScrollBar.getPreferredSize] to return zero dimensions.
 * This hides the scrollbar visually while preserving mouse wheel, trackpad, and
 * keyboard scrolling.
 */
@Service(Service.Level.PROJECT)
class EditorScrollbarManager(
    private val project: Project,
) : Disposable {
    /** Scroll panes whose scrollbars have been patched, so we can restore them. */
    private val patchedScrollPanes = WeakHashMap<JScrollPane, PatchedState>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    if (!LicenseChecker.isLicensedOrGrace()) return
                    val editor = event.editor
                    if (editor.project != project) return
                    applyToEditor(editor as? EditorEx ?: return)
                }
            },
            this,
        )

        // Self-heal after IJSwingUtilities.updateComponentTreeUI (startup, focus swap,
        // LAF change) — that walk calls updateUI() on every JScrollBar descendant and
        // resets the preferredSize=0 trick this manager uses to hide scrollbars.
        //
        project.messageBus
            .connect(this)
            .subscribe(
                ComponentTreeRefreshedTopic.TOPIC,
                ComponentTreeRefreshedListener {
                    resetOriginalSizeCache()
                    apply()
                },
            )
    }

    /**
     * Test-only entry point for [resetOriginalSizeCache]. The listener path that calls it
     * goes through `project.messageBus.syncPublisher(...)` which is impractical to wire
     * without a real Project; this seam lets unit tests verify the catch path directly.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun resetOriginalSizeCacheForTest() = resetOriginalSizeCache()

    /** Recapture an owned baseline only when the refresh actually reset the scrollbar. */
    private fun resetOriginalSizeCache() {
        for (pane in patchedScrollPanes.keys.toList()) {
            val tracked = patchedScrollPanes[pane] ?: continue
            resetBaseline(pane.verticalScrollBar, tracked.vertical)
            resetBaseline(pane.horizontalScrollBar, tracked.horizontal)
        }
    }

    private fun resetBaseline(
        bar: JScrollBar,
        patch: Patch?,
    ) {
        if (patch?.owns(bar) != true || bar.preferredSize == ZERO_SIZE) return
        try {
            bar.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, null)
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to clear scrollbar original-size cache on refresh", exception)
        }
    }

    fun apply() {
        val licensed = LicenseChecker.isLicensedOrGrace()
        val editors = EditorFactory.getInstance().allEditors
        for (editor in editors) {
            if (editor.project != project) continue
            val editorEx = editor as? EditorEx ?: continue
            if (licensed) {
                applyToEditor(editorEx)
            } else {
                restoreEditor(editorEx.scrollPane)
            }
        }
    }

    private fun applyToEditor(editor: EditorEx) {
        val scrollPane = editor.scrollPane
        val state = AyuIslandsSettings.getInstance().state
        val hideVertical = state.hideEditorVScrollbar
        val hideHorizontal = state.hideEditorHScrollbar

        if (!hideVertical && !hideHorizontal) {
            restoreEditor(scrollPane)
            return
        }

        val patched = patchedScrollPanes.getOrPut(scrollPane) { PatchedState() }
        patched.vertical = applyAxis(scrollPane.verticalScrollBar, patched.vertical, hideVertical)
        patched.horizontal = applyAxis(scrollPane.horizontalScrollBar, patched.horizontal, hideHorizontal)
        if (patched.vertical == null && patched.horizontal == null) patchedScrollPanes.remove(scrollPane)
        scrollPane.revalidate()
    }

    private fun applyAxis(
        bar: JScrollBar,
        previous: Patch?,
        hide: Boolean,
    ): Patch? {
        if (!hide || previous?.target() !== bar) restoreScrollBar(previous)
        if (!hide) return null
        val patch =
            if (previous?.owns(bar) == true) {
                previous
            } else {
                if (bar.getClientProperty(ORIGINAL_PREFERRED_SIZE_KEY) != null) return null
                val baseline = Dimension(bar.preferredSize)
                Patch(WeakReference(bar), baseline, bar.isPreferredSizeSet).also {
                    bar.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, baseline)
                }
            }
        if (!patch.owns(bar)) return null
        if (bar.preferredSize != ZERO_SIZE || !bar.isPreferredSizeSet) {
            bar.preferredSize = Dimension(ZERO_SIZE)
            bar.revalidate()
        }
        return patch
    }

    private fun restoreScrollBar(patch: Patch?) {
        val bar = patch?.target() ?: return
        if (!patch.owns(bar)) return
        if (bar.isPreferredSizeSet && bar.preferredSize == ZERO_SIZE) {
            bar.preferredSize = if (patch.isExplicit) Dimension(patch.baseline) else null
        }
        if (patch.owns(bar)) bar.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, null)
    }

    private fun restoreEditor(scrollPane: JScrollPane) {
        val patched = patchedScrollPanes[scrollPane] ?: return
        restoreScrollBar(patched.vertical)
        restoreScrollBar(patched.horizontal)
        patchedScrollPanes.remove(scrollPane)
    }

    override fun dispose() {
        patchedScrollPanes.keys.toList().forEach(::restoreEditor)
    }

    private class PatchedState(
        var vertical: Patch? = null,
        var horizontal: Patch? = null,
    )

    private class Patch(
        private val scrollbar: WeakReference<JScrollBar>,
        val baseline: Dimension,
        val isExplicit: Boolean,
    ) {
        // A strong reference to the bar would keep the weak map's parent pane alive.
        fun target(): JScrollBar? = scrollbar.get()

        fun owns(bar: JScrollBar): Boolean =
            target() === bar && bar.getClientProperty(ORIGINAL_PREFERRED_SIZE_KEY) === baseline
    }

    companion object {
        private val LOG = logger<EditorScrollbarManager>()
        private const val ORIGINAL_PREFERRED_SIZE_KEY = "ayuIslands.originalPreferredSize"
        private val ZERO_SIZE = Dimension(0, 0)

        fun getInstance(project: Project): EditorScrollbarManager =
            project.getService(
                EditorScrollbarManager::class.java,
            )
    }
}
