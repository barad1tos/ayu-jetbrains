package dev.ayuislands.settings

import com.intellij.ui.components.JBTabbedPane
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.awt.Color
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Decay-model locks for [SettingsBadges]: seeing is acknowledging, fresh
 * installs are exempt, the registry bounds the persisted set, and the
 * lifetime cap retires everything.
 */
class SettingsBadgesTest {
    @AfterTest
    fun tearDown() {
        unmockkObject(AyuIslandsSettings.Companion)
    }

    private fun updatedState(): AyuIslandsState = AyuIslandsState().apply { lastSeenVersion = "2.8.0" }

    @Test
    fun `fresh install has nothing pending even with an empty acknowledged set`() {
        val state = AyuIslandsState()

        assertTrue(SettingsBadges.pendingAnchors(state).isEmpty())
    }

    @Test
    fun `updated install pends every registry anchor until acknowledged`() {
        val state = updatedState()

        assertEquals(
            SettingsBadges.registry.map { it.id },
            SettingsBadges.pendingAnchors(state).map { it.id },
        )
    }

    @Test
    fun `acknowledging a tab retires exactly that tab's anchors`() {
        val state = updatedState()

        SettingsBadges.acknowledgeTab(state, "Glow")

        assertFalse(SettingsBadges.isPending(state, "glow-placement"))
        assertTrue(SettingsBadges.isPending(state, "chrome-tint-external-themes"))
        assertTrue(SettingsBadges.isPending(state, "accent-from-project-icon"))
    }

    @Test
    fun `seeding acknowledges everything`() {
        val state = updatedState()

        SettingsBadges.seedAllAcknowledged(state)

        assertTrue(SettingsBadges.pendingAnchors(state).isEmpty())
    }

    @Test
    fun `pruning drops ids that left the registry`() {
        val state = updatedState()
        state.acknowledgedSettingsBadges.add("retired-feature-id")
        state.acknowledgedSettingsBadges.add("glow-placement")

        SettingsBadges.pruneStaleIds(state)

        assertEquals(setOf("glow-placement"), state.acknowledgedSettingsBadges.toSet())
    }

    @Test
    fun `expiry cap acknowledges everything pending once due`() {
        val state = updatedState()

        SettingsBadges.armExpiry(state, nowMs = 1_000L)
        assertEquals(1_000L + SettingsBadges.BADGE_LIFETIME_MS, state.settingsBadgesExpireAtMs)

        SettingsBadges.expireIfDue(state, nowMs = 1_000L + SettingsBadges.BADGE_LIFETIME_MS - 1)
        assertTrue(SettingsBadges.pendingAnchors(state).isNotEmpty(), "cap must not fire early")

        SettingsBadges.expireIfDue(state, nowMs = 1_000L + SettingsBadges.BADGE_LIFETIME_MS)
        assertTrue(SettingsBadges.pendingAnchors(state).isEmpty())
        assertEquals(0L, state.settingsBadgesExpireAtMs)
    }

    @Test
    fun `expiry does not arm when nothing is pending`() {
        val state = updatedState()
        SettingsBadges.seedAllAcknowledged(state)

        SettingsBadges.armExpiry(state, nowMs = 5L)

        assertEquals(0L, state.settingsBadgesExpireAtMs)
    }

    @Test
    fun `selecting a badged tab acknowledges its anchors and clears the dot live`() {
        val state = updatedState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        val titles = listOf("Accent", "Glow", "Plugins")
        val tabs = JBTabbedPane()
        for (title in titles) tabs.addTab(title, JPanel())
        val defaultGlowTabComponent = tabs.getTabComponentAt(1)

        val controller = installSettingsBadges(tabs, titles, Color.ORANGE)

        assertNotNull(controller, "pending anchors must produce a controller")
        // The initially selected tab (Accent) counts as seen immediately.
        assertFalse(SettingsBadges.isPending(state, "accent-from-project-icon"))
        assertNotSame(
            defaultGlowTabComponent,
            tabs.getTabComponentAt(1),
            "Glow must carry a badge component while pending",
        )

        tabs.selectedIndex = 1
        assertFalse(SettingsBadges.isPending(state, "glow-placement"))
        assertSame(
            defaultGlowTabComponent,
            tabs.getTabComponentAt(1),
            "visited tab must restore the platform tab component",
        )
        assertTrue(controller.headerVisible.get(), "header stays while Plugins is pending")

        tabs.selectedIndex = 2
        assertFalse(SettingsBadges.isPending(state, "chrome-tint-external-themes"))
        assertFalse(controller.headerVisible.get(), "header retires with the last pending tab")
    }

    @Test
    fun `nothing pending yields no controller and no dots`() {
        val state = updatedState()
        SettingsBadges.seedAllAcknowledged(state)
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        val tabs = JBTabbedPane()
        tabs.addTab("Accent", JPanel())
        val defaultTabComponent = tabs.getTabComponentAt(0)

        assertNull(installSettingsBadges(tabs, listOf("Accent"), Color.ORANGE))
        assertSame(defaultTabComponent, tabs.getTabComponentAt(0), "platform tab component must stay untouched")
    }
}
