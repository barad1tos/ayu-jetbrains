package dev.ayuislands.commitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.toolwindow.AutoFitCalculator
import dev.ayuislands.toolwindow.ToolWindowAutoFitter

/** Per-project service that auto-fits the Commit tool window width to its tree content. */
@Service(Service.Level.PROJECT)
class CommitPanelAutoFitManager(
    private val project: Project,
) : Disposable {
    private val autoFitter =
        ToolWindowAutoFitter(
            project = project,
            toolWindowId = "Commit",
            minWidth = AutoFitCalculator.MIN_COMMIT_AUTOFIT_WIDTH,
        ).apply {
            maxWidthProvider = { AyuIslandsSettings.getInstance().state.autoFitCommitMaxWidth }
            minWidthProvider = { AyuIslandsSettings.getInstance().state.commitPanelAutoFitMinWidth }
        }

    init {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    if (changeType == ToolWindowManagerListener.ToolWindowManagerEventType.MovedOrResized) return
                    // ShowToolWindow fires every time the Commit tool window toggles visible.
                    // Content is unchanged; re-measuring the JTree on show oscillates its width
                    // because row bounds reflect cell-renderer extension under focus. Legitimate
                    // re-applies still arrive via mode-switch in [apply] and expansion listener.
                    if (changeType == ToolWindowManagerListener.ToolWindowManagerEventType.ShowToolWindow) return
                    val tw = toolWindowManager.getToolWindow("Commit") ?: return
                    if (!tw.isVisible) return
                    val mode =
                        PanelWidthMode.fromString(
                            AyuIslandsSettings.getInstance().state.commitPanelWidthMode,
                        )
                    if (mode == PanelWidthMode.DEFAULT) return
                    apply()
                }
            },
        )
    }

    fun apply() {
        if (!LicenseChecker.isLicensedOrGrace()) return
        val state = AyuIslandsSettings.getInstance().state
        autoFitter.applyWidthMode(
            PanelWidthMode.fromString(state.commitPanelWidthMode),
            state.autoFitCommitMaxWidth,
            state.commitPanelFixedWidth,
        )
    }

    override fun dispose() {
        autoFitter.removeExpansionListener()
    }

    companion object {
        fun getInstance(project: Project): CommitPanelAutoFitManager =
            project.getService(
                CommitPanelAutoFitManager::class.java,
            )
    }
}
