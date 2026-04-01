package dev.ayuislands.projectview

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.toolwindow.AutoFitCalculator
import dev.ayuislands.toolwindow.ToolWindowAutoFitter
import java.awt.Component
import java.beans.PropertyChangeListener
import java.util.MissingResourceException
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.tree.TreeCellRenderer

/** Per-project service that manages Project tool window tweaks (scrollbar, root path). */
@Service(Service.Level.PROJECT)
class ProjectViewScrollbarManager(
    private val project: Project,
) : Disposable {
    private var originalScrollbarPolicy: Int? = null
    private var registryKeyModified = false
    private var trackedTree: JTree? = null
    private var lastAppliedHidePath: Boolean? = null
    private var rendererListener: PropertyChangeListener? = null
    private val autoFitter =
        ToolWindowAutoFitter(
            project = project,
            toolWindowId = "Project",
            minWidth = AutoFitCalculator.MIN_PROJECT_AUTOFIT_WIDTH,
        ).apply {
            maxWidthProvider = { AyuIslandsSettings.getInstance().state.autoFitMaxWidth }
            minWidthProvider = { AyuIslandsSettings.getInstance().state.projectPanelAutoFitMinWidth }
        }

    init {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    val tw = toolWindowManager.getToolWindow("Project") ?: return
                    if (!tw.isVisible) return
                    val state =
                        AyuIslandsSettings.getInstance().state
                    val widthMode = PanelWidthMode.fromString(state.projectPanelWidthMode)
                    val allFeaturesDisabled =
                        !state.hideProjectRootPath &&
                            !state.hideProjectViewHScrollbar
                    if (allFeaturesDisabled && widthMode == PanelWidthMode.DEFAULT) {
                        return
                    }
                    apply()
                }
            },
        )

        // Initial apply: a tool window may already be open when this service is created.
        SwingUtilities.invokeLater {
            if (!project.isDisposed) apply()
        }
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
        val hidePath = AyuIslandsSettings.getInstance().state.hideProjectRootPath
        if (hidePath == lastAppliedHidePath) return
        lastAppliedHidePath = hidePath

        // ProjectViewImpl.isShowURL() reads directly from this Registry key.
        // Only resetToDefault when WE changed it — don't override the user's choice.
        val registryKey =
            try {
                Registry.get(SHOW_URL_KEY)
            } catch (_: MissingResourceException) {
                LOG.warn(
                    "Registry key '$SHOW_URL_KEY' not " +
                        "found — the IDE may have removed it",
                )
                return
            }
        if (hidePath) {
            registryKey.setValue(false)
            registryKeyModified = true
        } else if (registryKeyModified) {
            registryKey.resetToDefault()
            registryKeyModified = false
        }

        val tree = findProjectTree() ?: return

        if (hidePath) {
            installRendererWrapper(tree)
            installRendererGuard(tree)
        } else {
            removeRendererGuard()
            unwrapRenderer(tree)
        }

        // Force full tree rebuild to pick up Registry change
        ProjectView.getInstance(project).currentProjectViewPane?.updateFromRoot(true)
    }

    private fun installRendererWrapper(tree: JTree) {
        val current = tree.cellRenderer
        if (current is RootFilteringRenderer) return
        tree.cellRenderer = RootFilteringRenderer(current, project)
    }

    private fun unwrapRenderer(tree: JTree) {
        val current = tree.cellRenderer
        if (current is RootFilteringRenderer) {
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
                    if (newRenderer !is RootFilteringRenderer &&
                        AyuIslandsSettings.getInstance().state.hideProjectRootPath
                    ) {
                        tree.cellRenderer =
                            RootFilteringRenderer(newRenderer, project)
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
        return AutoFitCalculator.findFirstOfType(
            content,
            JScrollPane::class.java,
        ) as? JScrollPane
    }

    private fun findProjectTree(): JTree? {
        val content = findProjectContent() ?: return null
        return AutoFitCalculator.findFirstOfType(
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
            try {
                Registry.get(SHOW_URL_KEY).resetToDefault()
            } catch (_: MissingResourceException) {
                LOG.warn(
                    "Registry key '$SHOW_URL_KEY' not " +
                        "found during dispose",
                )
            }
            registryKeyModified = false
        }
        val tree = findProjectTree()
        if (tree != null) {
            unwrapRenderer(tree)
        }
    }

    companion object {
        private val LOG = logger<ProjectViewScrollbarManager>()
        private const val SHOW_URL_KEY = "project.tree.structure.show.url"

        fun getInstance(project: Project): ProjectViewScrollbarManager =
            project.getService(
                ProjectViewScrollbarManager::class.java,
            )
    }
}

/**
 * Filters root node path fragments.
 * Reads settings LIVE on every render — no state caching needed.
 */
private class RootFilteringRenderer(
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
            filterRootFragments(component)
        }
        return component
    }

    private fun filterRootFragments(component: SimpleColoredComponent) {
        if (!AyuIslandsSettings.getInstance().state.hideProjectRootPath) return

        val basePath = project.basePath
        val tildeBasePath =
            basePath?.replace(System.getProperty("user.home"), "~")
        val context =
            RootNodeContext(
                projectName = project.name,
                basePath = basePath,
                tildeBasePath = tildeBasePath,
            )
        val kept =
            mutableListOf<Pair<String, SimpleTextAttributes>>()
        val iter = component.iterator()
        while (iter.hasNext()) {
            iter.next()
            val text = iter.fragment
            val trimmed = text.trim()
            if (!RootFragmentFilter.isPathFragment(trimmed, context)) {
                kept.add(text to iter.textAttributes)
            }
        }

        val savedIcon = component.icon
        component.clear()
        component.icon = savedIcon
        for ((text, attrs) in kept) {
            component.append(text, attrs)
        }
    }
}
