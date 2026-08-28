package dev.ayuislands.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxTransactionResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
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
                dependencies = dependencies(mockk(relaxed = true)),
                onRelinquished = relinquished::add,
                onForeignScheme = { foreignReports++ },
            )

        runtime.recordRelinquishment("SWIFT_OPERATOR")
        runtime.showForeignScheme()

        assertEquals(listOf("SWIFT_OPERATOR"), relinquished)
        assertEquals(1, foreignReports)
    }

    @Test
    fun `failed recovery retries the retained journal before applying current persisted config`() {
        val manager = mockk<NotificationGroupManager>()
        val group = mockk<NotificationGroup>()
        val notification = mockk<Notification>()
        val action = slot<NotificationAction>()
        val service = mockk<SyntaxIntensityService>(relaxed = true)
        val session = mockk<SyntaxIntensityService.SyntaxRuntimeSession>()
        val checkpoint = config("CUSTOM")
        val current = config("AMBIENT")
        val restoreFailure = IllegalStateException("restore failed")
        every { session.restore() } returns
            SyntaxTransactionResult.RecoveryRequired(restoreFailure, listOf(restoreFailure)) andThen applied()
        every { service.openRuntimeSession() } returns session
        every { service.apply(checkpoint) } throws IllegalStateException("legacy recovery path")
        every { service.apply(current) } returns Unit
        mockkStatic(NotificationGroupManager::class)
        mockkObject(SyntaxIntensityState.Companion)
        every { SyntaxIntensityState.getInstance().toPresetConfig() } returns current
        every { NotificationGroupManager.getInstance() } returns manager
        every { manager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification
        every { notification.addAction(capture(action)) } returns notification
        every { notification.notify(null) } returns Unit
        every { notification.expire() } returns Unit

        try {
            SyntaxServiceRuntime(service).scheduleRecovery(checkpoint, restoreFailure)
            action.captured.actionPerformed(mockk<AnActionEvent>(relaxed = true), notification)

            verify(exactly = 2) { session.restore() }
            verify(exactly = 1) { service.apply(current) }
            verify(exactly = 0) { service.apply(checkpoint) }
            verify(exactly = 1) { notification.expire() }
        } finally {
            unmockkAll()
        }
    }

    private fun serviceRuntime(session: SyntaxIntensityService.SyntaxRuntimeSession): SyntaxServiceRuntime =
        SyntaxServiceRuntime(
            dependencies = dependencies(session),
            onRelinquished = {},
            onForeignScheme = {},
        )

    private fun dependencies(session: SyntaxIntensityService.SyntaxRuntimeSession): RuntimeDependencies =
        RuntimeDependencies(session) { _, _ -> }

    private fun config(preset: String = "AMBIENT"): SyntaxPresetConfig =
        SyntaxPresetConfig(selectedPreset = preset, customOverrides = emptyMap())

    private fun applied(): SyntaxTransactionResult = SyntaxTransactionResult.Applied(emptySet(), emptySet())
}
