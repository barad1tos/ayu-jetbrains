package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.ClassFqn
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.TintIntensity
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
import kotlin.test.assertEquals

/**
 * Tests for [ToolWindowStripeElement] — CHROME-03.
 *
 * Locks:
 *  - 3 UIManager background keys tinted (Stripe.background, Stripe.borderColor, Button.selectedBackground)
 *  - `ToolWindow.Button.selectedBackground` is the javap-verified New UI 2025.1 stripe-button key
 *    (IntelliJPlatform.themeMetadata.json entry present in app-client.jar)
 *  - Intensity sourced from `AyuIslandsSettings.state.chromeTintIntensity`
 *  - Contrast toggle produces optional foreground write on `ToolWindow.Button.selectedForeground`
 *  - revert unconditionally nulls every touched key
 */
class ToolWindowStripeElementTest {
    private lateinit var mockSettings: AyuIslandsSettings
    private lateinit var mockState: AyuIslandsState
    private lateinit var mockApplication: Application

    private val testAccent = Color(0xE6, 0xB4, 0x50)
    private val blended = Color(0x33, 0x44, 0x55)
    private val contrastFg = Color.WHITE
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
        every { WcagForeground.pickForeground(any(), any()) } returns contrastFg

        mockkObject(LiveChromeRefresher)
        every { LiveChromeRefresher.refresh(any(), any()) } returns Unit
        every { LiveChromeRefresher.clear(any()) } returns Unit

        // AyuIslandsSettings.getInstance() is backed by ApplicationManager.getService.
        // Mock the Application so the companion resolves without an IDE container.
        mockState = AyuIslandsState()
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
    fun `metadata id and displayName`() {
        val element = ToolWindowStripeElement()
        assertEquals(AccentElementId.TOOL_WINDOW_STRIPE, element.id)
        assertEquals("Tool window stripe", element.displayName)
    }

