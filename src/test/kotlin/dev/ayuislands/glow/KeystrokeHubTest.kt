package dev.ayuislands.glow

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class KeystrokeHubTest {
    private val settings = mockk<AyuIslandsSettings>()
    private val state = AyuIslandsState()
    private val actionManager = mockk<ActionManager>()
    private val project = mockk<Project>()
    private val manager = mockk<GlowOverlayManager>(relaxed = true)
    private val input = mockk<GlowInputSink>(relaxed = true)
    private val dataContext = mockk<DataContext>()

    @BeforeTest
    fun setUp() {
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        every { settings.state } returns state
        state.glowEnabled = true

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns actionManager
        every { project.getService(GlowOverlayManager::class.java) } returns manager
        every { manager.input } returns input
        every { dataContext.getData(CommonDataKeys.PROJECT) } returns project
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `typed characters route to the project glow manager`() {
        val hub = KeystrokeHub()

        hub.actionListener.beforeEditorTyping('a', dataContext)
        hub.actionListener.beforeEditorTyping('b', dataContext)

        verify(exactly = 2) { input.onKeystroke() }
        verify(exactly = 1) { LicenseChecker.isLicensedOrGrace() }
    }

    @Test
    fun `only approved editor actions route as keystrokes`() {
        val hub = KeystrokeHub()
        val event = mockk<AnActionEvent>()
        every { event.project } returns project

        for (actionId in APPROVED_ACTION_IDS) {
            val action = mockk<AnAction>()
            every { actionManager.getId(action) } returns actionId
            hub.actionListener.beforeActionPerformed(action, event)
        }

        val unrelated = mockk<AnAction>()
        every { actionManager.getId(unrelated) } returns "EditorLeft"
        hub.actionListener.beforeActionPerformed(unrelated, event)

        verify(exactly = APPROVED_ACTION_IDS.size) { input.onKeystroke() }
    }

    @Test
    fun `null project and disabled glow do not route input`() {
        val hub = KeystrokeHub()
        val noProject = mockk<DataContext>()
        every { noProject.getData(CommonDataKeys.PROJECT) } returns null

        hub.actionListener.beforeEditorTyping('a', noProject)
        state.glowEnabled = false
        hub.actionListener.beforeEditorTyping('b', dataContext)

        verify(exactly = 0) { input.onKeystroke() }
    }

    @Test
    fun `typing boundary contains downstream failures`() {
        every { LicenseChecker.isLicensedOrGrace() } throws RuntimeException("license unavailable")
        val hub = KeystrokeHub()

        hub.actionListener.beforeEditorTyping('a', dataContext)
        hub.actionListener.beforeEditorTyping('b', dataContext)

        verify(exactly = 0) { input.onKeystroke() }
        assertRouteFailureLogged(hub, expected = true)
        hub.invalidateLicenseGate()
        assertRouteFailureLogged(hub, expected = false)
    }

    @Test
    fun `license gate invalidation restores typing after renewal`() {
        every { LicenseChecker.isLicensedOrGrace() } returnsMany listOf(false, true)
        val hub = KeystrokeHub()

        hub.actionListener.beforeEditorTyping('a', dataContext)
        hub.invalidateLicenseGate()
        hub.actionListener.beforeEditorTyping('b', dataContext)

        verify(exactly = 1) { input.onKeystroke() }
        verify(exactly = 2) { LicenseChecker.isLicensedOrGrace() }
    }

    @Test
    fun `cached license expires and rechecks before routing more input`() {
        var nowMs = 0L
        every { LicenseChecker.isLicensedOrGrace() } returnsMany listOf(true, false)
        val hub = KeystrokeHub { nowMs }

        hub.actionListener.beforeEditorTyping('a', dataContext)
        nowMs = OFFLINE_GRACE_MS
        hub.actionListener.beforeEditorTyping('b', dataContext)

        verify(exactly = 1) { input.onKeystroke() }
        verify(exactly = 2) { LicenseChecker.isLicensedOrGrace() }
    }

    @Test
    fun `Power Save topic broadcasts the current mode`() {
        val application = mockk<Application>()
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val listener = slot<PowerSaveMode.Listener>()
        val hub = KeystrokeHub()
        mockkStatic(ApplicationManager::class)
        mockkStatic(PowerSaveMode::class)
        mockkObject(GlowOverlayManager.Companion)
        every { ApplicationManager.getApplication() } returns application
        every { application.messageBus } returns messageBus
        every { messageBus.connect(hub) } returns connection
        every { connection.subscribe(PowerSaveMode.TOPIC, capture(listener)) } returns Unit
        every { PowerSaveMode.isEnabled() } returns true
        every { GlowOverlayManager.broadcastPowerSave(any()) } returns Unit

        hub.initialize()
        listener.captured.powerSaveStateChanged()

        verify(exactly = 1) { GlowOverlayManager.broadcastPowerSave(true) }
    }

    private companion object {
        const val OFFLINE_GRACE_MS = 48L * 60 * 60 * 1_000
        val APPROVED_ACTION_IDS =
            setOf(
                IdeActions.ACTION_EDITOR_BACKSPACE,
                IdeActions.ACTION_EDITOR_ENTER,
                IdeActions.ACTION_EDITOR_DELETE,
            )
    }

    private fun assertRouteFailureLogged(
        hub: KeystrokeHub,
        expected: Boolean,
    ) {
        val field = KeystrokeHub::class.java.getDeclaredField("routeFailureLogged")
        field.isAccessible = true
        kotlin.test.assertEquals(expected, field.getBoolean(hub))
    }
}
