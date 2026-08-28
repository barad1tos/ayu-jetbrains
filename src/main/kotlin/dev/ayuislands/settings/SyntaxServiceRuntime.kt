package dev.ayuislands.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxTransactionResult
import kotlin.coroutines.cancellation.CancellationException

/** Bridges the settings session to one reversible syntax service session. */
internal class SyntaxServiceRuntime(
    private val runtime: SyntaxIntensityService.SyntaxRuntimeSession,
    private val recover: (SyntaxPresetConfig, RuntimeException) -> Unit,
    private val onRelinquished: (String) -> Unit,
    private val onForeignScheme: () -> Unit,
) : SyntaxEditingRuntime {
    var isWriting: Boolean = false
        private set

    constructor(
        service: SyntaxIntensityService = SyntaxIntensityService.getInstance(),
        onRelinquished: (String) -> Unit = {},
        onForeignScheme: () -> Unit = {},
    ) : this(
        runtime = service.openRuntimeSession(),
        recover = { config, failure -> scheduleRecovery(service, config, failure) },
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

private fun scheduleRecovery(
    service: SyntaxIntensityService,
    config: SyntaxPresetConfig,
    restoreFailure: RuntimeException,
) {
    logger<SyntaxServiceRuntime>().warn(
        "Failed to restore the syntax preview; retrying the persisted checkpoint",
        restoreFailure,
    )
    ApplicationManager.getApplication().invokeLater {
        retryRecovery(service, config)
    }
}

private fun retryRecovery(
    service: SyntaxIntensityService,
    config: SyntaxPresetConfig,
) {
    try {
        service.apply(config)
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (retryFailure: RuntimeException) {
        logger<SyntaxServiceRuntime>().warn("Failed to recover the persisted syntax checkpoint", retryFailure)
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
                        retryRecovery(service, config)
                    }
                },
            ).notify(null)
    }
}
