package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.ChromeDecorationsProbe
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Integration-style cross-surface test — simulates the AccentApplicator apply/revert
 * pass going through all 5 chrome elements and verifies each one routes its tinted
 * background (or clear signal) through [LiveChromeRefresher] exactly once with the
 * correct peer identifier (class-name string OR direct StatusBar API).
 *
 * Covers the multi-surface invariant: no cross-talk between elements, no
 * duplicate/missed peer refresh, D-14 clear symmetry across every surface.
 */
class ChromeLiveRefreshMultiSurfaceTest {
    private lateinit var mockSettings: AyuIslandsSettings
    private lateinit var mockState: AyuIslandsState
    private lateinit var mockApplication: Application

    private val testAccent = Color(0xE6, 0xB4, 0x50)
    private val blended = Color(0x22, 0x33, 0x44)

    @BeforeTest
    fun setUp() {
        mockkStatic(UIManager::class)
        every { UIManager.put(any<String>(), any()) } returns Unit

        mockkObject(ChromeTintBlender)
        every { ChromeTintBlender.blend(any(), any(), any()) } returns blended

        mockkObject(WcagForeground)
        every { WcagForeground.pickForeground(any(), any()) } returns Color.WHITE

        mockkObject(ChromeDecorationsProbe)
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true

        mockkObject(LiveChromeRefresher)
        every { LiveChromeRefresher.refreshStatusBar(any()) } returns Unit
        every { LiveChromeRefresher.clearStatusBar() } returns Unit
        every { LiveChromeRefresher.refreshByClassName(any(), any()) } returns Unit
        every { LiveChromeRefresher.clearByClassName(any()) } returns Unit

        mockState = AyuIslandsState().apply { chromeTintIntensity = 40 }
        mockSettings = mockk(relaxed = true)
        every { mockSettings.state } returns mockState

        mockApplication = mockk(relaxed = true)
        every { mockApplication.getService(AyuIslandsSettings::class.java) } returns mockSettings
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `applying all five chrome elements routes each peer refresh exactly once`() {
        StatusBarElement().apply(testAccent)
        NavBarElement().apply(testAccent)
        ToolWindowStripeElement().apply(testAccent)
        MainToolbarElement().apply(testAccent)
        PanelBorderElement().apply(testAccent)

        // StatusBar uses its own public-API path (not class-name).
        verify(exactly = 1) { LiveChromeRefresher.refreshStatusBar(blended) }
        // The remaining four use class-name string match with distinct FQNs.
        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassName(
                "com.intellij.platform.navbar.frontend.MyNavBarWrapperPanel",
                blended,
            )
        }
        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassName("com.intellij.toolWindow.Stripe", blended)
        }
        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassName(
                "com.intellij.openapi.wm.impl.headertoolbar.MainToolbar",
                blended,
            )
        }
        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassName("com.intellij.openapi.ui.OnePixelDivider", blended)
        }

        // No cross-talk: no element should be invoking the clear path during apply.
        verify(exactly = 0) { LiveChromeRefresher.clearStatusBar() }
        verify(exactly = 0) { LiveChromeRefresher.clearByClassName(any()) }
    }

    @Test
    fun `reverting all five chrome elements routes each peer clear exactly once`() {
        StatusBarElement().revert()
        NavBarElement().revert()
        ToolWindowStripeElement().revert()
        MainToolbarElement().revert()
        PanelBorderElement().revert()

        verify(exactly = 1) { LiveChromeRefresher.clearStatusBar() }
        verify(exactly = 1) {
            LiveChromeRefresher.clearByClassName("com.intellij.platform.navbar.frontend.MyNavBarWrapperPanel")
        }
        verify(exactly = 1) { LiveChromeRefresher.clearByClassName("com.intellij.toolWindow.Stripe") }
        verify(exactly = 1) {
            LiveChromeRefresher.clearByClassName("com.intellij.openapi.wm.impl.headertoolbar.MainToolbar")
        }
        verify(exactly = 1) { LiveChromeRefresher.clearByClassName("com.intellij.openapi.ui.OnePixelDivider") }

        // No cross-talk: revert must not invoke any refresh path.
        verify(exactly = 0) { LiveChromeRefresher.refreshStatusBar(any()) }
        verify(exactly = 0) { LiveChromeRefresher.refreshByClassName(any(), any()) }
    }

    @Test
    fun `apply then revert cycle across all five surfaces pairs each refresh with its clear`() {
        val elements =
            listOf(
                StatusBarElement(),
                NavBarElement(),
                ToolWindowStripeElement(),
                MainToolbarElement(),
                PanelBorderElement(),
            )
        elements.forEach { it.apply(testAccent) }
        elements.forEach { it.revert() }

        // StatusBar pair
        verify(exactly = 1) { LiveChromeRefresher.refreshStatusBar(blended) }
        verify(exactly = 1) { LiveChromeRefresher.clearStatusBar() }

        // Four class-name pairs
        val peerClasses =
            listOf(
                "com.intellij.platform.navbar.frontend.MyNavBarWrapperPanel",
                "com.intellij.toolWindow.Stripe",
                "com.intellij.openapi.wm.impl.headertoolbar.MainToolbar",
                "com.intellij.openapi.ui.OnePixelDivider",
            )
        for (fqn in peerClasses) {
            verify(exactly = 1) { LiveChromeRefresher.refreshByClassName(fqn, blended) }
            verify(exactly = 1) { LiveChromeRefresher.clearByClassName(fqn) }
        }
    }

    @Test
    fun `MainToolbar respects probe gate inside cross-surface flow`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false

        StatusBarElement().apply(testAccent)
        NavBarElement().apply(testAccent)
        ToolWindowStripeElement().apply(testAccent)
        MainToolbarElement().apply(testAccent)
        PanelBorderElement().apply(testAccent)

        // MainToolbar short-circuits — all other peers still refresh.
        verify(exactly = 1) { LiveChromeRefresher.refreshStatusBar(blended) }
        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassName(
                "com.intellij.platform.navbar.frontend.MyNavBarWrapperPanel",
                blended,
            )
        }
        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassName("com.intellij.toolWindow.Stripe", blended)
        }
        verify(exactly = 0) {
            LiveChromeRefresher.refreshByClassName(
                "com.intellij.openapi.wm.impl.headertoolbar.MainToolbar",
                any(),
            )
        }
        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassName("com.intellij.openapi.ui.OnePixelDivider", blended)
        }
    }
}
