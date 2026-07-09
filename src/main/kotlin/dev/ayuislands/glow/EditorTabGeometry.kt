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
        if (selectedContentBounds != null) {
            log.debug("Editor overlay bounds from selected content: $selectedContentBounds")
            return selectedContentBounds
        }

        val selectedLabelBottom = findSelectedLabelBottom(editorTabs, host)
        if (selectedLabelBottom != null) {
            log.debug("Editor overlay top from selected tab label bottom: $selectedLabelBottom")
            return Rectangle(0, selectedLabelBottom, host.width, (host.height - selectedLabelBottom).coerceAtLeast(0))
        }

        val nestedLabelBottom = findNestedTabLabelBottom(editorTabs, host)
        if (nestedLabelBottom != null) {
            log.debug("Editor overlay top from nested tab label bottom: $nestedLabelBottom")
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
     * Limited to [maxDepth] levels to avoid expensive traversal on deep Swing trees.
     */
    private fun findEditorTabsRecursive(
        component: Component,
        maxDepth: Int = 8,
    ): Component? {
        if (maxDepth <= 0) return null
        if (component.javaClass.name.contains("EditorTabs")) return component
        if (component is Container) {
            for (child in component.components) {
                val found = findEditorTabsRecursive(child, maxDepth - 1)
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
                editorTabs.javaClass.methods.firstOrNull {
                    it.name == "getSelectedInfo" && it.parameterCount == 0
                } ?: return null
            val tabInfo = getSelectedInfo.invoke(editorTabs) ?: return null

            val getComponent =
                tabInfo.javaClass.methods.firstOrNull {
                    it.name == "getComponent" && it.parameterCount == 0
                } ?: return null
            val contentComponent = getComponent.invoke(tabInfo) as? JComponent ?: return null

            val parent = contentComponent.parent ?: return null
            val contentBounds = contentComponent.bounds

            @Suppress("ConvertExpressionToRectangleConstructor")
            val converted = SwingUtilities.convertRectangle(parent, contentBounds, host)
            // Sanity gate: selected content that starts at (or above) the
            // host's top edge means the tab strip was NOT subtracted — newer
            // tab layouts (2026.x) report the selected tab's component as
            // spanning the whole host. Trusting that rectangle put the
            // "Under tabs" glow strip along the window top. Fall through to
            // the label-based fallbacks, which measure the real strip.
            if (converted.y <= 0) return null
            return converted
        } catch (_: ReflectiveOperationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    /**
     * Use reflection to call `getSelectedLabel()` and return its bottom edge
     * converted into [host] coordinates.
     */
    private fun findSelectedLabelBottom(
        editorTabs: Container,
        host: JComponent,
    ): Int? {
        try {
            val getSelectedLabel =
                editorTabs.javaClass.methods.firstOrNull {
                    it.name == "getSelectedLabel" && it.parameterCount == 0
                } ?: return null
            val label = getSelectedLabel.invoke(editorTabs) as? JComponent ?: return null
            return labelBottomInHost(label, host)
        } catch (_: ReflectiveOperationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    /**
     * Recursively search for a visible TabLabel and return its bottom edge in
     * [host] coordinates. Limited to [maxDepth] levels to avoid expensive
     * traversal on deep Swing trees.
     */
    private fun findNestedTabLabelBottom(
        component: Component,
        host: JComponent,
        maxDepth: Int = 8,
    ): Int? {
        if (maxDepth <= 0) return null
        if (component.javaClass.name.contains("TabLabel") && component is JComponent && component.isVisible) {
            return labelBottomInHost(component, host)
        }
        if (component is Container) {
            for (child in component.components) {
                val found = findNestedTabLabelBottom(child, host, maxDepth - 1)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * [label]'s bottom edge converted into [host] coordinates, or null when
     * the conversion lands at or above the host top (a label outside the
     * host cannot anchor the strip). The previous implementation returned
     * `label.y + label.height` in the label parent's coordinate space, which
     * matched host coordinates only when the tabs component happened to sit
     * at the host origin.
     */
    private fun labelBottomInHost(
        label: JComponent,
        host: JComponent,
    ): Int? {
        val parent = label.parent ?: return null
        val bottom = SwingUtilities.convertPoint(parent, 0, label.y + label.height, host).y
        return bottom.takeIf { it > 0 }
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
