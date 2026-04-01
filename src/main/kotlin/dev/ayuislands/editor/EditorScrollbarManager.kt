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
import java.util.WeakHashMap
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/** Per-project service that manages editor scrollbar visibility. */
@Service(Service.Level.PROJECT)
class EditorScrollbarManager(
    private val project: Project,
) : Disposable {
    /** Original (vertical, horizontal) scrollbar policies keyed by scroll pane. */
    private val originalPolicies = WeakHashMap<JScrollPane, Pair<Int, Int>>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    val editor = event.editor
                    if (editor.project != project) return
                    applyToEditor(editor as? EditorEx ?: return)
                }
            },
            this,
        )
    }

    fun apply() {
        if (!LicenseChecker.isLicensedOrGrace()) return
        val editors = EditorFactory.getInstance().allEditors
        for (editor in editors) {
            if (editor.project != project) continue
            applyToEditor(editor as? EditorEx ?: continue)
        }
    }

    private fun applyToEditor(editor: EditorEx) {
        val scrollPane = editor.scrollPane
        val state = AyuIslandsSettings.getInstance().state
        val hideVertical = state.hideEditorVScrollbar
        val hideHorizontal = state.hideEditorHScrollbar

        if (hideVertical || hideHorizontal) {
            if (scrollPane !in originalPolicies) {
                originalPolicies[scrollPane] =
                    Pair(
                        scrollPane.verticalScrollBarPolicy,
                        scrollPane.horizontalScrollBarPolicy,
                    )
            }
            if (hideVertical) {
                scrollPane.verticalScrollBarPolicy =
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            }
            if (hideHorizontal) {
                scrollPane.horizontalScrollBarPolicy =
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }
        } else {
            restoreEditor(scrollPane)
        }
    }

    private fun restoreEditor(scrollPane: JScrollPane) {
        val original = originalPolicies.remove(scrollPane) ?: return
        scrollPane.verticalScrollBarPolicy = original.first
        scrollPane.horizontalScrollBarPolicy = original.second
    }

    override fun dispose() {
        for ((scrollPane, original) in originalPolicies) {
            scrollPane.verticalScrollBarPolicy = original.first
            scrollPane.horizontalScrollBarPolicy = original.second
        }
        originalPolicies.clear()
    }

    companion object {
        fun getInstance(project: Project): EditorScrollbarManager =
            project.getService(
                EditorScrollbarManager::class.java,
            )
    }
}
