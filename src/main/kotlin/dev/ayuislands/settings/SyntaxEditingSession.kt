package dev.ayuislands.settings

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxTransactionResult
import javax.swing.Timer
import kotlin.coroutines.cancellation.CancellationException

internal enum class SyntaxSessionIntent {
    PREVIEW,
    COMMIT,
    RESTORE_AND_STAY,
    RESTORE_AND_CLOSE,
}

internal sealed interface SyntaxSessionState {
    data class Synced(
        val checkpoint: SyntaxPresetConfig,
    ) : SyntaxSessionState

    data class Pending(
        val checkpoint: SyntaxPresetConfig,
        val pending: SyntaxPresetConfig,
        val lastApplied: SyntaxPresetConfig,
    ) : SyntaxSessionState

    data class Applying(
        val checkpoint: SyntaxPresetConfig,
        val pending: SyntaxPresetConfig,
        val lastApplied: SyntaxPresetConfig,
        val intent: SyntaxSessionIntent,
    ) : SyntaxSessionState

    data class Live(
        val checkpoint: SyntaxPresetConfig,
        val pending: SyntaxPresetConfig,
    ) : SyntaxSessionState

    data class Failed(
        val checkpoint: SyntaxPresetConfig,
        val pending: SyntaxPresetConfig,
        val lastApplied: SyntaxPresetConfig,
        val failure: RuntimeException,
    ) : SyntaxSessionState

    data object Closed : SyntaxSessionState
}

internal sealed interface SyntaxSessionEvent {
    sealed interface Edit : SyntaxSessionEvent

    sealed interface Runtime : SyntaxSessionEvent

    sealed interface Commit : SyntaxSessionEvent

    sealed interface Restore : SyntaxSessionEvent

    sealed interface Environment : SyntaxSessionEvent

    data class EditDiscrete(
        val pending: SyntaxPresetConfig,
    ) : Edit

    data class EditSlider(
        val pending: SyntaxPresetConfig,
    ) : Edit

    data class Stage(
        val pending: SyntaxPresetConfig,
    ) : Edit

    data object DebounceElapsed : Edit

    data object SliderReleased : Edit

    data object RuntimeSucceeded : Runtime

    data class RuntimeFailed(
        val failure: RuntimeException,
    ) : Runtime

    data object ApplyRequested : Commit

    data object PersistenceSucceeded : Commit

    data class PersistenceFailed(
        val failure: RuntimeException,
    ) : Commit

    data object FrameworkResetRequested : Restore

    data object CancelRequested : Restore

    data object RestoreSucceeded : Restore

    data class RestoreFailed(
        val failure: RuntimeException,
    ) : Restore

    data class KeyRelinquished(
        val keyId: String,
    ) : Environment

    data object ActiveAyuSchemeChanged : Environment

    data object ForeignSchemeActivated : Environment
}

internal sealed interface SyntaxSessionEffect {
    data object RestartDebounce : SyntaxSessionEffect

    data object StopDebounce : SyntaxSessionEffect

    data class ApplyRuntime(
        val config: SyntaxPresetConfig,
        val intent: SyntaxSessionIntent,
    ) : SyntaxSessionEffect

    data class Persist(
        val config: SyntaxPresetConfig,
    ) : SyntaxSessionEffect

    data class Restore(
        val config: SyntaxPresetConfig,
    ) : SyntaxSessionEffect

    data class RecordRelinquishment(
        val keyId: String,
    ) : SyntaxSessionEffect

    data object ShowForeignScheme : SyntaxSessionEffect

    data object Close : SyntaxSessionEffect
}

internal data class SyntaxSessionTransition(
    val state: SyntaxSessionState,
    val effects: List<SyntaxSessionEffect> = emptyList(),
)

internal interface SyntaxEditingRuntime {
    fun preview(config: SyntaxPresetConfig): SyntaxTransactionResult

    fun materialize(config: SyntaxPresetConfig): SyntaxTransactionResult

    fun restore(config: SyntaxPresetConfig): SyntaxTransactionResult

    fun advance()

    fun recordRelinquishment(keyId: String) = Unit

    fun showForeignScheme() = Unit
}

internal interface SyntaxDebounce {
    fun restart()

    fun stop()

    fun dispose()
}

internal sealed interface SyntaxCommitResult {
    data object Applied : SyntaxCommitResult

    data class Failed(
        val failure: RuntimeException,
    ) : SyntaxCommitResult
}

