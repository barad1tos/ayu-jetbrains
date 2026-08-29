package dev.ayuislands.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxTransactionResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntaxServiceRuntimeTest {
    @Test
    fun `runtime write guard covers synchronous preview publication`() {
        val session = mockk<SyntaxIntensityService.SyntaxRuntimeSession>()
        lateinit var runtime: SyntaxServiceRuntime
        every { session.preview(any()) } answers {
            assertTrue(runtime.isWriting)
            applied()
        }
        runtime = serviceRuntime(session)

        runtime.preview(config())

        assertFalse(runtime.isWriting)
    }

    @Test
    fun `environment callbacks stay presentation-only`() {
        val relinquished = mutableListOf<String>()
        var foreignReports = 0
        val service = mockk<SyntaxIntensityService>()
        every { service.openRuntimeSession() } returns mockk(relaxed = true)
        val runtime =
            SyntaxServiceRuntime(
                service = service,
                onRelinquished = relinquished::add,
                onForeignScheme = { foreignReports++ },
            )

        runtime.recordRelinquishment("SWIFT_OPERATOR")
        runtime.showForeignScheme()

        assertEquals(listOf("SWIFT_OPERATOR"), relinquished)
        assertEquals(1, foreignReports)
    }

    @Test
    fun `recovery action retains its ticket until every recovery phase succeeds`() {
        val manager = mockk<NotificationGroupManager>()
        val group = mockk<NotificationGroup>()
        val notification = mockk<Notification>()
        val action = slot<NotificationAction>()
        val service = mockk<SyntaxIntensityService>()
        val session = mockk<SyntaxIntensityService.SyntaxRuntimeSession>()
        val failure = IllegalStateException("restore failed")
        val ticket = SyntaxIntensityService.SyntaxRecoveryTicket(failure)
        every { service.openRuntimeSession() } returns session
        every { session.close() } returns ticket
        every { service.retryRecovery(ticket) } returns
            SyntaxTransactionResult.RecoveryRequired(
                failure,
                listOf(IllegalStateException("rollback still failed")),
            ) andThen
            SyntaxTransactionResult.RolledBack(IllegalStateException("refresh failed")) andThen
            applied()
        mockkStatic(NotificationGroupManager::class)
        every { NotificationGroupManager.getInstance() } returns manager
        every { manager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification
        every { notification.addAction(capture(action)) } returns notification
        every { notification.notify(null) } returns Unit
        every { notification.expire() } returns Unit

        try {
            SyntaxServiceRuntime(service).close()
            action.captured.actionPerformed(mockk<AnActionEvent>(relaxed = true), notification)
            verify(exactly = 0) { notification.expire() }
            action.captured.actionPerformed(mockk<AnActionEvent>(relaxed = true), notification)
            verify(exactly = 0) { notification.expire() }
            action.captured.actionPerformed(mockk<AnActionEvent>(relaxed = true), notification)

            verify(exactly = 3) { service.retryRecovery(ticket) }
            verify(exactly = 0) { session.restore() }
            verify(exactly = 1) { notification.expire() }
        } finally {
            unmockkAll()
        }
    }

    private fun serviceRuntime(session: SyntaxIntensityService.SyntaxRuntimeSession): SyntaxServiceRuntime {
        val service = mockk<SyntaxIntensityService>()
        every { service.openRuntimeSession() } returns session
        return SyntaxServiceRuntime(service)
    }

    private fun config(preset: String = "AMBIENT"): SyntaxPresetConfig =
        SyntaxPresetConfig(selectedPreset = preset, customOverrides = emptyMap())

    private fun applied(): SyntaxTransactionResult = SyntaxTransactionResult.Applied(emptySet(), emptySet())
}
