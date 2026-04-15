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
        // Drop the cached "original preferred size" client property before reapplying so the
        // next hideScrollBar call captures the freshly-installed default. Without this, the
        // first apply's original-size snapshot (captured under the previous LAF) would be what
        // restore returns after a theme switch, silently drifting the restored scrollbar
        // dimensions away from the new theme's default.
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
     * Clear `ORIGINAL_PREFERRED_SIZE_KEY` on every currently-patched scrollbar so the next
     * [hideScrollBar] call captures the post-refresh default instead of serving a stale
     * pre-LAF-change dimension.
     *
     * Iterates a snapshot of the keys, not the live view, because [patchedScrollPanes] is a
     * [WeakHashMap] that expunges stale entries on any access — including during iteration —
     * which can race with a concurrent `editorCreated` listener mutation elsewhere on the
     * EDT. The snapshot plus try/catch keep the listener alive even if Swing throws from
     * a downstream `putClientProperty`.
     */
    private fun resetOriginalSizeCache() {
        val panes = patchedScrollPanes.keys.toList()
        for (scrollPane in panes) {
            try {
                scrollPane.verticalScrollBar?.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, null)
                scrollPane.horizontalScrollBar?.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, null)
            } catch (exception: RuntimeException) {
                LOG.warn("Failed to clear scrollbar original-size cache on refresh", exception)
            }
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

        // Apply is idempotent: we re-apply `hideScrollBar` every call without consulting
        // `patched.verticalHidden` because the platform can silently restore the default
        // preferred size during LAF changes and component-tree refreshes. The cached flag
        // would still read "true" while the scrollbar is visible again, so a flag-gated
        // call path would skip re-hiding. `hideScrollBar` preserves the original size only
        // on the first call (client property is never overwritten), so repeated invocations
        // are safe.
        val patched = patchedScrollPanes.getOrPut(scrollPane) { PatchedState() }
        if (hideVertical) {
            hideScrollBar(scrollPane.verticalScrollBar)
            patched.verticalHidden = true
        } else if (patched.verticalHidden) {
            restoreScrollBar(scrollPane.verticalScrollBar)
            patched.verticalHidden = false
        }
        if (hideHorizontal) {
            hideScrollBar(scrollPane.horizontalScrollBar)
            patched.horizontalHidden = true
        } else if (patched.horizontalHidden) {
            restoreScrollBar(scrollPane.horizontalScrollBar)
            patched.horizontalHidden = false
        }
        scrollPane.revalidate()
    }

    private fun hideScrollBar(scrollBar: JScrollBar) {
        if (scrollBar.getClientProperty(ORIGINAL_PREFERRED_SIZE_KEY) == null) {
            val originalSize = scrollBar.preferredSize?.let { Dimension(it) }
            scrollBar.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, originalSize)
        }
        scrollBar.preferredSize = ZERO_SIZE
        scrollBar.revalidate()
    }

    private fun restoreScrollBar(scrollBar: JScrollBar) {
        val originalSize = scrollBar.getClientProperty(ORIGINAL_PREFERRED_SIZE_KEY) as? Dimension
        if (originalSize != null) {
            scrollBar.preferredSize = originalSize
            scrollBar.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, null)
        }
    }

    private fun restoreEditor(scrollPane: JScrollPane) {
        val patched = patchedScrollPanes.remove(scrollPane) ?: return
        if (patched.verticalHidden) {
            restoreScrollBar(scrollPane.verticalScrollBar)
        }
        if (patched.horizontalHidden) {
            restoreScrollBar(scrollPane.horizontalScrollBar)
        }
    }

    override fun dispose() {
        for ((scrollPane, patched) in patchedScrollPanes) {
            if (patched.verticalHidden) {
                restoreScrollBar(scrollPane.verticalScrollBar)
            }
            if (patched.horizontalHidden) {
                restoreScrollBar(scrollPane.horizontalScrollBar)
            }
        }
        patchedScrollPanes.clear()
    }

    /**
     * Mutable per-scrollPane patch state. Not a `data class` — we mutate flags in place on hot
     * paths (every apply call), and the auto-generated equals/hashCode from `data class` would
     * be a foot-gun if anything ever used PatchedState as a map key (we use it as a WeakHashMap
     * *value*, not key). Plain class with `var` matches the imperative usage.
     */
    private class PatchedState(
        var verticalHidden: Boolean = false,
        var horizontalHidden: Boolean = false,
    )

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
