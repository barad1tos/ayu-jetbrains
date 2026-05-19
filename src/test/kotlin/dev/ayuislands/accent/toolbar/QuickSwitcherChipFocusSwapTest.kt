package dev.ayuislands.accent.toolbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.messages.Topic
import com.intellij.util.ui.ColorIcon
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentChangeListener
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Focus-tracking guarantee. The chip must reflect the FOCUSED project's
 * accent, swapping within one EDT cycle on:
 *   1. `ApplicationActivationListener.applicationActivated(...)` — alt-tab / Cmd+\` switch
 *   2. `AccentChangedTopic` publish — the user changed accent in another project
 *
 * Regression lock — the chip resolves through [AccentApplicator.resolveFocusedProject]
 * (the canonical OS-active + IdeFocusManager + openProjects cascade), NOT through
 * `ProjectManager.openProjects.firstOrNull` which was explicitly rejected.
 *
 * Captures the listener instances handed to `MessageBusConnection.subscribe` via [io.mockk.slot]s,
 * then fires them by hand — exercises the same code path the platform takes without booting
 * the IntelliJ application.
 */
class QuickSwitcherChipFocusSwapTest {
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockMessageBus = mockk<MessageBus>(relaxed = true)
    private val mockConnection = mockk<MessageBusConnection>(relaxed = true)
    private val mockProjectManager = mockk<ProjectManager>(relaxed = true)

    private val accentListenerSlot = slot<AccentChangeListener>()
    private val activationListenerSlot = slot<ApplicationActivationListener>()

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.messageBus } returns mockMessageBus
        every { mockMessageBus.connect(any<Disposable>()) } returns mockConnection

        // Catch-all FIRST — mockk evaluates last-defined-wins, so the specific captures
        // below shadow this. Keeps any unexpected subscribe (none at this layer today)
        // from throwing under a relaxed mock.
        every {
            mockConnection.subscribe(any<Topic<Any>>(), any<Any>())
        } returns Unit
        every {
            mockConnection.subscribe(eq(AccentChangedTopic.TOPIC), capture(accentListenerSlot))
        } returns Unit
        every {
            mockConnection.subscribe(
                eq(ApplicationActivationListener.TOPIC),
                capture(activationListenerSlot),
            )
        } returns Unit

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        mockkObject(AccentResolver)
        every { AccentResolver.sourceLabel(AccentResolver.Source.GLOBAL) } returns "Global"
        every { AccentResolver.sourceLabel(AccentResolver.Source.PROJECT_OVERRIDE) } returns "Project override"
        every { AccentResolver.sourceLabel(AccentResolver.Source.LANGUAGE_OVERRIDE) } returns "Language override"
        every { AccentResolver.source(any()) } returns AccentResolver.Source.GLOBAL

        mockkObject(AccentApplicator)
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `applicationActivated re-resolves accent against the newly focused project`() {
        val projectA =
            mockk<Project>(relaxed = true) {
                every { isDisposed } returns false
                every { isDefault } returns false
            }
        val projectB =
            mockk<Project>(relaxed = true) {
                every { isDisposed } returns false
                every { isDefault } returns false
            }
        every { AccentApplicator.resolveFocusedProject() } returnsMany listOf(projectA, projectB)
        every { AccentResolver.resolve(projectA, AyuVariant.MIRAGE) } returns "#FFCC66"
        every { AccentResolver.resolve(projectB, AyuVariant.MIRAGE) } returns "#5CCFE6"

        val chip = QuickSwitcherChipComponent()
        chip.addNotify() // refreshFromFocusedProject() runs once -> projectA -> #FFCC66

        assertTrue(activationListenerSlot.isCaptured, "ApplicationActivationListener must be captured")
        val activation = activationListenerSlot.captured
        val frame = mockk<IdeFrame>(relaxed = true)

        // First activation — projectA already drawn during addNotify. Second activation
        // pulls projectB next from the cascade.
        activation.applicationActivated(frame)
        SwingUtilities.invokeAndWait { /* flush */ }

        val icon = chip.icon as ColorIcon
        assertEquals(
            Color(0x5C, 0xCF, 0xE6),
            icon.iconColor,
            "Chip must reflect projectB's accent after second activation",
        )
    }

    @Test
    fun `AccentChangedTopic publish updates chip icon within one EDT cycle`() {
        val project =
            mockk<Project>(relaxed = true) {
                every { isDisposed } returns false
                every { isDefault } returns false
            }
        every { AccentApplicator.resolveFocusedProject() } returns project
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returnsMany listOf("#FFCC66", "#DFBFFF")

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()

        assertTrue(accentListenerSlot.isCaptured, "AccentChangeListener must be captured")

        // Fire the publish — publisher signature: project, hex, source.
        // The hex parameter is the [AccentHex] value class — wrap the literal to match.
        accentListenerSlot.captured.accentChanged(project, AccentHex.unsafeOf("#DFBFFF"), AccentResolver.Source.GLOBAL)
        // Handler wraps in SwingUtilities.invokeLater; flush.
        SwingUtilities.invokeAndWait { /* flush */ }

        val icon = chip.icon as ColorIcon
        assertEquals(Color(0xDF, 0xBF, 0xFF), icon.iconColor)
        assertTrue(
            chip.toolTipText.contains("#DFBFFF"),
            "Tooltip must reflect the post-publish hex; got '${chip.toolTipText}'",
        )
    }

    @Test
    fun `chip refresh path does NOT call ProjectManager openProjects (regression lock)`() {
        val project =
            mockk<Project>(relaxed = true) {
                every { isDisposed } returns false
                every { isDefault } returns false
            }
        every { AccentApplicator.resolveFocusedProject() } returns project
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()
        chip.refreshFromFocusedProject()
        accentListenerSlot.captured.accentChanged(project, AccentHex.unsafeOf("#FFCC66"), AccentResolver.Source.GLOBAL)
        SwingUtilities.invokeAndWait { /* flush */ }
        activationListenerSlot.captured.applicationActivated(mockk(relaxed = true))

        verify(exactly = 0) { mockProjectManager.openProjects }
    }
}
