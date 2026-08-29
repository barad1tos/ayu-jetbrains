package dev.ayuislands.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import kotlinx.coroutines.CancellationException

/** Owns transient capability work for one settings session. */
internal class SyntaxCapabilityController(
    private val project: Project?,
    private val rendered: (SyntaxCapabilityState?) -> Unit,
    private val marketplace: LanguageSupportMarketplace = LanguageSupportMarketplace(),
) : Disposable {
    private var probe: SyntaxCapabilityProbe? = project?.let(::NativeCapabilityProbe)
    private var activeProbe: Disposable? = null
    private val session = Disposer.newDisposable("Ayu syntax capability session")
    private var model = SyntaxCapabilityModel()
    private var isDisposed = false

    fun selectLanguage(languageId: String) {
        if (probe == null) return
        val specification =
            checkNotNull(SyntaxLanguageRegistry.findByStorageId(languageId)) {
                "Unknown syntax capability language '$languageId'"
            }
        val key =
            SyntaxCapabilityKey(
                languageId = languageId,
                profileIds = specification.nativeProfiles.mapTo(linkedSetOf()) { it.id },
            )
        handle(SyntaxCapabilityEvent.SelectLanguage(key))
    }

    fun performRecoveryAction() {
        when (model.state) {
            is SyntaxCapabilityState.SupportUnavailable -> handle(SyntaxCapabilityEvent.OpenLanguageSupport)
            is SyntaxCapabilityState.TemporarilyUnavailable,
            is SyntaxCapabilityState.Incompatible,
            -> handle(SyntaxCapabilityEvent.Retry)
            is SyntaxCapabilityState.Confirmed -> handle(SyntaxCapabilityEvent.OpenHighlightingSettings)
            is SyntaxCapabilityState.Checking,
            null,
            -> Unit
        }
    }

    fun replaceProbeForTest(probe: SyntaxCapabilityProbe) {
        this.probe = probe
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        runCleanupSteps(
            ::closeModel,
            { Disposer.dispose(session) },
        )
    }

    private fun handle(event: SyntaxCapabilityEvent) {
        val transition = SyntaxCapabilityReducer.reduce(model, event)
        model = transition.model
        transition.effects.forEach(::execute)
    }

    private fun closeModel() {
        val transition = SyntaxCapabilityReducer.reduce(model, SyntaxCapabilityEvent.CloseSettings)
        model = transition.model
        runCleanupSteps(transition.effects.map { effect -> cleanupStep(effect) })
    }

    private fun cleanupStep(effect: SyntaxCapabilityEffect): () -> Unit = { execute(effect) }

    private fun execute(effect: SyntaxCapabilityEffect) {
        when (effect) {
            SyntaxCapabilityEffect.CancelProbe -> cancelProbe()
            is SyntaxCapabilityEffect.StartProbe -> startProbe(effect.languageId, effect.generation)
            SyntaxCapabilityEffect.Render -> rendered(model.state)
            is SyntaxCapabilityEffect.OpenLanguageSupport ->
                openLanguageSupport(effect.languageId)
            SyntaxCapabilityEffect.OpenHighlightingSettings -> openHighlightingSettings()
            SyntaxCapabilityEffect.ClearRenderer -> rendered(null)
        }
    }

    private fun startProbe(
        languageId: String,
        generation: Long,
    ) {
        val currentProbe = probe ?: return
        val specification =
            checkNotNull(SyntaxLanguageRegistry.findByStorageId(languageId)) {
                "Unknown syntax capability language '$languageId'"
            }
        val task = Disposer.newDisposable("Ayu syntax capability probe: $languageId")
        Disposer.register(session, task)
        activeProbe = task
        try {
            currentProbe.start(specification, generation, task) { result ->
                if (activeProbe === task) activeProbe = null
                handle(result.toEvent())
            }
        } catch (failure: RuntimeException) {
            finishFailedProbe(languageId, generation, task, failure)
        }
    }

    private fun finishFailedProbe(
        languageId: String,
        generation: Long,
        task: Disposable,
        failure: RuntimeException,
    ) {
        if (activeProbe === task) activeProbe = null
        val terminalFailure = disposeAfterFailure(task, failure)
        if (terminalFailure.isCancellation()) throw terminalFailure
        LOG.warn("Syntax capability probe failed for $languageId", terminalFailure)
        val result =
            SyntaxProbeResult.Deferred(
                languageId = languageId,
                generation = generation,
                reason = probeFailureMessage(languageId),
            )
        handle(result.toEvent())
    }

    private fun disposeAfterFailure(
        task: Disposable,
        failure: RuntimeException,
    ): RuntimeException =
        try {
            Disposer.dispose(task)
            failure
        } catch (cleanupFailure: RuntimeException) {
            when {
                failure.isCancellation() -> failure.apply { addSuppressed(cleanupFailure) }
                cleanupFailure.isCancellation() -> cleanupFailure.apply { addSuppressed(failure) }
                else -> failure.apply { addSuppressed(cleanupFailure) }
            }
        }

    private fun cancelProbe() {
        val task = activeProbe ?: return
        activeProbe = null
        Disposer.dispose(task)
    }

    private fun openLanguageSupport(languageId: String) {
        marketplace.open(languageId)
    }

    private fun openHighlightingSettings() {
        val currentProject = project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(currentProject, HIGHLIGHTING_SETTINGS_ID)
        handle(SyntaxCapabilityEvent.RecheckHighlighting)
    }

    private companion object {
        private val LOG = Logger.getInstance(SyntaxCapabilityController::class.java)
        private const val HIGHLIGHTING_SETTINGS_ID = "preferences.editor.colorScheme"
    }
}

private fun RuntimeException.isCancellation(): Boolean =
    this is ProcessCanceledException || this is CancellationException