internal sealed interface SyntaxRestoreResult {
    data object Restored : SyntaxRestoreResult

    data class Failed(
        val failure: RuntimeException,
    ) : SyntaxRestoreResult
}

internal class SyntaxEditingSession(
    initialCheckpoint: SyntaxPresetConfig,
    private val runtime: SyntaxEditingRuntime,
    private val persist: (SyntaxPresetConfig) -> Unit,
    private val onRuntimeApplied: (SyntaxPresetConfig) -> Unit = {},
    private val onRuntimeFailed: (RuntimeException) -> Unit = {},
    debounceFactory: ((() -> Unit) -> SyntaxDebounce) = ::SwingSyntaxDebounce,
) {
    private val log = logger<SyntaxEditingSession>()
    private var state: SyntaxSessionState = SyntaxSessionState.Synced(initialCheckpoint)
    private var restoreFailure: RuntimeException? = null
    private val debounce = debounceFactory { dispatch(SyntaxSessionEvent.DebounceElapsed) }

    fun editDiscrete(config: SyntaxPresetConfig) {
        requireOpen()
        dispatch(SyntaxSessionEvent.EditDiscrete(config))
    }

    fun editSlider(config: SyntaxPresetConfig) {
        requireOpen()
        dispatch(SyntaxSessionEvent.EditSlider(config))
    }

    fun sliderReleased() {
        requireOpen()
        dispatch(SyntaxSessionEvent.SliderReleased)
    }

    fun activeAyuSchemeChanged() {
        dispatch(SyntaxSessionEvent.ActiveAyuSchemeChanged)
    }

    fun foreignSchemeActivated() {
        dispatch(SyntaxSessionEvent.ForeignSchemeActivated)
    }

    fun apply(config: SyntaxPresetConfig = pendingConfig()): SyntaxCommitResult {
        requireOpen()
        dispatch(SyntaxSessionEvent.Stage(config))
        dispatch(SyntaxSessionEvent.ApplyRequested)
        val failed = state as? SyntaxSessionState.Failed
        return if (failed == null) SyntaxCommitResult.Applied else SyntaxCommitResult.Failed(failed.failure)
    }

    fun reset(): SyntaxRestoreResult {
        requireOpen()
        restoreFailure = null
        dispatch(SyntaxSessionEvent.FrameworkResetRequested)
        return restoreResult()
    }

    fun cancel(): SyntaxRestoreResult {
        restoreFailure = null
        dispatch(SyntaxSessionEvent.CancelRequested)
        return restoreResult()
    }

    fun isModified(): Boolean {
        val data = state.data() ?: return false
        return data.pending != data.checkpoint
    }

    fun pendingConfig(): SyntaxPresetConfig =
        checkNotNull(state.data()) { "Closed syntax session has no pending configuration" }.pending

    fun dispose() {
        runCleanupSteps(
            { if (state != SyntaxSessionState.Closed) cancel() },
            debounce::dispose,
        )
    }

    private fun requireOpen() {
        check(state != SyntaxSessionState.Closed) { "Syntax editing session is closed" }
    }

    private fun dispatch(event: SyntaxSessionEvent) {
        val transition = SyntaxSessionReducer.reduce(state, event)
        state = transition.state
        if (event is SyntaxSessionEvent.Restore) {
            runCleanupSteps(transition.effects.map { effect -> { execute(effect) } })
        } else {
            transition.effects.forEach(::execute)
        }
    }

    private fun execute(effect: SyntaxSessionEffect) {
        when (effect) {
            SyntaxSessionEffect.RestartDebounce -> debounce.restart()
            SyntaxSessionEffect.StopDebounce -> debounce.stop()
            is SyntaxSessionEffect.ApplyRuntime -> applyRuntime(effect)
            is SyntaxSessionEffect.Persist -> persist(effect.config)
            is SyntaxSessionEffect.Restore -> restore(effect.config)
            is SyntaxSessionEffect.RecordRelinquishment -> runtime.recordRelinquishment(effect.keyId)
            SyntaxSessionEffect.ShowForeignScheme -> runtime.showForeignScheme()
            SyntaxSessionEffect.Close -> debounce.dispose()
        }
    }

    private fun applyRuntime(effect: SyntaxSessionEffect.ApplyRuntime) {
        val result =
            when (effect.intent) {
                SyntaxSessionIntent.PREVIEW -> runtime.preview(effect.config)
                SyntaxSessionIntent.COMMIT -> runtime.materialize(effect.config)
                SyntaxSessionIntent.RESTORE_AND_STAY,
                SyntaxSessionIntent.RESTORE_AND_CLOSE,
                -> return
            }
        dispatchResult(result, effect.config, isRestore = false)
    }

    private fun persist(config: SyntaxPresetConfig) {
        try {
            persist.invoke(config)
            runtime.advance()
            dispatch(SyntaxSessionEvent.PersistenceSucceeded)
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: RuntimeException) {
            dispatch(SyntaxSessionEvent.PersistenceFailed(failure))
        }
    }

    private fun restore(config: SyntaxPresetConfig) {
        dispatchResult(runtime.restore(config), config, isRestore = true)
    }

    private fun dispatchResult(
        result: SyntaxTransactionResult,
        appliedConfig: SyntaxPresetConfig,
        isRestore: Boolean,
    ) {
        when (result) {
            is SyntaxTransactionResult.Applied -> {
                onRuntimeApplied(appliedConfig)
                result.relinquishedKeys.forEach { keyId ->
                    dispatch(SyntaxSessionEvent.KeyRelinquished(keyId))
                }
                val event =
                    if (isRestore) SyntaxSessionEvent.RestoreSucceeded else SyntaxSessionEvent.RuntimeSucceeded
                dispatch(event)
            }
            is SyntaxTransactionResult.Failure -> {
                if (result is SyntaxTransactionResult.RecoveryRequired) {
                    result.rollbackFailures.forEach { failure ->
                        log.warn("Failed to roll back syntax preview state", failure)
                        onRuntimeFailed(failure)
                    }
                }
                onRuntimeFailed(result.cause)
                if (isRestore) restoreFailure = result.cause
                val event =
                    if (isRestore) {
                        SyntaxSessionEvent.RestoreFailed(result.cause)
                    } else {
                        SyntaxSessionEvent.RuntimeFailed(result.cause)
                    }
                dispatch(event)
            }
        }
    }

    private fun restoreResult(): SyntaxRestoreResult {
        val failure = restoreFailure ?: (state as? SyntaxSessionState.Failed)?.failure
        return if (failure == null) SyntaxRestoreResult.Restored else SyntaxRestoreResult.Failed(failure)
    }
}

