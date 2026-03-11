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
}
