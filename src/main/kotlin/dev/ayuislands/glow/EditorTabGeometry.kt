package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent

/**
 * Tab strip height calculation and editor-tab repaint utilities.
 *
 * Falls back to [DEFAULT_TAB_HEIGHT] when the expected Swing components
 * are not found, so glow overlays render at a reasonable offset even
 * when the IDE restructures its tab implementation.
 */
object EditorTabGeometry {
    private val log = logger<EditorTabGeometry>()

    /** Fallback tab strip height when TabLabel cannot be located. */
    const val DEFAULT_TAB_HEIGHT = 28

    /**
     * Calculate the pixel height of the tab strip inside [host] (typically EditorsSplitters).
     *
     * Looks for an EditorTabs child, then finds a TabLabel within it and returns
     * `tabLabel.y + tabLabel.height`. Returns [DEFAULT_TAB_HEIGHT] if either
     * component is missing.
     */
    fun calculateTabStripHeight(host: JComponent): Int {
        val editorTabs = ComponentHierarchyUtils.findChildByClassName(host, "EditorTabs") as? Container
        if (editorTabs == null) {
            log.warn("EditorTabs component not found in ${host.javaClass.name}, using DEFAULT_TAB_HEIGHT")
            return DEFAULT_TAB_HEIGHT
        }

        for (child in editorTabs.components) {
            if (child.javaClass.name.contains("TabLabel")) {
                return child.y + child.height
            }
        }

        log.warn("TabLabel not found in EditorTabs, using DEFAULT_TAB_HEIGHT")
        return DEFAULT_TAB_HEIGHT
    }

    /**
     * Walk up from [editorComponent] to find the EditorTabs or JBEditorTabs ancestor.
     *
     * Returns null if no matching ancestor is found.
     */
    fun findEditorTabsComponent(editorComponent: Component): Component? {
        var current: Component? = editorComponent.parent
        while (current != null) {
            val name = current.javaClass.name
            if (name.contains("EditorTabs") || name.contains("JBEditorTabs")) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /**
     * Repaint the editor tabs component for the given [project].
     *
     * Locates the active editor's tab container and triggers a repaint.
     * Logs a warning if the tab component cannot be found.
     */
    fun repaintEditorTabs(project: Project) {
        try {
            val editorComponent = FileEditorManager.getInstance(project).selectedEditor?.component ?: return
            val tabs = findEditorTabsComponent(editorComponent)
            if (tabs != null) {
                tabs.repaint()
            } else {
                log.warn("Could not find EditorTabs/JBEditorTabs for repaint")
            }
        } catch (exception: RuntimeException) {
            log.debug("Could not repaint editor tabs", exception)
        }
    }
}