private class SwingSyntaxDebounce(
    callback: () -> Unit,
) : SyntaxDebounce {
    private val timer = Timer(DEBOUNCE_MILLISECONDS) { callback() }.apply { isRepeats = false }

    override fun restart() {
        timer.restart()
    }

    override fun stop() {
        timer.stop()
    }

    override fun dispose() {
        timer.stop()
    }

    private companion object {
        const val DEBOUNCE_MILLISECONDS = 100
    }
}

internal object SyntaxSessionReducer {
    fun reduce(
        state: SyntaxSessionState,
        event: SyntaxSessionEvent,
    ): SyntaxSessionTransition {
        if (state == SyntaxSessionState.Closed) return SyntaxSessionTransition(state)
        return when (event) {
            is SyntaxSessionEvent.Edit -> EditTransitions.reduce(state, event)
            is SyntaxSessionEvent.Runtime -> RuntimeTransitions.reduce(state, event)
            is SyntaxSessionEvent.Commit -> CommitTransitions.reduce(state, event)
            is SyntaxSessionEvent.Restore -> RestoreTransitions.reduce(state, event)
            is SyntaxSessionEvent.Environment -> EnvironmentTransitions.reduce(state, event)
        }
    }
}

private object EditTransitions {
    fun reduce(
        state: SyntaxSessionState,
        event: SyntaxSessionEvent.Edit,
    ): SyntaxSessionTransition =
        when (event) {
            is SyntaxSessionEvent.EditDiscrete -> editDiscrete(state, event.pending)
            is SyntaxSessionEvent.EditSlider -> editSlider(state, event.pending)
            is SyntaxSessionEvent.Stage -> stage(state, event.pending)
            SyntaxSessionEvent.DebounceElapsed,
            SyntaxSessionEvent.SliderReleased,
            -> flushSlider(state)
        }

    private fun editDiscrete(
        state: SyntaxSessionState,
        pending: SyntaxPresetConfig,
    ): SyntaxSessionTransition {
        val data = state.data() ?: return SyntaxSessionTransition(state)
        return applyingTransition(data, pending, SyntaxSessionIntent.PREVIEW)
    }

