package dev.ayuislands.commitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
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
            minWidth = MIN_AUTOFIT_WIDTH,
        ).apply {
            maxWidthProvider = { AyuIslandsSettings.getInstance().state.autoFitCommitMaxWidth }
        }

    init {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
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
        when (PanelWidthMode.fromString(state.commitPanelWidthMode)) {
            PanelWidthMode.DEFAULT -> autoFitter.removeExpansionListener()
            PanelWidthMode.AUTO_FIT -> {
                autoFitter.installExpansionListener()
                autoFitter.applyAutoFitWidth(state.autoFitCommitMaxWidth)
            }
            PanelWidthMode.FIXED -> {
                autoFitter.removeExpansionListener()
                autoFitter.applyFixedWidth(state.commitPanelFixedWidth)
            }
        }
    }

    override fun dispose() {
        autoFitter.removeExpansionListener()
    }

    companion object {
        const val MIN_AUTOFIT_WIDTH = 269

        fun getInstance(project: Project): CommitPanelAutoFitManager =
            project.getService(
                CommitPanelAutoFitManager::class.java,
            )
    }
}
