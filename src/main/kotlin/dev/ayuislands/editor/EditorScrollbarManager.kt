package dev.ayuislands.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
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
        project.messageBus
            .connect(this)
            .subscribe(
                ComponentTreeRefreshedTopic.TOPIC,
                ComponentTreeRefreshedListener { apply() },
            )
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

    private data class PatchedState(
        var verticalHidden: Boolean = false,
        var horizontalHidden: Boolean = false,
    )

    companion object {
        private const val ORIGINAL_PREFERRED_SIZE_KEY = "ayuIslands.originalPreferredSize"
        private val ZERO_SIZE = Dimension(0, 0)

        fun getInstance(project: Project): EditorScrollbarManager =
            project.getService(
                EditorScrollbarManager::class.java,
            )
    }
}
