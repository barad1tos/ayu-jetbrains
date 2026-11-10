package dev.ayuislands.settings.mappings

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.color.ProjectIconAccentExtractor
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.session.ImmediateExecutionResult
import dev.ayuislands.studio.session.StudioQuickCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Extracts an icon off EDT, then submits a guarded project pin through the shared acceptance lane. */
internal object ProjectIconAccentAssigner {
    private val log = logger<ProjectIconAccentAssigner>()

    suspend fun assignIfAbsent(project: Project): Boolean = assignIfAbsent(project, Dispatchers.EDT)

    @TestOnly
    internal suspend fun assignIfAbsent(
        project: Project,
        uiContext: CoroutineContext,
    ): Boolean {
        val service = StudioService.getInstance()
        val target =
            withContext(uiContext) {
                if (project.isDisposed) return@withContext null
                service.capture(project)?.takeIf { canAutoAssign(service, it) }
            } ?: return false
        val key = (target.target as? StudioTarget.Project)?.key?.value ?: return false
        val candidate =
            withContext(Dispatchers.IO) {
                val icon = ProjectIconAccentExtractor.projectIconFile(key) ?: return@withContext null
                ProjectIconAccentExtractor.extract(icon)?.let { IconAccentCandidate(it, icon.name) }
            } ?: return false
        return withContext(uiContext) {
            if (project.isDisposed || !canAutoAssign(service, target)) return@withContext false
            val result = CompletableDeferred<ImmediateExecutionResult>()
            service.submitQuick(
                target,
                StudioQuickCommand.PinIfAbsent(
                    candidate.accent.value,
                    project.name.ifBlank { File(key).name },
                ),
            ) { result.complete(it) }
            val completed = result.await() as? ImmediateExecutionResult.Complete ?: return@withContext false
            if (completed.acceptance !is AcceptanceResult.Accepted) return@withContext false
            log.info("Project icon accent: assigned ${candidate.accent.value} to '$key' from ${candidate.iconName}")
            true
        }
    }

    private fun canAutoAssign(
        service: StudioService,
        target: TargetObservation,
    ): Boolean {
        val key = (target.target as? StudioTarget.Project)?.key?.value ?: return false
        val view = service.view(target) ?: return false
        return view.settings.projectIconAccentEnabled &&
            LicenseChecker.isLicensedOrGrace() &&
            key !in view.mappings.projectAccents
    }

    private data class IconAccentCandidate(
        val accent: AccentHex,
        val iconName: String,
    )
}