    private fun editSlider(
        state: SyntaxSessionState,
        pending: SyntaxPresetConfig,
    ): SyntaxSessionTransition {
        val data = state.data() ?: return SyntaxSessionTransition(state)
        return SyntaxSessionTransition(
            SyntaxSessionState.Pending(data.checkpoint, pending, data.lastApplied),
            listOf(SyntaxSessionEffect.RestartDebounce),
        )
    }

    private fun stage(
        state: SyntaxSessionState,
        pending: SyntaxPresetConfig,
    ): SyntaxSessionTransition {
        val data = state.data() ?: return SyntaxSessionTransition(state)
        return SyntaxSessionTransition(SyntaxSessionState.Pending(data.checkpoint, pending, data.lastApplied))
    }

    private fun flushSlider(state: SyntaxSessionState): SyntaxSessionTransition {
        val pending = state as? SyntaxSessionState.Pending ?: return SyntaxSessionTransition(state)
        val data = SessionData(pending.checkpoint, pending.pending, pending.lastApplied)
        return applyingTransition(data, pending.pending, SyntaxSessionIntent.PREVIEW)
    }
}

private object RuntimeTransitions {
    fun reduce(
        state: SyntaxSessionState,
        event: SyntaxSessionEvent.Runtime,
    ): SyntaxSessionTransition =
        when (event) {
            SyntaxSessionEvent.RuntimeSucceeded -> succeeded(state)
            is SyntaxSessionEvent.RuntimeFailed -> operationFailed(state, event.failure)
        }

    private fun succeeded(state: SyntaxSessionState): SyntaxSessionTransition {
        val applying = state as? SyntaxSessionState.Applying ?: return SyntaxSessionTransition(state)
        return when (applying.intent) {
            SyntaxSessionIntent.PREVIEW ->
                SyntaxSessionTransition(
                    if (applying.pending == applying.checkpoint) {
                        SyntaxSessionState.Synced(applying.checkpoint)
                    } else {
                        SyntaxSessionState.Live(applying.checkpoint, applying.pending)
                    },
                )
            SyntaxSessionIntent.COMMIT ->
                SyntaxSessionTransition(
                    applying,
                    listOf(SyntaxSessionEffect.Persist(applying.pending)),
                )
            SyntaxSessionIntent.RESTORE_AND_STAY,
            SyntaxSessionIntent.RESTORE_AND_CLOSE,
            -> SyntaxSessionTransition(state)
        }
    }
}

private object CommitTransitions {
    fun reduce(
        state: SyntaxSessionState,
        event: SyntaxSessionEvent.Commit,
    ): SyntaxSessionTransition =
        when (event) {
            SyntaxSessionEvent.ApplyRequested -> request(state)
            SyntaxSessionEvent.PersistenceSucceeded -> persistenceSucceeded(state)
            is SyntaxSessionEvent.PersistenceFailed -> operationFailed(state, event.failure)
        }

    private fun request(state: SyntaxSessionState): SyntaxSessionTransition {
        if (state is SyntaxSessionState.Synced) return SyntaxSessionTransition(state)
        val data = state.data() ?: return SyntaxSessionTransition(state)
        return applyingTransition(data, data.pending, SyntaxSessionIntent.COMMIT)
    }

    private fun persistenceSucceeded(state: SyntaxSessionState): SyntaxSessionTransition {
        val applying = state as? SyntaxSessionState.Applying ?: return SyntaxSessionTransition(state)
        return when (applying.intent) {
            SyntaxSessionIntent.COMMIT -> SyntaxSessionTransition(SyntaxSessionState.Synced(applying.pending))
            else -> SyntaxSessionTransition(state)
        }
    }
}

private object RestoreTransitions {
    fun reduce(
        state: SyntaxSessionState,
        event: SyntaxSessionEvent.Restore,
    ): SyntaxSessionTransition =
        when (event) {
            SyntaxSessionEvent.FrameworkResetRequested -> request(state, close = false)
            SyntaxSessionEvent.CancelRequested -> request(state, close = true)
            SyntaxSessionEvent.RestoreSucceeded -> succeeded(state)
            is SyntaxSessionEvent.RestoreFailed -> failed(state, event.failure)
        }

