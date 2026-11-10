package dev.ayuislands.studio

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.rethrowIfCancelled
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontPreset
import dev.ayuislands.licensing.LicenseRecheckScheduler
import dev.ayuislands.licensing.LicenseRecheckSlot
import dev.ayuislands.settings.SettingsOpenContext
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.FontJobStatus
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioThemeChange
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.persistence.StudioLegacyView
import dev.ayuislands.studio.session.ImmediateExecutionResult
import dev.ayuislands.studio.session.StudioQuickCommand
import dev.ayuislands.studio.session.StudioTargets
import dev.ayuislands.studio.ui.StudioIdeSettings
import dev.ayuislands.studio.ui.StudioSettingsAccess
import dev.ayuislands.studio.ui.settingsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

/** Owns the production graph for the injected application/plugin coroutine lifetime. */
@Service(Service.Level.APP)
internal class StudioService(
    private val scope: CoroutineScope,
) {
    private val opening = Mutex()

    @Volatile
    private var retained: StudioApplicationGraph? = null
    private var isClosed = false

    /** Background readers see only the accepted runtime owner, never an in-flight focus or draft. */
    val acceptedView: StudioLegacyView?
        get() {
            val document = retained?.sessions?.document ?: return null
            val owner = document.runtime.rotation?.owner ?: return null
            return StudioLegacyProjection.project(document, owner.target)
        }

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    opening.withLock {
                        isClosed = true
                        retained?.close()
                    }
                }
            }
        }
    }

    /** Startup and UI use the same lazy authority; merely constructing the service performs no disk I/O. */
    suspend fun open(): StudioApplicationGraph =
        opening.withLock {
            currentCoroutineContext().ensureActive()
            check(!isClosed) { "Ayu Islands is shutting down" }
            retained ?: StudioApplicationGraph
                .open(
                    PathManager.getConfigDir().resolve("ayu-islands/studio"),
                    Path.of(PathManager.getOptionsPath()),
                    scope,
                ).also { retained = it }
        }

    fun start(): Job = nativeChanged(StudioNativeChange.Appearance)

    fun focusChanged(): Job = launchReady { graph -> graph?.runtimeBinding?.windowActivated()?.join() }

    fun refreshEntitlement(): Job =
        launchReady { graph ->
            val nextCheck =
                try {
                    graph?.refreshEntitlement() ?: if (graph == null) LicenseRecheckScheduler.RETRY_DELAY_MS else null
                } catch (failure: RuntimeException) {
                    failure.rethrowIfCancelled()
                    LOG.error("Ayu license: failed to refresh accepted entitlement; retrying", failure)
                    LicenseRecheckScheduler.RETRY_DELAY_MS
                }
            nextCheck?.let { delay ->
                LicenseRecheckScheduler.getInstance().schedule(
                    LicenseRecheckSlot.TRANSITION,
                    delay,
                ) { refreshEntitlement() }
            }
        }

    fun updateUi(
        name: String,
        value: RawValue,
        completed: (AcceptanceResult) -> Unit = {},
    ): Job =
        metadata(
            StudioMetadataCommand.UiChoice(
                FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, name)),
                FieldValue.Present(value),
            ),
            completed,
        )

    fun metadata(
        command: StudioMetadataCommand,
        completed: (AcceptanceResult) -> Unit = {},
    ): Job =
        launchReady { graph ->
            val result =
                graph?.submitMetadata(command)
                    ?: AcceptanceResult.Blocked(StudioList(listOf(AcceptanceBlock.READ_ONLY)))
            if (result !is AcceptanceResult.Accepted && result != AcceptanceResult.NoChanges) {
                StudioNotifications.metadata { metadata(command, completed) }
            }
            completed(result)
        }

    /** Captures a project incarnation on EDT; missing authority never becomes a default Global snapshot. */
    fun capture(project: Project?): TargetObservation? {
        val graph = retained ?: return null
        val document = graph.sessions?.document ?: return null
        return graph.projects.capture(project)?.takeIf { StudioTargets.current(document, it.target) != null }
    }

    fun view(target: TargetObservation): StudioLegacyView? =
        document(target)?.let {
            StudioLegacyProjection.project(it, target.target)
        }

    fun document(target: TargetObservation): StudioDocument? {
        val graph = retained ?: return null
        val document = graph.sessions?.document ?: return null
        val observed = graph.projects.observe(target.target)
        if (observed.generation != target.generation || observed.presence == TargetPresence.UNAVAILABLE) return null
        return document
    }

    fun submitQuick(
        target: TargetObservation,
        command: StudioQuickCommand,
        completed: (ImmediateExecutionResult) -> Unit,
    ): Job =
        launchReady { graph ->
            val result =
                graph?.submitQuick(target, command) ?: ImmediateExecutionResult.Complete(
                    AcceptanceResult.Blocked(StudioList(listOf(AcceptanceBlock.READ_ONLY))),
                )
            completed(result)
        }

    fun retryImmediate(
        operationId: String,
        completed: (ImmediateExecutionResult) -> Unit,
    ): Job =
        launchReady { graph ->
            val result =
                graph?.immediate?.retry(operationId) ?: ImmediateExecutionResult.Complete(
                    AcceptanceResult.Blocked(StudioList(listOf(AcceptanceBlock.READ_ONLY))),
                )
            completed(result)
        }

    fun submitQuick(
        target: TargetObservation,
        command: StudioQuickCommand,
        baseline: StudioDocument,
        completed: (ImmediateExecutionResult) -> Unit,
    ): Job =
        launchReady { graph ->
            val result =
                graph?.submitQuick(target, command, baseline) ?: ImmediateExecutionResult.Complete(
                    AcceptanceResult.Blocked(StudioList(listOf(AcceptanceBlock.READ_ONLY))),
                )
            completed(result)
        }

    /** Installs a resource independently of a view; the originating gesture separately owns appearance intent. */
    fun installFont(
        project: Project?,
        preset: FontPreset,
        completed: (FontJobStatus) -> Unit,
    ): Job =
        launchReady { graph ->
            val entry = FontCatalog.forPreset(preset)
            if (graph == null || entry == null) {
                completed(FontJobStatus.FAILED)
            } else {
                val operationId = UUID.randomUUID().toString()
                val job = FontJob(OperationStamp(operationId, 0, operationId), entry.familyName, FontAction.INSTALL)
                graph.resources.start(project, job, compactConsent = false, completed)
            }
        }

    fun nativeChanged(change: StudioNativeChange): Job =
        launchReady { graph ->
            val binding = graph?.runtimeBinding ?: return@launchReady
            when (change) {
                StudioNativeChange.Appearance -> binding.appearanceChanged()
                StudioNativeChange.AccentContext -> binding.accentContextChanged()
                is StudioNativeChange.Theme ->
                    when (val selection = change.selection) {
                        is StudioThemeChange.Explicit -> binding.selectTheme(selection.name)
                        StudioThemeChange.FollowSystem -> binding.systemAppearanceChanged()
                    }
            }.join()
        }

    /** Project-close observation must happen synchronously before the native project is disposed. */
    fun projectClosing(project: Project) {
        val graph = retained ?: return
        if (graph.sessions != null) graph.runtimeBinding.projectClosing(project)
    }

    fun projectClosed(): Job =
        launchReady { graph ->
            val binding = graph?.runtimeBinding ?: return@launchReady
            binding.focusChanged().join()
            binding.appearanceChanged().join()
        }

    private fun launchReady(action: suspend (StudioApplicationGraph?) -> Unit): Job =
        scope.launch(Dispatchers.EDT + ModalityState.defaultModalityState().asContextElement()) {
            val graph =
                try {
                    open().takeIf { it.application.start() is StudioApplicationState.Ready }
                } catch (_: IOException) {
                    null
                }
            if (graph == null) StudioNotifications.storage()
            action(graph)
        }

    fun settings(): StudioIdeSettings {
        var provider: (suspend (SettingsOpenContext) -> StudioSettingsAccess)? = null
        return StudioIdeSettings(provider = { captured ->
            try {
                val ready = provider ?: open().settingsProvider().also { provider = it }
                ready(captured)
            } catch (_: IOException) {
                StudioSettingsAccess.Unavailable(
                    "Ayu Islands could not open its saved settings. " +
                        "Check that the IDE settings folder is writable, then retry.",
                )
            }
        })
    }

    companion object {
        private val LOG = logger<StudioService>()

        fun getInstance(): StudioService = service()
    }
}
