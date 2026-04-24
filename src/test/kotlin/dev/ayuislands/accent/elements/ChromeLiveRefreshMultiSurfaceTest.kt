package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeDecorationsProbe
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.ClassFqn
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
    private val stockBase = Color(0x2A, 0x2F, 0x3A)

    @BeforeTest
    fun setUp() {
        mockkStatic(UIManager::class)
        every { UIManager.put(any<String>(), any()) } returns Unit

        mockkObject(ChromeBaseColors)
        every { ChromeBaseColors.get(any()) } returns stockBase

        mockkObject(ChromeTintBlender)
        every { ChromeTintBlender.blend(any(), any<Color>(), any()) } returns blended

        mockkObject(WcagForeground)
        every { WcagForeground.pickForeground(any(), any()) } returns Color.WHITE

        mockkObject(ChromeDecorationsProbe)
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true

        mockkObject(LiveChromeRefresher)
        every { LiveChromeRefresher.refresh(any(), any()) } returns Unit
        every { LiveChromeRefresher.clear(any()) } returns Unit

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

    private val navBarTarget =
        ChromeTarget.ByClassName(ClassFqn.require("com.intellij.platform.navbar.frontend.MyNavBarWrapperPanel"))
    private val stripeTarget = ChromeTarget.ByClassName(ClassFqn.require("com.intellij.toolWindow.Stripe"))
    private val mainToolbarTarget =
        ChromeTarget.ByClassName(ClassFqn.require("com.intellij.openapi.wm.impl.headertoolbar.MainToolbar"))
    private val panelBorderTarget =
        ChromeTarget.ByClassNameInside(
            target = ClassFqn.require("com.intellij.openapi.ui.OnePixelDivider"),
            ancestor = ClassFqn.require("com.intellij.toolWindow.InternalDecoratorImpl"),
        )

    @Test
    fun `applying all five chrome elements routes each peer refresh exactly once`() {
        StatusBarElement().apply(testAccent)
        NavBarElement().apply(testAccent)
        ToolWindowStripeElement().apply(testAccent)
        MainToolbarElement().apply(testAccent)
        PanelBorderElement().apply(testAccent)

        verify(exactly = 1) { LiveChromeRefresher.refresh(ChromeTarget.StatusBar, blended) }
        verify(exactly = 1) { LiveChromeRefresher.refresh(navBarTarget, blended) }
        verify(exactly = 1) { LiveChromeRefresher.refresh(stripeTarget, blended) }
        verify(exactly = 1) { LiveChromeRefresher.refresh(mainToolbarTarget, blended) }
        // Round 2 A-1: PanelBorder uses the ancestor-scoped variant to avoid
        // over-tinting OnePixelDivider instances outside tool windows.
        verify(exactly = 1) { LiveChromeRefresher.refresh(panelBorderTarget, blended) }
        // Guard: PanelBorder must NOT use the blind ByClassName target.
        verify(exactly = 0) {
            LiveChromeRefresher.refresh(
                ChromeTarget.ByClassName(ClassFqn.require("com.intellij.openapi.ui.OnePixelDivider")),
                any(),
            )
        }
        // No cross-talk: no element should be invoking clear during apply.
        verify(exactly = 0) { LiveChromeRefresher.clear(any()) }
    }

    @Test
    fun `reverting all five chrome elements routes each peer clear exactly once`() {
        StatusBarElement().revert()
        NavBarElement().revert()
        ToolWindowStripeElement().revert()
        MainToolbarElement().revert()
        PanelBorderElement().revert()

        verify(exactly = 1) { LiveChromeRefresher.clear(ChromeTarget.StatusBar) }
        verify(exactly = 1) { LiveChromeRefresher.clear(navBarTarget) }
        verify(exactly = 1) { LiveChromeRefresher.clear(stripeTarget) }
        verify(exactly = 1) { LiveChromeRefresher.clear(mainToolbarTarget) }
        verify(exactly = 1) { LiveChromeRefresher.clear(panelBorderTarget) }
        verify(exactly = 0) {
            LiveChromeRefresher.clear(
                ChromeTarget.ByClassName(ClassFqn.require("com.intellij.openapi.ui.OnePixelDivider")),
            )
        }
        verify(exactly = 0) { LiveChromeRefresher.refresh(any(), any()) }
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

        val targets =
            listOf(ChromeTarget.StatusBar, navBarTarget, stripeTarget, mainToolbarTarget, panelBorderTarget)
        for (target in targets) {
            verify(exactly = 1) { LiveChromeRefresher.refresh(target, blended) }
            verify(exactly = 1) { LiveChromeRefresher.clear(target) }
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
        verify(exactly = 1) { LiveChromeRefresher.refresh(ChromeTarget.StatusBar, blended) }
        verify(exactly = 1) { LiveChromeRefresher.refresh(navBarTarget, blended) }
        verify(exactly = 1) { LiveChromeRefresher.refresh(stripeTarget, blended) }
        verify(exactly = 0) { LiveChromeRefresher.refresh(mainToolbarTarget, any()) }
        // PanelBorder ancestor-scoped refresh still fires (Round 2 A-1) — MainToolbar gate
        // short-circuits MainToolbar only, not sibling peers.
        verify(exactly = 1) { LiveChromeRefresher.refresh(panelBorderTarget, blended) }
    }
}