    private fun request(
        state: SyntaxSessionState,
        close: Boolean,
    ): SyntaxSessionTransition {
        if (state is SyntaxSessionState.Synced) {
            return if (close) {
                SyntaxSessionTransition(SyntaxSessionState.Closed, listOf(SyntaxSessionEffect.Close))
            } else {
                SyntaxSessionTransition(state)
            }
        }
        val data = state.data() ?: return SyntaxSessionTransition(state)
        val intent = if (close) SyntaxSessionIntent.RESTORE_AND_CLOSE else SyntaxSessionIntent.RESTORE_AND_STAY
        return SyntaxSessionTransition(
            SyntaxSessionState.Applying(data.checkpoint, data.pending, data.lastApplied, intent),
            listOf(
                SyntaxSessionEffect.StopDebounce,
                SyntaxSessionEffect.Restore(data.checkpoint),
            ),
        )
    }

    private fun succeeded(state: SyntaxSessionState): SyntaxSessionTransition {
        val applying = state as? SyntaxSessionState.Applying ?: return SyntaxSessionTransition(state)
        return when (applying.intent) {
            SyntaxSessionIntent.RESTORE_AND_STAY ->
                SyntaxSessionTransition(SyntaxSessionState.Synced(applying.checkpoint))
            SyntaxSessionIntent.RESTORE_AND_CLOSE ->
                SyntaxSessionTransition(SyntaxSessionState.Closed, listOf(SyntaxSessionEffect.Close))
            else -> SyntaxSessionTransition(state)
        }
    }

    private fun failed(
        state: SyntaxSessionState,
        failure: RuntimeException,
    ): SyntaxSessionTransition {
        val applying = state as? SyntaxSessionState.Applying ?: return SyntaxSessionTransition(state)
        return when (applying.intent) {
            SyntaxSessionIntent.RESTORE_AND_CLOSE ->
                SyntaxSessionTransition(
                    SyntaxSessionState.Closed,
                    listOf(SyntaxSessionEffect.Close),
                )
            SyntaxSessionIntent.RESTORE_AND_STAY -> operationFailed(state, failure)
            else -> SyntaxSessionTransition(state)
        }
    }
}

private object EnvironmentTransitions {
    fun reduce(
        state: SyntaxSessionState,
        event: SyntaxSessionEvent.Environment,
    ): SyntaxSessionTransition =
        when (event) {
            is SyntaxSessionEvent.KeyRelinquished ->
                SyntaxSessionTransition(
                    state,
                    listOf(SyntaxSessionEffect.RecordRelinquishment(event.keyId)),
                )
            SyntaxSessionEvent.ActiveAyuSchemeChanged -> {
                val data = state.data() ?: return SyntaxSessionTransition(state)
                applyingTransition(data, data.pending, SyntaxSessionIntent.PREVIEW)
            }
            SyntaxSessionEvent.ForeignSchemeActivated ->
                SyntaxSessionTransition(state, listOf(SyntaxSessionEffect.ShowForeignScheme))
        }
}

private fun operationFailed(
    state: SyntaxSessionState,
    failure: RuntimeException,
): SyntaxSessionTransition {
    val applying = state as? SyntaxSessionState.Applying ?: return SyntaxSessionTransition(state)
    return SyntaxSessionTransition(
        SyntaxSessionState.Failed(
            applying.checkpoint,
            applying.pending,
            applying.lastApplied,
            failure,
        ),
    )
}

private fun applyingTransition(
    data: SessionData,
    pending: SyntaxPresetConfig,
    intent: SyntaxSessionIntent,
): SyntaxSessionTransition =
    SyntaxSessionTransition(
        SyntaxSessionState.Applying(data.checkpoint, pending, data.lastApplied, intent),
        listOf(
            SyntaxSessionEffect.StopDebounce,
            SyntaxSessionEffect.ApplyRuntime(pending, intent),
        ),
    )

private fun SyntaxSessionState.data(): SessionData? =
    when (this) {
        is SyntaxSessionState.Synced -> SessionData(checkpoint, checkpoint, checkpoint)
        is SyntaxSessionState.Pending -> SessionData(checkpoint, pending, lastApplied)
        is SyntaxSessionState.Applying -> SessionData(checkpoint, pending, lastApplied)
        is SyntaxSessionState.Live -> SessionData(checkpoint, pending, pending)
        is SyntaxSessionState.Failed -> SessionData(checkpoint, pending, lastApplied)
        SyntaxSessionState.Closed -> null
    }

private data class SessionData(
    val checkpoint: SyntaxPresetConfig,
    val pending: SyntaxPresetConfig,
    val lastApplied: SyntaxPresetConfig,
)
