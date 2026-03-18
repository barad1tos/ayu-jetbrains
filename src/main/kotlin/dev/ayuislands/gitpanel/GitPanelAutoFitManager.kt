package dev.ayuislands.gitpanel

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

/** Per-project service that manages Git tool window width (auto-fit or fixed). */
@Service(Service.Level.PROJECT)
class GitPanelAutoFitManager(
    private val project: Project,
) : Disposable {
    private val autoFitter =
        ToolWindowAutoFitter(
            project = project,
            toolWindowId = "Git",
            minWidth = AutoFitCalculator.MIN_GIT_AUTOFIT_WIDTH,
        ).apply {
            maxWidthProvider = { AyuIslandsSettings.getInstance().state.gitPanelAutoFitMaxWidth }
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
                            AyuIslandsSettings.getInstance().state.gitPanelWidthMode,
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
            PanelWidthMode.fromString(state.gitPanelWidthMode),
            state.gitPanelAutoFitMaxWidth,
            state.gitPanelFixedWidth,
        )
    }

    override fun dispose() {
        autoFitter.removeExpansionListener()
    }

    companion object {
        fun getInstance(project: Project): GitPanelAutoFitManager =
            project.getService(
                GitPanelAutoFitManager::class.java,
            )
    }
}
