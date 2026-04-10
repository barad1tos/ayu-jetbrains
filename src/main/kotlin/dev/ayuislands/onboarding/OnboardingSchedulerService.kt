package dev.ayuislands.onboarding

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/**
 * Project-scoped coroutine host for onboarding wizard scheduling.
 *
 * Replaces the prior `javax.swing.Timer` approach which dispatched on EDT and
 * blocked the UI when `FileEditorManager.openFile` synchronously waited for
 * editor composite construction (`waitBlockingAndPumpEdt`).
 */
@Service(Service.Level.PROJECT)
internal class OnboardingSchedulerService(
    private val project: Project,
    private val cs: CoroutineScope,
) {
    fun scope(): CoroutineScope = cs

    fun project(): Project = project

    companion object {
        fun getInstance(project: Project): OnboardingSchedulerService = project.service()
    }
}
