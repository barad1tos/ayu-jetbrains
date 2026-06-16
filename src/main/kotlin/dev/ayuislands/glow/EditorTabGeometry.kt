package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.SwingUtilities

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

    private val warnedHosts: MutableSet<String> =
        java.util.concurrent
            .ConcurrentHashMap
            .newKeySet()

    /**
     * Calculate the pixel height of the tab strip inside [host] (typically EditorsSplitters).
     *
     * Looks for an EditorTabs child, then finds a TabLabel within it and returns
     * `tabLabel.y + tabLabel.height`. Returns [DEFAULT_TAB_HEIGHT] if either
     * component is missing (logs warning once per host class).
     */
    fun calculateTabStripHeight(host: JComponent): Int {
        val editorTabs = ComponentHierarchyUtils.findChildByClassName(host, "EditorTabs") as? Container
        if (editorTabs == null) {
            if (warnedHosts.add("EditorTabs:${host.javaClass.name}")) {
                log.warn("EditorTabs component not found in ${host.javaClass.name}, using DEFAULT_TAB_HEIGHT")
            }
            return DEFAULT_TAB_HEIGHT
        }

        for (child in editorTabs.components) {
            if (child.javaClass.name.contains("TabLabel")) {
                return child.y + child.height
            }
        }

        if (warnedHosts.add("TabLabel:${editorTabs.javaClass.name}")) {
            log.warn("TabLabel not found in EditorTabs, using DEFAULT_TAB_HEIGHT")
        }
        return DEFAULT_TAB_HEIGHT
    }

    /**
     * Calculate the editor overlay bounds relative to [host].
     *
     * Prefers the selected editor content component bounds (via reflection on
     * `JBTabsImpl.getSelectedInfo()` → `TabInfo.getComponent()`). Falls back to
     * the selected `TabLabel` bottom edge, then any visible nested `TabLabel`,
     * and finally [DEFAULT_TAB_HEIGHT].
     */
    fun calculateEditorOverlayBounds(host: JComponent): Rectangle {
        val editorTabs = findEditorTabsRecursive(host) as? Container
        if (editorTabs == null) {
            if (warnedHosts.add("EditorTabsOverlay:${host.javaClass.name}")) {
                log.warn("EditorTabs not found in ${host.javaClass.name}, using DEFAULT_TAB_HEIGHT for overlay")
            }
            val tabStripBottom = DEFAULT_TAB_HEIGHT
            return Rectangle(0, tabStripBottom, host.width, (host.height - tabStripBottom).coerceAtLeast(0))
        }

        val selectedContentBounds = findSelectedContentBounds(editorTabs, host)
        if (selectedContentBounds != null) return selectedContentBounds

        val selectedLabelBottom = findSelectedLabelBottom(editorTabs)
        if (selectedLabelBottom != null) {
            return Rectangle(0, selectedLabelBottom, host.width, (host.height - selectedLabelBottom).coerceAtLeast(0))
        }

        val nestedLabelBottom = findNestedTabLabelBottom(editorTabs)
        if (nestedLabelBottom != null) {
            return Rectangle(0, nestedLabelBottom, host.width, (host.height - nestedLabelBottom).coerceAtLeast(0))
        }

        if (warnedHosts.add("TabLabelOverlay:${editorTabs.javaClass.name}")) {
            log.warn("TabLabel not found in EditorTabs, using DEFAULT_TAB_HEIGHT for overlay")
        }
        val tabStripBottom = DEFAULT_TAB_HEIGHT
        return Rectangle(0, tabStripBottom, host.width, (host.height - tabStripBottom).coerceAtLeast(0))
    }

    /**
     * Find EditorTabs by recursing into the component tree (not just direct children).
     */
    private fun findEditorTabsRecursive(component: Component): Component? {
        if (component.javaClass.name.contains("EditorTabs")) return component
        if (component is Container) {
            for (child in component.components) {
                val found = findEditorTabsRecursive(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Use reflection to call `getSelectedInfo()` → `getComponent()` and return
     * the component's bounds converted to [host] coordinates.
     */
    private fun findSelectedContentBounds(
        editorTabs: Container,
        host: JComponent,
    ): Rectangle? {
        try {
            val getSelectedInfo =
                editorTabs.javaClass.methods.firstOrNull { it.name == "getSelectedInfo" }
                    ?: return null
            val tabInfo = getSelectedInfo(editorTabs) ?: return null

            val getComponent =
                tabInfo.javaClass.methods.firstOrNull { it.name == "getComponent" }
                    ?: return null
            val contentComponent = getComponent(tabInfo) as? JComponent ?: return null

            val contentBounds = contentComponent.bounds
            @Suppress("ConvertExpressionToRectangleConstructor")
            return SwingUtilities.convertRectangle(contentComponent.parent, contentBounds, host)
        } catch (_: ReflectiveOperationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    /**
     * Use reflection to call `getSelectedLabel()` and return its bottom edge (y + height).
     */
    private fun findSelectedLabelBottom(editorTabs: Container): Int? {
        try {
            val getSelectedLabel =
                editorTabs.javaClass.methods.firstOrNull { it.name == "getSelectedLabel" }
                    ?: return null
            val label = getSelectedLabel(editorTabs) as? JComponent ?: return null
            return label.y + label.height
        } catch (_: ReflectiveOperationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    /**
     * Recursively search for a visible TabLabel and return its bottom edge.
     */
    private fun findNestedTabLabelBottom(component: Component): Int? {
        if (component.javaClass.name.contains("TabLabel") && component is JComponent && component.isVisible) {
            return component.y + component.height
        }
        if (component is Container) {
            for (child in component.components) {
                val found = findNestedTabLabelBottom(child)
                if (found != null) return found
            }
        }
        return null
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
