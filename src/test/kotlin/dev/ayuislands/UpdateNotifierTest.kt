package dev.ayuislands

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.whatsnew.WhatsNewLauncher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateNotifierTest {
    private val project = mockk<Project>(relaxed = true)
    private val state = AyuIslandsState()
    private val descriptor = mockk<IdeaPluginDescriptor>()

    @BeforeTest
    fun setUp() {
        mockkStatic(PluginManagerCore::class)
        mockkObject(AyuIslandsSettings.Companion)
        mockkStatic(NotificationGroupManager::class)

        val settings = mockk<AyuIslandsSettings>()
        every { AyuIslandsSettings.getInstance() } returns settings
        every { settings.state } returns state

        // Default: launcher returns false so the balloon path runs as before —
        // keeps every existing test exercising its original code path. Tests
        // that need the tab-supersedes-balloon behavior override this stub.
        mockkObject(WhatsNewLauncher)
        every { WhatsNewLauncher.openIfEligible(any(), any()) } returns false
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `no-op when plugin descriptor not found`() {
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns null

        UpdateNotifier.showIfUpdated(project)

        assertNull(state.lastSeenVersion)
    }

    @Test
    fun `no-op when version already seen`() {
        state.lastSeenVersion = "2.2.0"
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.2.0"

        UpdateNotifier.showIfUpdated(project)

        assertEquals("2.2.0", state.lastSeenVersion)
    }

    @Test
    fun `updates lastSeenVersion on first install without notification`() {
        state.lastSeenVersion = null
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.2.0"

        UpdateNotifier.showIfUpdated(project)

        assertEquals("2.2.0", state.lastSeenVersion)
    }

    @Test
    fun `no notification when no release notes for version`() {
        state.lastSeenVersion = "2.1.0"
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "9.9.9"

        UpdateNotifier.showIfUpdated(project)

        assertEquals("9.9.9", state.lastSeenVersion)
    }

    @Test
    fun `shows notification when upgrading to version with release notes`() {
        state.lastSeenVersion = "2.1.0"
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.2.0"

        val notification = mockk<Notification>(relaxed = true)
        val group = mockk<NotificationGroup>()
        val groupManager = mockk<NotificationGroupManager>()
        every { NotificationGroupManager.getInstance() } returns groupManager
        every { groupManager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(
                any<String>(),
                any<String>(),
                any<NotificationType>(),
            )
        } returns notification
        every { notification.addAction(any()) } returns notification

        UpdateNotifier.showIfUpdated(project)

        assertEquals("2.2.0", state.lastSeenVersion)
        verify {
            group.createNotification(
                "Ayu Islands updated to 2.2.0",
                match<String> { it.contains("font presets") },
                NotificationType.INFORMATION,
            )
        }
        verify { notification.notify(project) }
    }

    @Test
    fun `updates state before checking release notes`() {
        state.lastSeenVersion = "2.0.0"
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.1.0"

        UpdateNotifier.showIfUpdated(project)

        // Version updated even though 2.1.0 has no release notes entry
        assertEquals("2.1.0", state.lastSeenVersion)
    }

    @Test
    fun `repeated startups with same version stay silent`() {
        // lastSeen already matches current — line 21 should short-circuit every call.
        state.lastSeenVersion = "2.4.0"
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.4.0"

        val notification = mockk<Notification>(relaxed = true)
        val group = mockk<NotificationGroup>(relaxed = true)
        val groupManager = mockk<NotificationGroupManager>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns groupManager
        every { groupManager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification

        repeat(5) { UpdateNotifier.showIfUpdated(project) }

        assertEquals("2.4.0", state.lastSeenVersion)
        verify(exactly = 0) { notification.notify(any<Project>()) }
        verify(exactly = 0) {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }

    @Test
    fun `fresh install with release notes for current version stays silent`() {
        // lastSeen == null means first install — state must update but notification must NOT fire,
        // even when release notes exist for the current version.
        state.lastSeenVersion = null
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.4.0"

        val notification = mockk<Notification>(relaxed = true)
        val group = mockk<NotificationGroup>(relaxed = true)
        val groupManager = mockk<NotificationGroupManager>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns groupManager
        every { groupManager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification

        UpdateNotifier.showIfUpdated(project)

        assertEquals("2.4.0", state.lastSeenVersion)
        verify(exactly = 0) { notification.notify(any<Project>()) }
        verify(exactly = 0) {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }

    @Test
    fun `upgrade then subsequent startups show notification exactly once`() {
        // Multi-session regression guard: mirrors the LicenseTransitionListener single-call trap.
        // Session 1 upgrades from 2.3.7 to 2.4.0 — notification fires.
        // Session 2 and 3 read the persisted state — must stay silent.
        state.lastSeenVersion = "2.3.7"
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.4.0"

        val notification = mockk<Notification>(relaxed = true)
        val group = mockk<NotificationGroup>(relaxed = true)
        val groupManager = mockk<NotificationGroupManager>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns groupManager
        every { groupManager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification

        // Session 1: upgrade
        UpdateNotifier.showIfUpdated(project)
        assertEquals("2.4.0", state.lastSeenVersion)

        // Session 2: startup with persisted state
        UpdateNotifier.showIfUpdated(project)

        // Session 3: startup with persisted state
        UpdateNotifier.showIfUpdated(project)

        verify(exactly = 1) { notification.notify(any<Project>()) }
        verify(exactly = 1) {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }

    @Test
    fun `version skip shows release notes for target version only`() {
        // User upgraded 2.3.5 -> 2.4.0 (skipped 2.3.6 and 2.3.7). The notification must describe
        // the target version, not an intermediate one.
        state.lastSeenVersion = "2.3.5"
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.4.0"

        val notification = mockk<Notification>(relaxed = true)
        val group = mockk<NotificationGroup>(relaxed = true)
        val groupManager = mockk<NotificationGroupManager>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns groupManager
        every { groupManager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification

        UpdateNotifier.showIfUpdated(project)

        assertEquals("2.4.0", state.lastSeenVersion)
        verify(exactly = 1) {
            group.createNotification(
                "Ayu Islands updated to 2.4.0",
                match<String> { body ->
                    body.contains("Onboarding wizard") &&
                        !body.contains("tool window width jumping") &&
                        !body.contains("Bug fixes")
                },
                NotificationType.INFORMATION,
            )
        }
        verify(exactly = 1) { notification.notify(project) }
    }

    @Test
    fun `WhatsNew tab supersedes balloon when launcher claims the upgrade`() {
        // When a manifest exists for the upgrade target version, the launcher
        // claims the open and returns true. UpdateNotifier must skip the balloon
        // path entirely — no double-signal to the user.
        state.lastSeenVersion = "2.4.2"
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.5.0"
        every { WhatsNewLauncher.openIfEligible(project, "2.5.0") } returns true

        val notification = mockk<Notification>(relaxed = true)
        val group = mockk<NotificationGroup>(relaxed = true)
        val groupManager = mockk<NotificationGroupManager>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns groupManager
        every { groupManager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification

        UpdateNotifier.showIfUpdated(project)

        // State write still happened (lastSeenVersion is updated before the
        // launcher delegation, preserving multi-window dedup semantics).
        assertEquals("2.5.0", state.lastSeenVersion)
        // Launcher was consulted; balloon was NOT created.
        verify(exactly = 1) { WhatsNewLauncher.openIfEligible(project, "2.5.0") }
        verify(exactly = 0) {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
        verify(exactly = 0) { notification.notify(any<Project>()) }
    }

    @Test
    fun `balloon fallback runs when launcher declines the upgrade`() {
        // No manifest for this version (e.g. patch release like 2.5.1) — launcher
        // returns false, balloon path runs as if the launcher integration didn't
        // exist. Regression guard for breaking the existing balloon flow.
        state.lastSeenVersion = "2.1.0"
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.2.0"
        every { WhatsNewLauncher.openIfEligible(project, "2.2.0") } returns false

        val notification = mockk<Notification>(relaxed = true)
        val group = mockk<NotificationGroup>(relaxed = true)
        val groupManager = mockk<NotificationGroupManager>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns groupManager
        every { groupManager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification

        UpdateNotifier.showIfUpdated(project)

        verify(exactly = 1) { WhatsNewLauncher.openIfEligible(project, "2.2.0") }
        verify(exactly = 1) {
            group.createNotification(
                "Ayu Islands updated to 2.2.0",
                any<String>(),
                NotificationType.INFORMATION,
            )
        }
        verify(exactly = 1) { notification.notify(project) }
    }

    @Test
    fun `version with no release notes updates state but does not notify`() {
        // Current version is not in RELEASE_NOTES — state must update but notification must NOT fire.
        state.lastSeenVersion = "2.3.7"
        every {
            PluginManagerCore.getPlugin(any<PluginId>())
        } returns descriptor
        every { descriptor.version } returns "2.9.99"

        val notification = mockk<Notification>(relaxed = true)
        val group = mockk<NotificationGroup>(relaxed = true)
        val groupManager = mockk<NotificationGroupManager>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns groupManager
        every { groupManager.getNotificationGroup("Ayu Islands") } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification

        UpdateNotifier.showIfUpdated(project)

        assertEquals("2.9.99", state.lastSeenVersion)
        verify(exactly = 0) { notification.notify(any<Project>()) }
        verify(exactly = 0) {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }
}
