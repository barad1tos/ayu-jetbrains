package dev.ayuislands.projectview

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.toolwindow.ToolWindowAutoFitter
import java.awt.Component
import java.awt.Container
import java.beans.PropertyChangeListener
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.tree.TreeCellRenderer

/** Per-project service that manages Project tool window tweaks (scrollbar, root path). */
@Service(Service.Level.PROJECT)
class ProjectViewScrollbarManager(
    private val project: Project,
) : Disposable {
    private var originalScrollbarPolicy: Int? = null
    private var registryKeyModified = false
    private var trackedTree: JTree? = null
    private var rendererListener: PropertyChangeListener? = null
    private val autoFitter =
        ToolWindowAutoFitter(
            project = project,
            toolWindowId = "Project",
            minWidth = MIN_AUTOFIT_WIDTH,
        ).apply {
            maxWidthProvider = { AyuIslandsSettings.getInstance().state.autoFitMaxWidth }
        }

    init {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    val state =
                        AyuIslandsSettings.getInstance().state
                    val widthMode = PanelWidthMode.fromString(state.projectPanelWidthMode)
                    val allFeaturesDisabled =
                        !state.hideProjectRootPath &&
                            !state.hideRootVcsAnnotations &&
                            !state.hideProjectViewHScrollbar
                    if (allFeaturesDisabled && widthMode == PanelWidthMode.DEFAULT) {
                        return
                    }
                    apply()
                }
            },
        )
    }

    fun apply() {
        if (!LicenseChecker.isLicensedOrGrace()) return
        applyScrollbar()
        applyRootDisplay()
        manageAutoFit()
    }

    private fun applyScrollbar() {
        val scrollPane = findProjectScrollPane() ?: return
        val shouldHide =
            AyuIslandsSettings.getInstance().state.hideProjectViewHScrollbar

        if (shouldHide) {
            if (originalScrollbarPolicy == null) {
                originalScrollbarPolicy =
                    scrollPane.horizontalScrollBarPolicy
            }
            scrollPane.horizontalScrollBarPolicy =
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        } else if (originalScrollbarPolicy != null) {
            scrollPane.horizontalScrollBarPolicy =
                originalScrollbarPolicy!!
            originalScrollbarPolicy = null
        }
    }

    private fun manageAutoFit() {
        val state = AyuIslandsSettings.getInstance().state
        when (PanelWidthMode.fromString(state.projectPanelWidthMode)) {
            PanelWidthMode.DEFAULT -> autoFitter.removeExpansionListener()
            PanelWidthMode.AUTO_FIT -> {
                autoFitter.installExpansionListener()
                autoFitter.applyAutoFitWidth(state.autoFitMaxWidth)
            }
            PanelWidthMode.FIXED -> {
                autoFitter.removeExpansionListener()
                autoFitter.applyFixedWidth(state.projectPanelFixedWidth)
            }
        }
    }

    private fun applyRootDisplay() {
        applyFilesystemPathVisibility()
        applyVcsAnnotationVisibility()
        ProjectView.getInstance(project).refresh()
    }

    private fun applyFilesystemPathVisibility() {
        val shouldHide =
            AyuIslandsSettings.getInstance().state.hideProjectRootPath
        val registryKey = Registry.get(SHOW_URL_KEY)
        if (shouldHide) {
            registryKey.setValue(false)
            registryKeyModified = true
        } else if (registryKeyModified) {
            registryKey.resetToDefault()
            registryKeyModified = false
        }
    }

    private fun applyVcsAnnotationVisibility() {
        val shouldHideVcs =
            AyuIslandsSettings.getInstance().state.hideRootVcsAnnotations
        val tree = findProjectTree() ?: return
        if (shouldHideVcs) {
            installRendererWrapper(tree)
            installRendererGuard(tree)
        } else {
            removeRendererGuard()
            unwrapRenderer(tree)
        }
    }

    private fun installRendererWrapper(tree: JTree) {
        val current = tree.cellRenderer
        if (current !is RootLocationHidingRenderer) {
            tree.cellRenderer =
                RootLocationHidingRenderer(current, project)
        }
    }

    private fun unwrapRenderer(tree: JTree) {
        val current = tree.cellRenderer
        if (current is RootLocationHidingRenderer) {
            tree.cellRenderer = current.delegate
        }
    }

    private fun installRendererGuard(tree: JTree) {
        if (trackedTree === tree && rendererListener != null) return

        removeRendererGuard()
        trackedTree = tree
        rendererListener =
            PropertyChangeListener { event ->
                if (event.propertyName == "cellRenderer") {
                    val newRenderer =
                        event.newValue as? TreeCellRenderer
                            ?: return@PropertyChangeListener
                    if (newRenderer !is RootLocationHidingRenderer &&
                        AyuIslandsSettings
                            .getInstance()
                            .state
                            .hideRootVcsAnnotations
                    ) {
                        tree.cellRenderer =
                            RootLocationHidingRenderer(
                                newRenderer,
                                project,
                            )
                    }
                }
            }
        tree.addPropertyChangeListener(
            "cellRenderer",
            rendererListener,
        )
    }

    private fun removeRendererGuard() {
        val tree = trackedTree ?: return
        val listener = rendererListener ?: return
        tree.removePropertyChangeListener(
            "cellRenderer",
            listener,
        )
        trackedTree = null
        rendererListener = null
    }

    private fun findProjectScrollPane(): JScrollPane? {
        val content = findProjectContent() ?: return null
        return findFirstOfType(
            content,
            JScrollPane::class.java,
        ) as? JScrollPane
    }

    private fun findProjectTree(): JTree? {
        val content = findProjectContent() ?: return null
        return findFirstOfType(
            content,
            JTree::class.java,
        ) as? JTree
    }

    private fun findProjectContent(): Component? {
        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow("Project")
                ?: return null
        return toolWindow
            .contentManager
            .contents
            .firstOrNull()
            ?.component
    }

    private fun findFirstOfType(
        component: Component,
        type: Class<*>,
    ): Component? {
        if (type.isInstance(component)) return component
        if (component is Container) {
            for (child in component.components) {
                val found = findFirstOfType(child, type)
                if (found != null) return found
            }
        }
        return null
    }

    override fun dispose() {
        removeRendererGuard()
        autoFitter.removeExpansionListener()
        val scrollPane = findProjectScrollPane()
        if (scrollPane != null && originalScrollbarPolicy != null) {
            scrollPane.horizontalScrollBarPolicy =
                originalScrollbarPolicy!!
            originalScrollbarPolicy = null
        }
        if (registryKeyModified) {
            Registry.get(SHOW_URL_KEY).resetToDefault()
            registryKeyModified = false
        }
        val tree = findProjectTree()
        if (tree != null) {
            unwrapRenderer(tree)
        }
    }

    companion object {
        private const val SHOW_URL_KEY =
            "project.tree.structure.show.url"
        const val MIN_AUTOFIT_WIDTH = 253

        fun getInstance(project: Project): ProjectViewScrollbarManager =
            project.getService(
                ProjectViewScrollbarManager::class.java,
            )
    }
}

