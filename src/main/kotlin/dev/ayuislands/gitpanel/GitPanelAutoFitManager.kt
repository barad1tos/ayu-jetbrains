package dev.ayuislands.gitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
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
            minWidth = MIN_AUTOFIT_WIDTH,
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
        when (PanelWidthMode.fromString(state.gitPanelWidthMode)) {
            PanelWidthMode.DEFAULT -> autoFitter.removeExpansionListener()
            PanelWidthMode.AUTO_FIT -> {
                autoFitter.installExpansionListener()
                autoFitter.applyAutoFitWidth(state.gitPanelAutoFitMaxWidth)
            }
            PanelWidthMode.FIXED -> {
                autoFitter.removeExpansionListener()
                autoFitter.applyFixedWidth(state.gitPanelFixedWidth)
            }
        }
    }

    override fun dispose() {
        autoFitter.removeExpansionListener()
    }

    companion object {
        const val MIN_AUTOFIT_WIDTH = 200

        fun getInstance(project: Project) = project.getService(GitPanelAutoFitManager::class.java)
    }
}
