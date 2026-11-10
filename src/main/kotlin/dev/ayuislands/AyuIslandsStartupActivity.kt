package dev.ayuislands

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageRootChangeListener
import dev.ayuislands.accent.ScanCompletionAccentRefresher
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.rethrowIfCancelled
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.FocusedEditorContext
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import dev.ayuislands.settings.mappings.ProjectIconAccentAssigner
import dev.ayuislands.studio.StudioApplicationGraph
import dev.ayuislands.studio.StudioApplicationState
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.AccentMappingsView
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.session.StudioFonts
import dev.ayuislands.syntax.SyntaxIntensityMigrationNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Registers project lifetime owners, then submits startup through the single accepted Studio authority. */
internal class AyuIslandsStartupActivity(
    private val initializeFocusedEditorContext: suspend () -> Unit = { FocusedEditorContext.getInstance() },
    private val openStudio: suspend () -> StudioApplicationGraph = { StudioService.getInstance().open() },
) : ProjectActivity {
    override suspend fun execute(project: Project) {
        initializeFocusedEditorContext()
        if (project.isDisposed) return
        withContext(Dispatchers.EDT) { registerProjectOwners(project) }
        val graph = openStudio()
        if (graph.application.start() !is StudioApplicationState.Ready) return
        val initialized = graph.initializeProject(project)
        if (initialized !is AcceptanceResult.Accepted && initialized != AcceptanceResult.NoChanges) return
        if (project.isDisposed) return
        runStep("project-icon-accent") { ProjectIconAccentAssigner.assignIfAbsent(project) }
        seedFonts(graph)
        val view =
            withContext(Dispatchers.EDT) {
                val target = graph.projects.capture(project)?.target ?: return@withContext null
                StudioLegacyProjection.project(requireNotNull(graph.sessions).document, target)
            } ?: return
        val isReturningUser = view.settings.lastSeenVersion != null
        val themeName = AyuVariant.currentThemeName()
        LOG.info("Ayu Islands loaded — active theme: $themeName, project: ${project.name}")
        if (shouldWarmLanguageDetector(view.mappings)) ProjectLanguageDetector.dominant(project)
        val delayMs = StartupLicenseHandler.computeAdaptiveDelay()
        StartupLicenseHandler.start(project, graph, isReturningUser, delayMs)
        if (AyuVariant.fromThemeName(themeName) == null) return
        val conflicts = ConflictRegistry.detectConflicts()
        if (conflicts.isNotEmpty()) {
            LOG.info("Ayu Islands detected third-party plugins: ${conflicts.joinToString { it.pluginDisplayName }}")
        }
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            if (view.settings.followSystemAppearance) graph.runtimeBinding.systemAppearanceChanged().join()
            runStep("update-notification") { UpdateNotifier.showIfUpdated(project) }
            runStep("syntax-migration-notification") { SyntaxIntensityMigrationNotifier.maybeFire(project) }
        }
    }

    private fun registerProjectOwners(project: Project) {
        if (project.isDisposed || ApplicationManager.getApplication().isDisposed) return
        ScanCompletionAccentRefresher.getInstance(project)
        ProjectLanguageRootChangeListener.getInstance(project)
        ProjectAccentSwapService.getInstance().install()
        EditorScrollbarManager.getInstance(project)
        ProjectViewScrollbarManager.getInstance(project)
        CommitPanelAutoFitManager.getInstance(project)
        GitPanelAutoFitManager.getInstance(project)
    }

    private suspend fun seedFonts(graph: StudioApplicationGraph) {
        val document = graph.sessions?.document ?: return
        val settings = requireNotNull(StudioLegacyProjection.project(document, StudioTarget.Global)).settings
        if (settings.installedFontsSeeded) return
        val families =
            try {
                StudioFonts.availableFamilies()
            } catch (failure: RuntimeException) {
                failure.rethrowIfCancelled()
                LOG.warn("Unable to seed installed fonts from the native registry", failure)
                null
            }
        graph.submitMetadata(StudioMetadataCommand.SeedFonts(families?.let(::StudioList)))
    }

    @Suppress("TooGenericExceptionCaught") // VM error rethrown; generic Error logged-and-continue
    private inline fun runStep(name: String, block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (failure: RuntimeException) {
            failure.rethrowIfCancelled()
            LOG.error("Ayu startup step '$name' failed", failure)
            false
        } catch (failure: VirtualMachineError) {
            LOG.error("Ayu startup step '$name' failed with VM error", failure)
            throw failure
        } catch (failure: Error) {
            LOG.error("Ayu startup step '$name' failed with Error", failure)
            false
        }

    @org.jetbrains.annotations.TestOnly
    internal fun runStepForTest(
        name: String,
        block: () -> Unit,
    ): Boolean = runStep(name, block)

    internal fun shouldWarmLanguageDetector(state: AccentMappingsView): Boolean =
        state.languageAccents.isNotEmpty() || !state.languageFallbackAccent.isNullOrBlank()

    private companion object {
        val LOG = logger<AyuIslandsStartupActivity>()
    }
}
