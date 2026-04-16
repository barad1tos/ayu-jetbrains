package dev.ayuislands.whatsnew

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LoggedErrorProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShowWhatsNewActionTest {
    private val action = ShowWhatsNewAction()
    private val presentation = Presentation()
    private val event = mockk<AnActionEvent>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val descriptor = mockk<IdeaPluginDescriptor>()

    @BeforeTest
    fun setUp() {
        mockkStatic(PluginManagerCore::class)
        every { event.presentation } returns presentation
        every { event.project } returns project
    }

    @AfterTest
    fun tearDown() {
        // Reset the companion-object one-shot latch between tests — otherwise
        // a test that trips the null-descriptor path could swallow the WARN in
        // a subsequent test case within the same JVM.
        ShowWhatsNewAction.resetForTesting()
        unmockkAll()
    }

    @Test
    fun `update enables the action when manifest exists for current version`() {
        // Test resources include /whatsnew/v9.9.0/manifest.json — when the
        // plugin descriptor reports that version, the action must be visible
        // and enabled in the Tools menu.
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns descriptor
        every { descriptor.version } returns "9.9.0"

        action.update(event)

        assertTrue(presentation.isEnabled, "action must be enabled when manifest exists")
        assertTrue(presentation.isVisible, "action must be visible when manifest exists")
    }

    @Test
    fun `update disables the action when no manifest for current version`() {
        // Patch releases or any version without a manifest hide the menu item —
        // better than a dead-clickable item that opens an empty tab.
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns descriptor
        every { descriptor.version } returns "0.0.0"

        action.update(event)

        assertFalse(presentation.isEnabled)
        assertFalse(presentation.isVisible)
    }

    @Test
    fun `update disables the action when plugin descriptor is missing`() {
        // Defense in depth — plugin classloader registry could theoretically
        // not list us during early startup or under PluginManager edge cases.
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null

        action.update(event)

        assertFalse(presentation.isEnabled)
    }

    @Test
    fun `actionPerformed delegates to launcher`() {
        // The action wrapper holds no business logic; it just forwards to the
        // launcher, which handles the in-session orchestrator pick + manifest
        // lookup. Verify the delegation contract.
        mockkObject(WhatsNewLauncher)
        every { WhatsNewLauncher.openManually(project) } returns true

        action.actionPerformed(event)

        verify(exactly = 1) { WhatsNewLauncher.openManually(project) }
    }

    @Test
    fun `actionPerformed is a no-op when event has no project`() {
        // Tools menu may dispatch with a null project in extreme cases (welcome
        // screen, before any project loads). Don't NPE — just bail out.
        mockkObject(WhatsNewLauncher)
        every { event.project } returns null

        action.actionPerformed(event)

        verify(exactly = 0) { WhatsNewLauncher.openManually(any()) }
    }

    @Test
    fun `actionPerformed does NOT escalate to WARN when launcher declines due to missing manifest`() {
        // Defense-in-depth path: update() should have hidden the menu when no
        // manifest exists, but BGT update can race with the click. If the user
        // hits Show What's New… and openManually returns false (manifest gone),
        // we leave an INFO breadcrumb — but NOT a WARN, since this is an
        // expected race, not an anomaly.
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns descriptor
        every { descriptor.version } returns "0.0.0"
        mockkObject(WhatsNewLauncher)
        every { WhatsNewLauncher.openManually(project) } returns false

        val captured = mutableListOf<String>()
        LoggedErrorProcessor.executeWith<RuntimeException>(
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    captured += "WARN: $message"
                    return false
                }
            },
        ) {
            action.actionPerformed(event)
        }
        verify(exactly = 1) { WhatsNewLauncher.openManually(project) }
        assertTrue(
            captured.isEmpty(),
            "manifest-missing race is INFO-level, must NOT escalate to WARN; got: $captured",
        )
    }

    @Test
    fun `update logs WARN exactly once per null-descriptor streak then re-arms on recovery`() {
        // The one-shot latch must emit a WARN on the FIRST null-descriptor
        // observation, then stay silent through subsequent null-observations,
        // and then re-arm once the descriptor comes back non-null — so each
        // disable-enable cycle of the plugin produces its own diagnostic.
        val captured = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (message.contains("plugin descriptor lookup returned null")) captured += message
                    return false
                }
            }
        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            // First null observation — expect ONE WARN.
            every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null
            action.update(event)
            action.update(event)
            action.update(event)
            val firstStreak = captured.size
            kotlin.test.assertEquals(1, firstStreak, "streak of null-observations must produce ONE WARN")

            // Descriptor comes back — action re-arms the latch silently.
            every { PluginManagerCore.getPlugin(any<PluginId>()) } returns descriptor
            every { descriptor.version } returns "9.9.0"
            action.update(event)
            kotlin.test.assertEquals(1, captured.size, "recovery must not log WARN")

            // Descriptor goes null again — latch re-armed, expect a SECOND WARN.
            every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null
            action.update(event)
            kotlin.test.assertEquals(2, captured.size, "second null streak must re-arm and log once")
        }
    }

    @Test
    fun `actionPerformed escalates to WARN when descriptor is missing`() {
        // Distinct from the manifest-missing path: descriptor=null means the
        // platform can't find OUR OWN plugin, which is a real anomaly worth
        // surfacing as WARN so a future "menu does nothing" report has a
        // diagnostic that distinguishes "patch release" from "platform broken".
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null
        mockkObject(WhatsNewLauncher)
        every { WhatsNewLauncher.openManually(project) } returns false

        val captured = mutableListOf<String>()
        LoggedErrorProcessor.executeWith<RuntimeException>(
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    captured += message
                    return false
                }
            },
        ) {
            action.actionPerformed(event)
        }
        assertTrue(
            captured.any { it.contains("plugin descriptor missing") },
            "descriptor-null path must produce a WARN naming the descriptor; got: $captured",
        )
    }
}
