package dev.ayuislands.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
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
        val runtime =
            SyntaxServiceRuntime(
                runtime = mockk(relaxed = true),
                recover = { _, _ -> },
                onRelinquished = relinquished::add,
                onForeignScheme = { foreignReports++ },
            )

        runtime.recordRelinquishment("SWIFT_OPERATOR")
        runtime.showForeignScheme()

        assertEquals(listOf("SWIFT_OPERATOR"), relinquished)
        assertEquals(1, foreignReports)
    }

    @Test
    fun `failed automatic recovery notification retries the persisted checkpoint`() {
        val application = mockk<Application>()
        val manager = mockk<NotificationGroupManager>()
        val group = mockk<NotificationGroup>()
        val notification = mockk<Notification>()
        val action = slot<NotificationAction>()
        val service = mockk<SyntaxIntensityService>(relaxed = true)
        val config = config()
        every { service.apply(config) } throws IllegalStateException("automatic retry failed") andThen Unit
        mockkStatic(ApplicationManager::class, NotificationGroupManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(any<Runnable>()) } answers { firstArg<Runnable>().run() }
        every { NotificationGroupManager.getInstance() } returns manager
        every { manager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification
        every { notification.addAction(capture(action)) } returns notification
        every { notification.notify(null) } returns Unit
        every { notification.expire() } returns Unit

        try {
            SyntaxServiceRuntime(service).scheduleRecovery(config, IllegalStateException("restore failed"))
            action.captured.actionPerformed(mockk<AnActionEvent>(relaxed = true), notification)

            verify(exactly = 2) { service.apply(config) }
            verify(exactly = 1) { notification.expire() }
        } finally {
            unmockkAll()
        }
    }

    private fun serviceRuntime(session: SyntaxIntensityService.SyntaxRuntimeSession): SyntaxServiceRuntime =
        SyntaxServiceRuntime(
            runtime = session,
            recover = { _, _ -> },
            onRelinquished = {},
            onForeignScheme = {},
        )

    private fun config(): SyntaxPresetConfig =
        SyntaxPresetConfig(selectedPreset = "AMBIENT", customOverrides = emptyMap())

    private fun applied(): SyntaxTransactionResult = SyntaxTransactionResult.Applied(emptySet(), emptySet())
}
