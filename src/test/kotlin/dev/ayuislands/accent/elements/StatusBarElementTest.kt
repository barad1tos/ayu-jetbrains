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
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User-space + algorithmic coverage for [StatusBarElement] per CHROME-01 / CHROME-07.
 *
 * Verifies that `apply` routes every background key through [ChromeTintBlender.blend],
 * that the foreground-contrast branch always fires (the legacy
 * `chromeTintKeepForegroundReadable` toggle was retired — WCAG contrast is
 * always-on), that intensity is pulled from state per D-03 (not from the
 * `apply` signature), and that `revert` nulls every touched UIManager key so
 * the LAF re-resolves the stock theme value.
 */
class StatusBarElementTest {
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()

    private val accent = Color(255, 0, 0)
    private val blended = Color(0x12, 0x34, 0x56)
    private val stockBase = Color(0x2A, 0x2F, 0x3A)

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        mockkStatic(UIManager::class)
        every { UIManager.put(any<String>(), any()) } returns null

        mockkObject(ChromeBaseColors)
        every { ChromeBaseColors.get(any()) } returns stockBase

        mockkObject(ChromeTintBlender)
        every { ChromeTintBlender.blend(any(), any<Color>(), any()) } returns blended

        mockkObject(WcagForeground)
        every { WcagForeground.pickForeground(any(), any()) } returns Color.GREEN

        mockkObject(LiveChromeRefresher)
        every { LiveChromeRefresher.refreshStatusBar(any()) } returns Unit
        every { LiveChromeRefresher.clearStatusBar() } returns Unit
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply writes blended color to every background key`() {
        state.chromeTintIntensity = 40

        StatusBarElement().apply(accent)

        verify { UIManager.put("StatusBar.background", blended) }
        verify { UIManager.put("StatusBar.borderColor", blended) }
        verify { UIManager.put("StatusBar.Widget.hoverBackground", blended) }
    }

    @Test
    fun `revert nulls every touched UIManager key`() {
        StatusBarElement().revert()

        verify { UIManager.put("StatusBar.background", null) }
        verify { UIManager.put("StatusBar.borderColor", null) }
        verify { UIManager.put("StatusBar.Widget.hoverBackground", null) }
        verify { UIManager.put("StatusBar.Widget.foreground", null) }
        verify { UIManager.put("StatusBar.Widget.hoverForeground", null) }
        // D-14 symmetry for breadcrumbs foreground fix — every new key written on
        // apply must be nulled on revert so the LAF re-resolves the stock value.
        verify { UIManager.put("StatusBar.Breadcrumbs.foreground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.hoverForeground", null) }
    }

    @Test
    fun `apply always writes WcagForeground pick to both foreground keys`() {
        state.chromeTintIntensity = 40

        StatusBarElement().apply(accent)

        verify { WcagForeground.pickForeground(blended, WcagForeground.TextTarget.PRIMARY_TEXT) }
        verify { UIManager.put("StatusBar.Widget.foreground", Color.GREEN) }
        verify { UIManager.put("StatusBar.Widget.hoverForeground", Color.GREEN) }
    }

    @Test
    fun `apply extends WcagForeground pick to breadcrumb foreground keys present in 2026_1`() {
        // Regression guard: path breadcrumbs ("project > build.gradle.kts") in the
        // status bar used to stay LAF-grey against a tinted background. The fix
        // re-uses the same contrast color the rest of the status bar widget text
        // already receives, so breadcrumbs are legible at any supported tint.
        //
        // Only keys present in IntelliJPlatform.themeMetadata.json for platformVersion
        // 2026.1 are asserted here — see StatusBarElement.foregroundKeys for the
        // javap-verified presence list.
        state.chromeTintIntensity = 40

        StatusBarElement().apply(accent)

        verify(exactly = 1) { UIManager.put("StatusBar.Breadcrumbs.foreground", Color.GREEN) }
        verify(exactly = 1) { UIManager.put("StatusBar.Breadcrumbs.hoverForeground", Color.GREEN) }
    }

    @Test
    fun `apply uses PRIMARY_TEXT target not ICON for status bar widget text`() {
        state.chromeTintIntensity = 40

        StatusBarElement().apply(accent)

        // StatusBar.Widget.foreground + StatusBar.Widget.hoverForeground share the
        // same tinted sample, so pickForeground is invoked once per fg key at minimum.
        verify(atLeast = 1) {
            WcagForeground.pickForeground(any(), WcagForeground.TextTarget.PRIMARY_TEXT)
        }
        verify(exactly = 0) {
            WcagForeground.pickForeground(any(), WcagForeground.TextTarget.ICON)
        }
        verify(exactly = 0) {
            WcagForeground.pickForeground(any(), WcagForeground.TextTarget.SECONDARY_TEXT)
        }
    }

    @Test
    fun `apply passes intensity from state to blender per D-03`() {
        state.chromeTintIntensity = 37

        val intensitySlot = slot<Int>()
        every {
            ChromeTintBlender.blend(any<Color>(), any<Color>(), capture(intensitySlot))
        } returns blended

        StatusBarElement().apply(accent)

        assertTrue(intensitySlot.isCaptured, "intensity should be forwarded to blender")
        assertEquals(37, intensitySlot.captured, "element must read intensity from state, not from apply()")
    }

    @Test
    fun `revert symmetry — every key apply writes is nulled on revert`() {
        state.chromeTintIntensity = 50

        val appliedKeys = mutableListOf<String>()
        every {
            UIManager.put(capture(appliedKeys), any<Any>())
        } returns null

        val element = StatusBarElement()
        element.apply(accent)
        val writtenDuringApply = appliedKeys.toSet()
        appliedKeys.clear()

        element.revert()
        val writtenDuringRevert = appliedKeys.toSet()

        assertTrue(
            writtenDuringApply.isNotEmpty(),
            "apply should have written at least one key",
        )
        assertTrue(
            writtenDuringApply.all { it in writtenDuringRevert },
            "every key apply writes must be nulled on revert; missing: ${writtenDuringApply - writtenDuringRevert}",
        )
    }

    @Test
    fun `id and displayName match the CHROME registry entry`() {
        val element = StatusBarElement()

        assertEquals(AccentElementId.STATUS_BAR, element.id)
        assertEquals("Status bar", element.displayName)
    }

    @Test
    fun `apply invokes LiveChromeRefresher refreshStatusBar once with tinted background (Gap 4)`() {
        state.chromeTintIntensity = 40

        StatusBarElement().apply(accent)

        verify(exactly = 1) { LiveChromeRefresher.refreshStatusBar(blended) }
        verify(exactly = 0) { LiveChromeRefresher.clearStatusBar() }
    }

    @Test
    fun `revert invokes LiveChromeRefresher clearStatusBar once (D-14 symmetry)`() {
        StatusBarElement().revert()

        verify(exactly = 1) { LiveChromeRefresher.clearStatusBar() }
        verify(exactly = 0) { LiveChromeRefresher.refreshStatusBar(any()) }
    }
}
