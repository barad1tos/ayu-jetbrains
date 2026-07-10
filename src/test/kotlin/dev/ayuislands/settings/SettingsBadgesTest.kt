package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.panel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import java.awt.Component
import java.awt.Container
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
        unmockkAll()
        SettingsBadges.clearSessionWiring()
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
    fun `acknowledging a tab retires its directly visible anchors only`() {
        val state = updatedState()

        SettingsBadges.acknowledgeTab(state, "Plugins")

        assertFalse(SettingsBadges.isPending(state, "chrome-tint-external-themes"))
        assertTrue(SettingsBadges.isPending(state, "glow-placement"))
        assertTrue(SettingsBadges.isPending(state, "accent-from-project-icon"))
    }

    @Test
    fun `a tab visit does not retire an anchor hidden behind a collapsed group`() {
        val state = updatedState()
        SettingsBadges.registerGroupExpanded("glow-placement") { false }

        SettingsBadges.acknowledgeTab(state, "Glow")

        assertTrue(
            SettingsBadges.isPending(state, "glow-placement"),
            "a collapsed spoiler hides its anchor — visiting the tab must not count as seeing it",
        )
    }

    @Test
    fun `a tab visit retires a grouped anchor whose group is expanded`() {
        val state = updatedState()
        SettingsBadges.registerGroupExpanded("glow-placement") { true }

        SettingsBadges.acknowledgeTab(state, "Glow")

        assertFalse(SettingsBadges.isPending(state, "glow-placement"))
    }

    @Test
    fun `a tab visit retires a grouped anchor when its group never got built`() {
        // Stub tab or hidden section: no supplier registered — the spoiler
        // cannot gate the anchor, so the badge must not become unreachable.
        val state = updatedState()

        SettingsBadges.acknowledgeTab(state, "Glow")

        assertFalse(SettingsBadges.isPending(state, "glow-placement"))
    }

    @Test
    fun `clearing session wiring drops suppliers and the refresh hook`() {
        val state = updatedState()
        var refreshed = false
        SettingsBadges.registerGroupExpanded("glow-placement") { false }
        SettingsBadges.onBadgesChanged = { refreshed = true }

        SettingsBadges.clearSessionWiring()

        SettingsBadges.acknowledgeAnchor(state, "chrome-tint-external-themes")
        assertFalse(refreshed, "cleared refresh hook must not fire for a closed dialog")
        SettingsBadges.acknowledgeTab(state, "Glow")
        assertFalse(
            SettingsBadges.isPending(state, "glow-placement"),
            "cleared supplier must fall back to visit-acknowledgement",
        )
    }

    @Test
    fun `acknowledging an anchor directly retires it and fires the refresh hook`() {
        val state = updatedState()
        var refreshed = false
        SettingsBadges.onBadgesChanged = { refreshed = true }

        SettingsBadges.acknowledgeAnchor(state, "accent-from-project-icon")

        assertFalse(SettingsBadges.isPending(state, "accent-from-project-icon"))
        assertTrue(refreshed, "panel-side acknowledgement must refresh the badge view live")
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
    fun `selecting a badged tab acknowledges visible anchors and clears the dot live`() {
        val state = updatedState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        val titles = listOf("Plugins", "Glow")
        val tabs = JBTabbedPane()
        for (title in titles) tabs.addTab(title, JPanel())
        val defaultPluginsTabComponent = tabs.getTabComponentAt(0)
        val defaultGlowTabComponent = tabs.getTabComponentAt(1)
        SettingsBadges.registerGroupExpanded("glow-placement") { false }

        val controller = installSettingsBadges(tabs, titles, Color.ORANGE)

        assertNotNull(controller, "pending anchors must produce a controller")
        // The initially selected tab (Plugins) counts as seen immediately.
        assertFalse(SettingsBadges.isPending(state, "chrome-tint-external-themes"))
        assertSame(
            defaultPluginsTabComponent,
            tabs.getTabComponentAt(0),
            "acknowledged tab must restore the platform tab component",
        )
        assertNotSame(
            defaultGlowTabComponent,
            tabs.getTabComponentAt(1),
            "Glow must carry a badge component while pending",
        )

        tabs.selectedIndex = 1
        assertTrue(
            SettingsBadges.isPending(state, "glow-placement"),
            "visiting Glow must not retire the anchor hidden in the collapsed Targets group",
        )
        assertNotSame(
            defaultGlowTabComponent,
            tabs.getTabComponentAt(1),
            "dot stays while the spoiler hides the new setting",
        )
        assertTrue(controller.headerVisible.get(), "header stays while anything is pending")

        // Panel-side acknowledgement (spoiler expanded) refreshes the dots live.
        SettingsBadges.acknowledgeAnchor(state, "glow-placement")
        assertSame(
            defaultGlowTabComponent,
            tabs.getTabComponentAt(1),
            "expanding the group must clear the tab dot through the refresh hook",
        )
        assertTrue(
            controller.headerVisible.get(),
            "header stays while the Accent anchor pends on a tab outside this pane",
        )

        SettingsBadges.acknowledgeAnchor(state, "accent-from-project-icon")
        assertFalse(controller.headerVisible.get(), "header retires with the last pending anchor")
    }

    @Test
    fun `group title carries an accent dot while its anchor pends and restores after`() {
        val state = updatedState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        mockHeadlessApplicationForUiDsl()

        lateinit var overrides: CollapsibleRow
        val accentTab =
            panel {
                overrides =
                    collapsibleGroup("Overrides") {
                        row { label("project icon accent toggle lives here") }
                    }
            }
        overrides.expanded = false
        overrides.bindNewSettingBadge("accent-from-project-icon")
        val tabs = JBTabbedPane()
        tabs.addTab("Accent", accentTab)

        val originalBorder = findSeparator(accentTab, "Overrides")?.label?.border

        installSettingsBadges(tabs, listOf("Accent"), Color.ORANGE)

        assertTrue(
            SettingsBadges.isPending(state, "accent-from-project-icon"),
            "collapsed spoiler must keep its anchor pending through the initial tab visit",
        )
        val separator = findSeparator(accentTab, "Overrides")
        assertNotNull(separator, "collapsible group title separator must be discoverable")
        assertEquals(
            true,
            separator.label.getClientProperty("ayu.newSettingsDotMarker"),
            "pending grouped anchor must decorate the spoiler title with a dot border",
        )
        assertNotSame(originalBorder, separator.label.border, "dot border must wrap the original")

        // Expanding the spoiler acknowledges the anchor and restores the plain title.
        overrides.expanded = true
        assertFalse(SettingsBadges.isPending(state, "accent-from-project-icon"))
        assertNull(separator.label.getClientProperty("ayu.newSettingsDotMarker"))
        assertSame(originalBorder, separator.label.border, "acknowledged title must restore its border")
    }

    // Kotlin UI DSL touches ActionManager and ExperimentalUI during panel
    // construction; headless tests satisfy it the same way EffectsPanelTest does.
    private fun mockHeadlessApplicationForUiDsl() {
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { actionManagerMock.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUiMock = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { appMock.getService(experimentalUiClass) } returns experimentalUiMock
    }

    private fun findSeparator(
        root: Container,
        title: String,
    ): TitledSeparator? {
        val queue = ArrayDeque<Component>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current is TitledSeparator && current.text == title) return current
            if (current is Container) queue.addAll(current.components)
        }
        return null
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
