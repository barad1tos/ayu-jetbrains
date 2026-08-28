package dev.ayuislands.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxTransactionResult
import kotlin.coroutines.cancellation.CancellationException

/** Bridges the settings session to one reversible syntax service session. */
internal class SyntaxServiceRuntime(
    dependencies: RuntimeDependencies,
    private val onRelinquished: (String) -> Unit,
    private val onForeignScheme: () -> Unit,
) : SyntaxEditingRuntime {
    private val runtime = dependencies.runtime
    private val recover = dependencies.recover

    var isWriting: Boolean = false
        private set

    constructor(
        service: SyntaxIntensityService = SyntaxIntensityService.getInstance(),
        onRelinquished: (String) -> Unit = {},
        onForeignScheme: () -> Unit = {},
    ) : this(
        dependencies = runtimeDependencies(service),
        onRelinquished = onRelinquished,
        onForeignScheme = onForeignScheme,
    )

    override fun preview(config: SyntaxPresetConfig): SyntaxTransactionResult = write { runtime.preview(config) }

    override fun materialize(config: SyntaxPresetConfig): SyntaxTransactionResult =
        write { runtime.materialize(config) }

    override fun restore(config: SyntaxPresetConfig): SyntaxTransactionResult = write(runtime::restore)

    override fun advance() {
        runtime.advance()
    }

    override fun scheduleRecovery(
        config: SyntaxPresetConfig,
        failure: RuntimeException,
    ) {
        recover(config, failure)
    }

    override fun recordRelinquishment(keyId: String) {
        onRelinquished(keyId)
    }

    override fun showForeignScheme() {
        onForeignScheme()
    }

    private fun write(operation: () -> SyntaxTransactionResult): SyntaxTransactionResult {
        check(!isWriting) { "Nested syntax runtime writes are not supported" }
        isWriting = true
        return try {
            operation()
        } finally {
            isWriting = false
        }
    }
}

internal data class RuntimeDependencies(
    val runtime: SyntaxIntensityService.SyntaxRuntimeSession,
    val recover: (SyntaxPresetConfig, RuntimeException) -> Unit,
)

private fun runtimeDependencies(service: SyntaxIntensityService): RuntimeDependencies {
    val runtime = service.openRuntimeSession()
    return RuntimeDependencies(
        runtime = runtime,
        recover = { _, failure -> restoreOrNotify(runtime, service, failure) },
    )
}

private fun restoreOrNotify(
    runtime: SyntaxIntensityService.SyntaxRuntimeSession,
    service: SyntaxIntensityService,
    restoreFailure: RuntimeException,
    synchronizePersistedConfig: Boolean = false,
) {
    logger<SyntaxServiceRuntime>().warn(
        "Failed to restore the syntax preview; retrying its retained checkpoint",
        restoreFailure,
    )
    val retryFailure = retryRecovery(runtime, service, synchronizePersistedConfig)
    if (retryFailure != null) {
        showRecoveryNotification(runtime, service, retryFailure)
    }
}

private fun retryRecovery(
    runtime: SyntaxIntensityService.SyntaxRuntimeSession,
    service: SyntaxIntensityService,
    synchronizePersistedConfig: Boolean,
): RuntimeException? =
    try {
        when (val result = runtime.restore()) {
            is SyntaxTransactionResult.Applied -> {
                if (synchronizePersistedConfig) {
                    service.apply(SyntaxIntensityState.getInstance().toPresetConfig())
                }
                null
            }
            is SyntaxTransactionResult.RecoveryRequired -> {
                result.rollbackFailures.forEach { failure ->
                    logger<SyntaxServiceRuntime>().warn("Failed to recover a retained syntax checkpoint", failure)
                }
                result.cause
            }
            is SyntaxTransactionResult.RolledBack -> result.cause
        }
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (retryFailure: RuntimeException) {
        retryFailure
    }

private fun showRecoveryNotification(
    runtime: SyntaxIntensityService.SyntaxRuntimeSession,
    service: SyntaxIntensityService,
    failure: RuntimeException,
) {
    logger<SyntaxServiceRuntime>().warn("Failed to recover the persisted syntax checkpoint", failure)
    val notification =
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Ayu Islands")
            .createNotification(
                "Syntax settings could not be restored",
                "Your saved choices were not changed. Retry to restore their editor appearance now.",
                NotificationType.ERROR,
            )
    notification
        .addAction(
            object : NotificationAction("Retry") {
                override fun actionPerformed(
                    event: AnActionEvent,
                    current: Notification,
                ) {
                    current.expire()
                    restoreOrNotify(
                        runtime = runtime,
                        service = service,
                        restoreFailure = failure,
                        synchronizePersistedConfig = true,
                    )
                }
            },
        ).notify(null)
}
