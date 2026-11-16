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

        val patched = patchedScrollPanes.getOrPut(scrollPane) { PatchedState() }
        patched.vertical = applyAxis(scrollPane.verticalScrollBar, patched.vertical, hideVertical)
        patched.horizontal = applyAxis(scrollPane.horizontalScrollBar, patched.horizontal, hideHorizontal)
        if (patched.isEmpty()) patchedScrollPanes.remove(scrollPane)
        scrollPane.revalidate()
    }

    private fun applyAxis(
        bar: JScrollBar,
        previous: Patch?,
        hide: Boolean,
    ): Patch? {
        if (!hide) return restoreScrollBar(previous)
        if (previous?.target() !== bar) {
            restoreScrollBar(previous)?.let { return it }
        }
        return hideScrollBar(bar, previous)
    }

    private fun hideScrollBar(
        bar: JScrollBar,
        previous: Patch?,
    ): Patch? {
        var attempted = previous
        try {
            val owned = previous?.owns(bar) == true
            if (!owned && bar.getClientProperty(ORIGINAL_PREFERRED_SIZE_KEY) != null) return null
            val explicit = bar.isPreferredSizeSet
            val current = bar.preferredSize
            if (owned && explicit && current == ZERO_SIZE) return previous

            val patch = Patch(WeakReference(bar), Dimension(current), explicit)
            // Swing can notify listeners after storing a value, even when the setter throws.
            attempted = patch
            tryScrollbarWrite("baseline") { bar.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, patch.baseline) }
            if (patch.owns(bar) && (!explicit || current != ZERO_SIZE)) {
                bar.preferredSize = Dimension(ZERO_SIZE)
                bar.revalidate()
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to hide editor scrollbar", exception)
        }
        return attempted?.takeIf { it.owns(bar) } ?: previous?.takeIf { it.owns(bar) }
    }

    private fun restoreScrollBar(patch: Patch?): Patch? {
        val bar = patch?.target() ?: return null
        if (!patch.owns(bar)) return null
        try {
            if (hasHiddenSize(bar)) {
                val restored = if (patch.isExplicit) Dimension(patch.baseline) else null
                val wrote = tryScrollbarWrite("preferred size") { bar.preferredSize = restored }
                if (!wrote && hasHiddenSize(bar)) return patch.takeIf { it.owns(bar) }
            }
            if (patch.owns(bar)) {
                tryScrollbarWrite("baseline") { bar.putClientProperty(ORIGINAL_PREFERRED_SIZE_KEY, null) }
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to restore editor scrollbar", exception)
        }
        return patch.takeIf { it.owns(bar) }
    }

    private fun hasHiddenSize(bar: JScrollBar): Boolean = bar.isPreferredSizeSet && bar.preferredSize == ZERO_SIZE

    private inline fun tryScrollbarWrite(
        field: String,
        write: () -> Unit,
    ): Boolean =
        try {
            write()
            true
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to update editor scrollbar $field", exception)
            false
        }

    private fun restoreEditor(scrollPane: JScrollPane) {
        val patched = patchedScrollPanes[scrollPane] ?: return
        patched.vertical = restoreScrollBar(patched.vertical)
        patched.horizontal = restoreScrollBar(patched.horizontal)
        if (patched.isEmpty()) patchedScrollPanes.remove(scrollPane)
    }

    override fun dispose() {
        patchedScrollPanes.keys.toList().forEach(::restoreEditor)
    }

    private class PatchedState(
        var vertical: Patch? = null,
        var horizontal: Patch? = null,
    ) {
        fun isEmpty(): Boolean = vertical == null && horizontal == null
    }

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
