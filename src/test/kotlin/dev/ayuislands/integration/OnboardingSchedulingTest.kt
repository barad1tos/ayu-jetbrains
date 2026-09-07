package dev.ayuislands.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.StartupLicenseHandler
import dev.ayuislands.onboarding.FreeOnboardingVirtualFile
import dev.ayuislands.onboarding.OnboardingOrchestrator
import dev.ayuislands.onboarding.OnboardingSchedulerService
import dev.ayuislands.onboarding.OnboardingVirtualFile
import dev.ayuislands.onboarding.WizardAction
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OnboardingSchedulingTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    private val dispatcher = QueuedDispatcher()
    private val lifetime = SupervisorJob()
    private val failures = ConcurrentLinkedQueue<Throwable>()
    private val state = AyuIslandsState()
    private val settings = AyuIslandsSettings()
    private val target = mockk<Project>()
    private val editors = mockk<FileEditorManager>()
    private var isDisposed = false
    private var activeProject: Project? = target
    private val openedFiles = mutableListOf<VirtualFile>()

    @Before
    fun setUp() {
        OnboardingOrchestrator.gate.resetForTesting()
        settings.loadState(state)
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        val exceptionHandler = CoroutineExceptionHandler { _, failure -> failures.add(failure) }
        val scope = CoroutineScope(lifetime + dispatcher + exceptionHandler)
        val scheduler = OnboardingSchedulerService(target, scope)
        every { target.getService(OnboardingSchedulerService::class.java) } returns scheduler
        every { target.getService(FileEditorManager::class.java) } returns editors
        every { target.isDisposed } answers { isDisposed }
        every { target.name } returns "onboarding-project"
        val frame = mockk<IdeFrame>()
        every { frame.project } answers { activeProject }
        val focusManager = mockk<IdeFocusManager>()
        every { focusManager.lastFocusedFrame } returns frame
        mockkStatic(IdeFocusManager::class)
        every { IdeFocusManager.getGlobalInstance() } returns focusManager
        allowOpening()
    }

    @After
    fun tearDown() {
        try {
            lifetime.cancel()
            drain()
        } finally {
            OnboardingOrchestrator.gate.resetForTesting()
            unmockkAll()
        }
    }

    @Test
    fun `successful opening marks the free wizard once after the editor opens`() {
        state.premiumOnboardingShown = true
        state.glowEnabled = false

        schedule()
        schedule()
        assertFalse(state.freeOnboardingShown)
        assertTrue(openedFiles.isEmpty())
        drain()

        assertTrue(state.freeOnboardingShown)
        assertIs<FreeOnboardingVirtualFile>(openedFiles.single())
        assertTrue(state.premiumOnboardingShown)
        assertFalse(state.glowEnabled)
    }

    @Test
    fun `failed opening preserves unseen state and allows a later retry`() {
        val failure = IllegalStateException("editor subsystem not ready")
        every { editors.openFile(any(), true) } throws failure
        schedule()

        val logged = LoggedErrorProcessor.executeAndReturnLoggedError { drain() }

        assertSame(failure, logged)
        assertFalse(state.freeOnboardingShown)
        allowOpening()
        schedule()
        drain()
        assertTrue(state.freeOnboardingShown)
        assertIs<FreeOnboardingVirtualFile>(openedFiles.single())
    }

    @Test
    fun `premium recovery marks shown without rewriting reloaded preferences`() {
        settings.state.apply {
            freeOnboardingShown = true
            premiumOnboardingShown = false
            trialWelcomeShown = false
            glowEnabled = true
            glowPreset = "CUSTOM"
            glowStyle = "SHARP_NEON"
            sharpNeonIntensity = 87
            fontPresetEnabled = true
            fontPresetName = "FUTURE_PRESET"
            fontPresetCustomizations["FUTURE_PRESET"] = "opaque|future|customization"
        }
        settings.state.glowEnabled = false
        val disabledXml = encodeState(settings.state)
        val failure = IllegalStateException("premium editor subsystem not ready")
        every { editors.openFile(any(), true) } throws failure

        schedulePremiumWizard()
        val logged = LoggedErrorProcessor.executeAndReturnLoggedError { drain() }

        assertSame(failure, logged)
        assertTrue(openedFiles.isEmpty())
        assertEquals(disabledXml, encodeState(settings.state))

        settings.loadState(decodeState(disabledXml))
        val disabledState = settings.state
        assertFalse(disabledState.glowEnabled)
        assertEquals("SHARP_NEON", disabledState.glowStyle)
        assertEquals(87, disabledState.sharpNeonIntensity)
        assertEquals("FUTURE_PRESET", disabledState.fontPresetName)
        assertTrue(disabledState.freeOnboardingShown)
        assertFalse(disabledState.trialWelcomeShown)
        assertFalse(disabledState.premiumOnboardingShown)

        disabledState.glowEnabled = true
        val reenabledXml = encodeState(disabledState)
        settings.loadState(decodeState(reenabledXml))
        val reloadedState = settings.state
        assertTrue(reloadedState.glowEnabled)
        assertEquals(reenabledXml, encodeState(reloadedState))
        val expectedState = decodeState(reenabledXml).apply { premiumOnboardingShown = true }
        val expectedXml = encodeState(expectedState)
        every { editors.openFile(any(), true) } answers {
            assertTrue(SwingUtilities.isEventDispatchThread())
            val stateAtOpen = settings.state
            assertFalse(stateAtOpen.premiumOnboardingShown, "Opening must complete before the premium wizard is marked")
            assertTrue(stateAtOpen.freeOnboardingShown)
            assertFalse(stateAtOpen.trialWelcomeShown)
            assertTrue(stateAtOpen.glowEnabled)
            assertEquals("FUTURE_PRESET", stateAtOpen.fontPresetName)
            val file = firstArg<VirtualFile>()
            assertIs<OnboardingVirtualFile>(file)
            openedFiles += file
            arrayOf(mockk<FileEditor>())
        }

        schedulePremiumWizard()
        drain()

        assertIs<OnboardingVirtualFile>(openedFiles.single())
        assertEquals(expectedXml, encodeState(settings.state))
        assertTrue(settings.state.premiumOnboardingShown)
        assertTrue(settings.state.freeOnboardingShown)
        assertFalse(settings.state.trialWelcomeShown)
    }

    @Test
    fun `cancelled opening preserves unseen state and allows a later retry`() {
        val cancellation = ProcessCanceledException()
        every { editors.openFile(any(), true) } throws cancellation
        schedule()
        var completionCause: Throwable? = null
        lifetime.children.single().invokeOnCompletion { completionCause = it }

        drain()

        assertSame(cancellation, completionCause)
        assertFalse(state.freeOnboardingShown)
        allowOpening()
        schedule()
        drain()
        assertTrue(state.freeOnboardingShown)
        assertIs<FreeOnboardingVirtualFile>(openedFiles.single())
    }

    @Test
    fun `cancelling queued work does not open or mark the wizard`() {
        schedule()
        lifetime.children.single().cancel()

        drain()

        assertFalse(state.freeOnboardingShown)
        verify(exactly = 0) { editors.openFile(any(), any<Boolean>()) }
    }

    @Test
    fun `cancelling during the startup delay leaves the wizard unseen`() {
        StartupLicenseHandler.handleWizardAction(WizardAction.ShowFreeWizard, target, 60_000)
        SwingUtilities.invokeAndWait { dispatcher.runPending() }
        assertFalse(state.freeOnboardingShown)
        assertTrue(openedFiles.isEmpty())
        lifetime.children.single().cancel()

        drain()

        assertFalse(state.freeOnboardingShown)
        verify(exactly = 0) { editors.openFile(any(), any<Boolean>()) }
    }

    @Test
    fun `focus deferral preserves unseen state until the active project retries`() {
        activeProject = mockk<Project>()
        schedule()
        drain()

        assertFalse(state.freeOnboardingShown)
        verify(exactly = 0) { editors.openFile(any(), any<Boolean>()) }
        activeProject = target
        schedule()
        drain()
        assertTrue(state.freeOnboardingShown)
        assertEquals(1, openedFiles.size)
    }

    @Test
    fun `project disposed before launch does not open or mark the wizard`() {
        schedule()
        isDisposed = true

        drain()

        assertFalse(state.freeOnboardingShown)
        verify(exactly = 0) { editors.openFile(any(), any<Boolean>()) }
    }

    @Test
    fun `disposed project leaves queued premium onboarding unseen`() {
        settings.state.apply {
            freeOnboardingShown = true
            premiumOnboardingShown = false
            trialWelcomeShown = false
            glowEnabled = false
            fontPresetEnabled = true
            fontPresetName = "FUTURE_PRESET"
        }
        val expectedXml = encodeState(settings.state)
        schedulePremiumWizard()
        isDisposed = true

        drain()

        assertEquals(expectedXml, encodeState(settings.state))
        assertTrue(openedFiles.isEmpty())
        verify(exactly = 0) { editors.openFile(any(), any<Boolean>()) }
    }

    @Test
    fun `project disposed after the EDT callback is queued stays unseen`() {
        schedule()
        SwingUtilities.invokeAndWait {
            // Keep the EDT occupied while the real opener queues its withContext continuation.
            ApplicationManager
                .getApplication()
                .executeOnPooledThread { dispatcher.runPending() }
                .get(10, TimeUnit.SECONDS)
            assertTrue(lifetime.children.any(), "The EDT continuation must still be pending")
            isDisposed = true
        }

        drain()

        assertFalse(state.freeOnboardingShown)
        verify(exactly = 0) { editors.openFile(any(), any<Boolean>()) }
    }

    private fun schedule() {
        StartupLicenseHandler.handleWizardAction(WizardAction.ShowFreeWizard, target, 0)
    }

    private fun schedulePremiumWizard() {
        StartupLicenseHandler.handleWizardAction(WizardAction.ShowPremiumWizard, target, 0)
    }

    private fun allowOpening() {
        every { editors.openFile(any(), true) } answers {
            assertTrue(SwingUtilities.isEventDispatchThread())
            assertFalse(state.freeOnboardingShown, "Scheduling must not mark the wizard before the editor opens")
            openedFiles += firstArg<VirtualFile>()
            arrayOf(mockk<FileEditor>())
        }
    }

    private fun drain() {
        SwingUtilities.invokeAndWait {
            PlatformTestUtil.waitWithEventsDispatching(
                "Onboarding coroutine did not complete",
                {
                    dispatcher.runPending()
                    lifetime.children.none()
                },
                10,
            )
        }
        failures.poll()?.let { throw it }
    }

    private fun encodeState(source: AyuIslandsState): String = JDOMUtil.writeElement(XmlSerializer.serialize(source))

    private fun decodeState(xml: String): AyuIslandsState =
        XmlSerializer.deserialize(JDOMUtil.load(xml), AyuIslandsState::class.java)
}

private class QueuedDispatcher : CoroutineDispatcher() {
    private val pending = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        pending.add(block)
    }

    fun runPending() {
        while (true) {
            val next = pending.poll() ?: return
            next.run()
        }
    }
}
