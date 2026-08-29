package dev.ayuislands.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxTransactionResult

/** Bridges the settings session to one reversible syntax service session. */
internal class SyntaxServiceRuntime(
    private val service: SyntaxIntensityService = SyntaxIntensityService.getInstance(),
    private val onRelinquished: (String) -> Unit = {},
    private val onForeignScheme: () -> Unit = {},
) : SyntaxEditingRuntime {
    private val runtime = service.openRuntimeSession()

    var isWriting: Boolean = false
        private set

    override fun preview(config: SyntaxPresetConfig): SyntaxTransactionResult = write { runtime.preview(config) }

    override fun materialize(config: SyntaxPresetConfig): SyntaxTransactionResult =
        write { runtime.materialize(config) }

    override fun restore(config: SyntaxPresetConfig): SyntaxTransactionResult = write(runtime::restore)

    override fun advance() {
        runtime.advance()
    }

    fun close() {
        runtime.close()?.let { ticket -> showRecoveryNotification(service, ticket) }
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

private fun showRecoveryNotification(
    service: SyntaxIntensityService,
    ticket: SyntaxIntensityService.SyntaxRecoveryTicket,
) {
    logger<SyntaxServiceRuntime>().warn("Failed to restore the saved syntax appearance", ticket.failure)
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
                    when (val result = service.retryRecovery(ticket)) {
                        is SyntaxTransactionResult.Applied -> current.expire()
                        is SyntaxTransactionResult.RecoveryRequired -> {
                            result.rollbackFailures.forEach { failure ->
                                logger<SyntaxServiceRuntime>().warn(
                                    "Failed to recover a retained syntax checkpoint",
                                    failure,
                                )
                            }
                        }
                        is SyntaxTransactionResult.RolledBack -> {
                            logger<SyntaxServiceRuntime>().warn(
                                "Failed to refresh the restored syntax appearance",
                                result.cause,
                            )
                        }
                    }
                }
            },
        ).notify(null)
}
