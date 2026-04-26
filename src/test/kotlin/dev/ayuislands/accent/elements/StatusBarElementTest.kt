package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.TintIntensity
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
        every { WcagForeground.pickLightFamilyForeground(any(), any()) } returns Color.GREEN

        mockkObject(LiveChromeRefresher)
        every { LiveChromeRefresher.refresh(any(), any()) } returns Unit
        every { LiveChromeRefresher.clear(any()) } returns Unit
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply tints opaque status bar surfaces and clears translucent overlay backgrounds`() {
        state.chromeTintIntensity = 40

        StatusBarElement().apply(accent)

        // Phase 40.3c — root chrome surface
        verify { UIManager.put("StatusBar.background", blended) }
        verify { UIManager.put("StatusBar.borderColor", blended) }
        verify { UIManager.put("StatusBar.Widget.hoverBackground", null) }
        // Phase 40.4 — NavBar Compact (2026.1) state-specific bg keys read by
        // `NavBarItemComponent.highlightColor()` are translucent overlays, not
        // standalone surfaces. The compact panel receives the opaque tint directly;
        // these keys must fall back to the LAF overlay so hover/selection does not
        // become a bright opaque fill that forces dark breadcrumb text.
        verify { UIManager.put("StatusBar.Breadcrumbs.hoverBackground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.selectionBackground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.selectionInactiveBackground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.floatingBackground", null) }
    }

    @Test
    fun `apply still nulls every overlay background when ChromeBaseColors returns null for every key`() {
        // Phase 40.4 contract: the overlay null sweep is unconditional. Even
        // when every opaque base color resolves to null (theme metadata gap,
        // non-Ayu LAF active mid-apply) the overlay keys must be cleared so a
        // previously-applied plugin override cannot leak past a partial apply.
        // Guards against a future refactor that "optimizes" the null sweep
        // behind an `if (tinted.isNotEmpty())` check.
        every { ChromeBaseColors.get(any()) } returns null
        state.chromeTintIntensity = 30

        StatusBarElement().apply(accent)

        verify { UIManager.put("StatusBar.Breadcrumbs.hoverBackground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.selectionBackground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.selectionInactiveBackground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.floatingBackground", null) }
        verify { UIManager.put("StatusBar.Widget.hoverBackground", null) }
        // No opaque tint should have been written since the bases were absent.
        verify(exactly = 0) { UIManager.put("StatusBar.background", blended) }
    }

    @Test
    fun `revert nulls every touched UIManager key`() {
        StatusBarElement().revert()

        // Backgrounds (3 root + 4 Breadcrumbs state) — D-14 symmetry mirror of apply.
        verify { UIManager.put("StatusBar.background", null) }
        verify { UIManager.put("StatusBar.borderColor", null) }
        verify { UIManager.put("StatusBar.Widget.hoverBackground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.hoverBackground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.selectionBackground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.selectionInactiveBackground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.floatingBackground", null) }
        // Foregrounds (2 Widget + 5 Breadcrumbs state) — every fg state the path
        // widget can land in must be nulled so the LAF re-resolves stock when the
        // user disables chrome tinting.
        verify { UIManager.put("StatusBar.Widget.foreground", null) }
        verify { UIManager.put("StatusBar.Widget.hoverForeground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.foreground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.hoverForeground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.floatingForeground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.selectionForeground", null) }
        verify { UIManager.put("StatusBar.Breadcrumbs.selectionInactiveForeground", null) }
    }

    @Test
    fun `apply uses light-family pick for foreground keys (no BLACK)`() {
        state.chromeTintIntensity = 40

        StatusBarElement().apply(accent)

        // Phase 40.4 — status bar fg uses pickLightFamilyForeground (palette
        // [WHITE, DARK_FG], no BLACK) instead of the standard 3-color pick.
        // Standard pickForeground would correctly land on BLACK on mid-luminance
        // tinted bg — visually broken on a chrome surface we own as a dark band.
        verify { WcagForeground.pickLightFamilyForeground(blended, WcagForeground.TextTarget.PRIMARY_TEXT) }
        verify(exactly = 0) {
            WcagForeground.pickForeground(any(), any())
        }
        verify { UIManager.put("StatusBar.Widget.foreground", Color.GREEN) }
        verify { UIManager.put("StatusBar.Widget.hoverForeground", Color.GREEN) }
    }

    @Test
    fun `apply extends light-family pick to all 5 breadcrumb foreground states`() {
        // Phase 40.4 — NavBarItemComponent.update() (intellij 2026.1) cycles
        // through 5 fg states based on hover/selected/focused/floating flags.
        // Every state's UIDefault key must be written so the path widget never
        // falls through to UIUtil.getLabelForeground() when transitioning states.
        state.chromeTintIntensity = 40

        StatusBarElement().apply(accent)

        verify(exactly = 1) { UIManager.put("StatusBar.Breadcrumbs.foreground", Color.GREEN) }
        verify(exactly = 1) { UIManager.put("StatusBar.Breadcrumbs.hoverForeground", Color.GREEN) }
        verify(exactly = 1) { UIManager.put("StatusBar.Breadcrumbs.floatingForeground", Color.GREEN) }
        verify(exactly = 1) { UIManager.put("StatusBar.Breadcrumbs.selectionForeground", Color.GREEN) }
        verify(exactly = 1) { UIManager.put("StatusBar.Breadcrumbs.selectionInactiveForeground", Color.GREEN) }
    }

    @Test
    fun `hover foreground uses root status bar contrast instead of translucent overlay contrast`() {
        val rootTint = Color(0x24, 0x39, 0x44)
        val hoverOverlayTint = Color(0xA8, 0xF0, 0xFF)
        val rootForeground = Color.WHITE
        val overlayForeground = Color(0x1F, 0x24, 0x30)
        state.chromeTintIntensity = 40

        every { ChromeBaseColors.get("StatusBar.background") } returns Color(0x24, 0x29, 0x36)
        every { ChromeBaseColors.get("StatusBar.borderColor") } returns Color(0x24, 0x29, 0x36)
        every { ChromeBaseColors.get("StatusBar.Widget.hoverBackground") } returns Color(0xFF, 0xFF, 0xFF, 0x18)
        every {
            ChromeTintBlender.blend(accent, Color(0x24, 0x29, 0x36), any())
        } returns rootTint
        every {
            ChromeTintBlender.blend(accent, Color(0xFF, 0xFF, 0xFF, 0x18), any())
        } returns hoverOverlayTint
        every {
            WcagForeground.pickLightFamilyForeground(rootTint, WcagForeground.TextTarget.PRIMARY_TEXT)
        } returns rootForeground
        every {
            WcagForeground.pickLightFamilyForeground(hoverOverlayTint, WcagForeground.TextTarget.PRIMARY_TEXT)
        } returns overlayForeground

        StatusBarElement().apply(accent)

        verify { UIManager.put("StatusBar.Widget.hoverForeground", rootForeground) }
        verify { UIManager.put("StatusBar.Breadcrumbs.hoverForeground", rootForeground) }
        verify(exactly = 0) {
            UIManager.put("StatusBar.Widget.hoverForeground", overlayForeground)
        }
        verify(exactly = 0) {
            UIManager.put("StatusBar.Breadcrumbs.hoverForeground", overlayForeground)
        }
    }

    @Test
    fun `apply uses PRIMARY_TEXT target not ICON for status bar widget text`() {
        state.chromeTintIntensity = 40

        StatusBarElement().apply(accent)

        // Light-family pick (Phase 40.4) — same target band as the standard
        // picker, just a restricted palette. PRIMARY_TEXT (4.5:1) is the right
        // floor for path widget breadcrumbs which paint readable-size text.
        verify(atLeast = 1) {
            WcagForeground.pickLightFamilyForeground(any(), WcagForeground.TextTarget.PRIMARY_TEXT)
        }
        verify(exactly = 0) {
            WcagForeground.pickLightFamilyForeground(any(), WcagForeground.TextTarget.ICON)
        }
        verify(exactly = 0) {
            WcagForeground.pickLightFamilyForeground(any(), WcagForeground.TextTarget.SECONDARY_TEXT)
        }
    }

    @Test
    fun `apply passes intensity from state to blender per D-03`() {
        state.chromeTintIntensity = 37

        val intensitySlot = slot<TintIntensity>()
        every {
            ChromeTintBlender.blend(any<Color>(), any<Color>(), capture(intensitySlot))
        } returns blended

        StatusBarElement().apply(accent)

        assertTrue(intensitySlot.isCaptured, "intensity should be forwarded to blender")
        assertEquals(37, intensitySlot.captured.percent, "element must read intensity from state, not from apply()")
    }

    @Test
    fun `revert symmetry - every key apply writes is nulled on revert`() {
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
    fun `apply invokes LiveChromeRefresher once with StatusBar target + tinted background (Gap 4)`() {
        state.chromeTintIntensity = 40

        StatusBarElement().apply(accent)

        verify(exactly = 1) { LiveChromeRefresher.refresh(ChromeTarget.StatusBar, blended) }
        verify(exactly = 0) { LiveChromeRefresher.clear(any()) }
    }

    @Test
    fun `revert invokes LiveChromeRefresher clear with StatusBar target once (D-14 symmetry)`() {
        StatusBarElement().revert()

        verify(exactly = 1) { LiveChromeRefresher.clear(ChromeTarget.StatusBar) }
        verify(exactly = 0) { LiveChromeRefresher.refresh(any(), any()) }
    }
}