/**
 * Strips VCS annotations (branch name, changed file count)
 * from the project root node. Rebuilds the component keeping
 * only the project name and filesystem path fragments.
 */
private class RootLocationHidingRenderer(
    val delegate: TreeCellRenderer,
    private val project: Project,
) : TreeCellRenderer {
    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val component =
            delegate.getTreeCellRendererComponent(
                tree,
                value,
                selected,
                expanded,
                leaf,
                row,
                hasFocus,
            )
        if (row == 0 && component is SimpleColoredComponent) {
            stripVcsFragments(component)
        }
        return component
    }

    private fun stripVcsFragments(component: SimpleColoredComponent) {
        val projectName = project.name
        val basePath = project.basePath
        val tildeBasePath =
            basePath?.replace(
                System.getProperty("user.home"),
                "~",
            )
        val kept =
            mutableListOf<Pair<String, SimpleTextAttributes>>()
        val iter = component.iterator()
        while (iter.hasNext()) {
            iter.next()
            val text = iter.fragment
            val trimmed = text.trim()
            if (isKeptFragment(trimmed, projectName, basePath, tildeBasePath)) {
                kept.add(text to iter.textAttributes)
            }
        }
        component.clear()
        for ((text, attrs) in kept) {
            component.append(text, attrs)
        }
    }

    private fun isKeptFragment(
        trimmed: String,
        projectName: String,
        basePath: String?,
        tildeBasePath: String?,
    ): Boolean {
        if (trimmed.isEmpty()) return false
        if (trimmed == projectName) return true
        if (basePath != null && trimmed.contains(basePath)) {
            return true
        }
        if (tildeBasePath != null &&
            trimmed.contains(tildeBasePath)
        ) {
            return true
        }
        return false
    }
}
