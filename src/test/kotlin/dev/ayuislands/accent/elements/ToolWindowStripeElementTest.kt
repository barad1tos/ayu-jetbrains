package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
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
        every { LiveChromeRefresher.refreshByClassName(any(), any()) } returns Unit
        every { LiveChromeRefresher.clearByClassName(any()) } returns Unit

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
        mockState.chromeTintIntensity = 55

        ToolWindowStripeElement().apply(testAccent)

        // All 3 keys resolve to the stubbed stockBase, so blend is invoked 3× with the same args.
        verify(exactly = 3) { ChromeTintBlender.blend(testAccent, stockBase, 55) }
    }

    @Test
    fun `apply always writes WcagForeground ICON pick to both foreground keys`() {
        mockState.chromeTintIntensity = 40

        ToolWindowStripeElement().apply(testAccent)

        verify(atLeast = 1) {
            WcagForeground.pickForeground(blended, WcagForeground.TextTarget.ICON)
        }
        verify(exactly = 1) { UIManager.put("ToolWindow.Button.selectedForeground", contrastFg) }
        verify(exactly = 1) { UIManager.put("ToolWindow.Stripe.foreground", contrastFg) }
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
    fun `apply invokes LiveChromeRefresher refreshByClassName for stripe peer (Gap 4)`() {
        mockState.chromeTintIntensity = 30

        ToolWindowStripeElement().apply(testAccent)

        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassName("com.intellij.toolWindow.Stripe", blended)
        }
        verify(exactly = 0) { LiveChromeRefresher.clearByClassName(any()) }
    }

    @Test
    fun `revert invokes LiveChromeRefresher clearByClassName for stripe peer (D-14 symmetry)`() {
        ToolWindowStripeElement().revert()

        verify(exactly = 1) { LiveChromeRefresher.clearByClassName("com.intellij.toolWindow.Stripe") }
        verify(exactly = 0) { LiveChromeRefresher.refreshByClassName(any(), any()) }
    }
}