    @Test
    fun `apply writes blended color to three stripe background keys`() {
        mockState.chromeTintIntensity = 30

        ToolWindowStripeElement().apply(testAccent)

        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.background", blended) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.borderColor", blended) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Button.selectedBackground", blended) }
    }

    @Test
    fun `apply passes chromeTintIntensity through to blender for every background key`() {
        mockState.chromeTintIntensity = 45

        ToolWindowStripeElement().apply(testAccent)

        // All 3 keys resolve to the stubbed stockBase, so blend is invoked 3× with the same args.
        verify(exactly = 3) { ChromeTintBlender.blend(testAccent, stockBase, TintIntensity.of(45)) }
    }

    @Test
    fun `apply samples WcagForeground ICON independently for each bg (Round 2 A-2)`() {
        mockState.chromeTintIntensity = 40

        ToolWindowStripeElement().apply(testAccent)

        // Round 2 A-2 CRITICAL: stripe bg and selected-button bg are DIFFERENT base colors;
        // fg must be sampled against each bg independently, not reused across both.
        // With the current stub all three bg keys resolve to the same stubbed base+blend,
        // so the two pickForeground calls receive the same input. The important invariant
        // is that pickForeground is called at least TWICE (once per target bg).
        verify(atLeast = 2) {
            WcagForeground.pickForeground(any(), WcagForeground.TextTarget.ICON)
        }
        verify(exactly = 1) { UIManager.put("ToolWindow.Button.selectedForeground", contrastFg) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.foreground", contrastFg) }
    }

    @Test
    fun `apply picks DISTINCT fg colors when stripe bg and selected bg differ (Round 2 A-2)`() {
        mockState.chromeTintIntensity = 40

        // Return different base colors for the two foreground-bearing background keys so the
        // blender produces different tints, then have pickForeground return a distinct fg per
        // tint. This locks the invariant: fg is NOT reused; each bg gets its own contrast pick.
        val stripeBase = Color(0x20, 0x20, 0x20)
        val selectedBase = Color(0x60, 0x60, 0x60)
        val stripeTinted = Color(0x41, 0x41, 0x41)
        val selectedTinted = Color(0x82, 0x82, 0x82)
        val stripeFg = Color(0xEE, 0xEE, 0xEE)
        val selectedFg = Color(0x11, 0x11, 0x11)

        every { ChromeBaseColors.get("ToolWindow.Stripe.background") } returns stripeBase
        every { ChromeBaseColors.get("ToolWindow.Button.selectedBackground") } returns selectedBase
        every { ChromeBaseColors.get("ToolWindow.Stripe.borderColor") } returns stockBase
        every { ChromeTintBlender.blend(testAccent, stripeBase, TintIntensity.of(40)) } returns stripeTinted
        every { ChromeTintBlender.blend(testAccent, selectedBase, TintIntensity.of(40)) } returns selectedTinted
        every { ChromeTintBlender.blend(testAccent, stockBase, TintIntensity.of(40)) } returns blended
        every {
            WcagForeground.pickForeground(stripeTinted, WcagForeground.TextTarget.ICON)
        } returns stripeFg
        every {
            WcagForeground.pickForeground(selectedTinted, WcagForeground.TextTarget.ICON)
        } returns selectedFg

        ToolWindowStripeElement().apply(testAccent)

        verify(exactly = 1) {
            WcagForeground.pickForeground(stripeTinted, WcagForeground.TextTarget.ICON)
        }
        verify(exactly = 1) {
            WcagForeground.pickForeground(selectedTinted, WcagForeground.TextTarget.ICON)
        }
        // The two writes diverge — stripe fg is NOT reused for the selected button.
        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.foreground", stripeFg) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Button.selectedForeground", selectedFg) }
    }

    @Test
    fun `apply uses ICON target not PRIMARY_TEXT for stripe buttons`() {
        mockState.chromeTintIntensity = 40

        ToolWindowStripeElement().apply(testAccent)

        verify(exactly = 0) {
            WcagForeground.pickForeground(any(), WcagForeground.TextTarget.PRIMARY_TEXT)
        }
        verify(exactly = 0) {
            WcagForeground.pickForeground(any(), WcagForeground.TextTarget.SECONDARY_TEXT)
        }
    }

    @Test
    fun `revert nulls every key the element can write`() {
        ToolWindowStripeElement().revert()

        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.background", null) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.borderColor", null) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Button.selectedBackground", null) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Button.selectedForeground", null) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.foreground", null) }
    }

    @Test
    fun `apply then revert cleans every key touched by apply`() {
        mockState.chromeTintIntensity = 40

        val element = ToolWindowStripeElement()
        element.apply(testAccent)
        element.revert()

        // revert() hits every key (including every optional foreground one) unconditionally
        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.background", null) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.borderColor", null) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Button.selectedBackground", null) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Button.selectedForeground", null) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.foreground", null) }
    }

    @Test
    fun `apply invokes LiveChromeRefresher for stripe peer (Gap 4)`() {
        mockState.chromeTintIntensity = 30

        ToolWindowStripeElement().apply(testAccent)

        val expectedTarget = ChromeTarget.ByClassName(ClassFqn.require("com.intellij.toolWindow.Stripe"))
        verify(exactly = 1) { LiveChromeRefresher.refresh(expectedTarget, blended) }
        verify(exactly = 0) { LiveChromeRefresher.clear(any()) }
    }

    @Test
    fun `revert invokes LiveChromeRefresher clear for stripe peer (D-14 symmetry)`() {
        ToolWindowStripeElement().revert()

        val expectedTarget = ChromeTarget.ByClassName(ClassFqn.require("com.intellij.toolWindow.Stripe"))
        verify(exactly = 1) { LiveChromeRefresher.clear(expectedTarget) }
        verify(exactly = 0) { LiveChromeRefresher.refresh(any(), any()) }
    }
}
