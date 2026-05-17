package dev.ayuislands.settings.mappings

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.messages.MessageBus
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentChangeListener
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.ui.ComponentTreeRefresher
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Window
import java.awt.event.WindowEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * D-03 invariant lock: when [ProjectAccentSwapService.handleWindowActivated] takes
 * the same-hex branch (`hexChanged == false`), the service publishes
 * [AccentChangedTopic.TOPIC] exactly once for the activated project with the
 * post-swap resolution source.
 *
 * The hex-changed branch is unaffected — that path re-enters
 * [AccentApplicator.applyFromHexString] (whose own D-02 publisher fires), so
 * `ProjectAccentSwapService` itself must NOT publish a second time. Pattern B
 * try/catch protects the publish call from a malicious subscriber.
 */
class ProjectAccentSwapServicePublishTest {
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockMessageBus = mockk<MessageBus>(relaxed = true)
    private lateinit var listener: AccentChangeListener

    @BeforeTest
    fun setUp() {
        state.irIntegrationEnabled = true

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.messageBus } returns mockMessageBus

        listener = mockk(relaxed = true)
        every { mockMessageBus.syncPublisher(AccentChangedTopic.TOPIC) } returns listener

        mockkObject(AccentResolver)
        mockkObject(AccentApplicator)
        mockkObject(AyuVariant.Companion)
        mockkObject(ComponentTreeRefresher)
        mockkObject(IndentRainbowSync)
        every { AccentApplicator.applyFromHexString(any()) } returns true
        every { AccentApplicator.syncCodeGlanceProViewportForSwap(any()) } just Runs
        every { IndentRainbowSync.apply(any(), any()) } just Runs
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { ComponentTreeRefresher.walkAndNotify(any(), any()) } just Runs

        mockkStatic(WindowManager::class)
        val windowManager =
            mockk<WindowManager> {
                every { allProjectFrames } returns emptyArray()
            }
        every { WindowManager.getInstance() } returns windowManager

        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.getWindowAncestor(any()) } returns null
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `same-hex branch publishes AccentChangedTopic with post-swap source`() {
        // D-03 invariant: the same-hex branch updates per-project CGP/IR caches
        // WITHOUT re-entering AccentApplicator.apply (whose own publisher would
        // otherwise fire). Subscribers must still learn about the focus swap so
        // the chip's source label updates from "Global" to "Project override"
        // when the activated project carries an override.
        val (window, project) = wireMatchingFrame()
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"
        every { AccentResolver.source(project) } returns AccentResolver.Source.PROJECT_OVERRIDE

        val service = ProjectAccentSwapService()

        // First activation primes lastAppliedHex (hexChanged=true → applyFromHexString).
        service.onWindowActivatedForTest(makeEvent(window))
        // Second activation: same hex → same-hex branch fires the new publisher.
        service.onWindowActivatedForTest(makeEvent(window))

        verify(exactly = 1) {
            listener.accentChanged(project, "#FFCC66", AccentResolver.Source.PROJECT_OVERRIDE)
        }
    }

    @Test
    fun `hex-changed branch does NOT publish a second time from the swap service`() {
        // D-03 scope guard: the hex-changed branch already re-enters
        // AccentApplicator.applyFromHexString → apply, which publishes via D-02.
        // `ProjectAccentSwapService` itself must NOT publish on the changed-hex
        // branch — otherwise subscribers fire twice (once from the apply pipeline,
        // once from the swap service) and the chip momentarily renders the wrong
        // label / repaints redundantly.
        val (window, project) = wireMatchingFrame()
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#5CCFE6"
        every { AccentResolver.source(project) } returns AccentResolver.Source.GLOBAL

        val service = ProjectAccentSwapService()
        service.onWindowActivatedForTest(makeEvent(window)) // hexChanged=true → delegates to apply

        // `applyFromHexString` is stubbed so the D-02 publisher does NOT run here.
        // We are asserting that the swap service itself stays silent on the
        // changed-hex branch — the publish belongs to the apply path exclusively.
        verify(exactly = 0) { listener.accentChanged(any(), any(), any()) }
    }

    @Test
    fun `same-hex branch survives a throwing subscriber without aborting the swap pipeline`() {
        // Pattern B regression lock at the D-03 publish site. A malicious
        // subscriber must NOT prevent the swap service from running its post-
        // publish bookkeeping (walkAndNotify already ran before the publish, and
        // the handler still returns gracefully from `handleWindowActivated`).
        val (window, project) = wireMatchingFrame()
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"
        every { AccentResolver.source(project) } returns AccentResolver.Source.GLOBAL
        every { listener.accentChanged(any(), any(), any()) } answers { error("boom") }

        val service = ProjectAccentSwapService()
        // First activation primes the cache; second activation hits the same-hex branch.
        service.onWindowActivatedForTest(makeEvent(window))
        // The next call must not throw out of the handler — Pattern B contains the throw.
        service.onWindowActivatedForTest(makeEvent(window))

        // ComponentTreeRefresher.walkAndNotify fires for BOTH activations, proving the
        // handler ran to completion despite the subscriber exception.
        verify(exactly = 2) { ComponentTreeRefresher.walkAndNotify(project, window) }
        // The publisher was invoked on the same-hex branch (second activation).
        verify(exactly = 1) {
            listener.accentChanged(project, "#FFCC66", AccentResolver.Source.GLOBAL)
        }
    }

    // -- Test helpers -------------------------------------------------------

    private fun makeEvent(window: Window): WindowEvent =
        mockk {
            every { id } returns WindowEvent.WINDOW_ACTIVATED
            every { this@mockk.window } returns window
        }

    private fun wireMatchingFrame(): Pair<Window, Project> {
        val window = mockk<Window>(relaxed = true)
        val component = JPanel()
        every { SwingUtilities.getWindowAncestor(component) } returns window
        val project = stubProject("publish-project")
        val frame = stubIdeFrame(project, component)
        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(frame)
        return window to project
    }

    private fun stubProject(name: String): Project =
        mockk {
            every { isDisposed } returns false
            every { isDefault } returns false
            every { this@mockk.name } returns name
        }

    private fun stubIdeFrame(
        project: Project,
        component: JComponent,
    ): IdeFrame =
        mockk {
            every { this@mockk.project } returns project
            every { this@mockk.component } returns component
        }
}
