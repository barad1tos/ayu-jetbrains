package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentElementId
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
 * User-space + algorithmic coverage for [NavBarElement] per CHROME-04.
 *
 * Phase 40-10 (Gap 2) — the legacy "no foreground contract" invariant is
 * retired; the NavBar now writes `NavBar.foreground` and
 * `NavBar.selectedItemForeground` via [WcagForeground.pickForeground] under
 * the contrast gate. Tests here lock both the new fg writes and the
 * revert-symmetry for those new keys.
 */
class NavBarElementTest {
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()

    private val accent = Color(255, 0, 0)
    private val blended = Color(0x12, 0x34, 0x56)
    private val contrastFg = Color.GREEN

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        mockkStatic(UIManager::class)
        every { UIManager.put(any<String>(), any()) } returns null

        mockkObject(ChromeTintBlender)
        every { ChromeTintBlender.blend(any(), any(), any()) } returns blended

        mockkObject(WcagForeground)
        every { WcagForeground.pickForeground(any(), any()) } returns contrastFg

        mockkObject(LiveChromeRefresher)
        every { LiveChromeRefresher.refreshByClassName(any(), any()) } returns Unit
        every { LiveChromeRefresher.clearByClassName(any()) } returns Unit
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply writes blended color to NavBar background and border`() {
        state.chromeTintIntensity = 30
        state.chromeTintKeepForegroundReadable = false

        NavBarElement().apply(accent)

        verify { UIManager.put("NavBar.background", blended) }
        verify { UIManager.put("NavBar.borderColor", blended) }
    }

    @Test
    fun `apply with contrast on writes WcagForeground pick to both foreground keys`() {
        state.chromeTintIntensity = 40
        state.chromeTintKeepForegroundReadable = true

        NavBarElement().apply(accent)

        verify { WcagForeground.pickForeground(blended, WcagForeground.TextTarget.PRIMARY_TEXT) }
        verify { UIManager.put("NavBar.foreground", contrastFg) }
        verify { UIManager.put("NavBar.selectedItemForeground", contrastFg) }
    }

    @Test
    fun `apply with contrast off skips every foreground write and pickForeground call`() {
        state.chromeTintIntensity = 40
        state.chromeTintKeepForegroundReadable = false

        NavBarElement().apply(accent)

        verify(exactly = 0) { WcagForeground.pickForeground(any(), any()) }
        verify(exactly = 0) { UIManager.put("NavBar.foreground", any()) }
        verify(exactly = 0) { UIManager.put("NavBar.selectedItemForeground", any()) }
    }

    @Test
    fun `revert nulls both bg keys and both fg keys unconditionally`() {
        NavBarElement().revert()

        verify { UIManager.put("NavBar.background", null) }
        verify { UIManager.put("NavBar.borderColor", null) }
        verify { UIManager.put("NavBar.foreground", null) }
        verify { UIManager.put("NavBar.selectedItemForeground", null) }
    }

    @Test
    fun `apply passes intensity from state to blender per D-03`() {
        state.chromeTintIntensity = 62
        state.chromeTintKeepForegroundReadable = false

        val intensitySlot = slot<Int>()
        every {
            ChromeTintBlender.blend(any<Color>(), any<String>(), capture(intensitySlot))
        } returns blended

        NavBarElement().apply(accent)

        assertTrue(intensitySlot.isCaptured, "intensity must flow from state into blender")
        assertEquals(62, intensitySlot.captured)
    }

    @Test
    fun `apply invokes blender twice per call — once per background key`() {
        state.chromeTintIntensity = 50
        state.chromeTintKeepForegroundReadable = false

        NavBarElement().apply(accent)

        verify(exactly = 1) { ChromeTintBlender.blend(accent, "NavBar.background", 50) }
        verify(exactly = 1) { ChromeTintBlender.blend(accent, "NavBar.borderColor", 50) }
    }

    @Test
    fun `revert symmetry — every key apply can write is nulled on revert`() {
        state.chromeTintIntensity = 40
        state.chromeTintKeepForegroundReadable = true

        val appliedKeys = mutableListOf<String>()
        every {
            UIManager.put(capture(appliedKeys), any<Any>())
        } returns null

        val element = NavBarElement()
        element.apply(accent)
        val writtenDuringApply = appliedKeys.toSet()
        appliedKeys.clear()

        element.revert()
        val writtenDuringRevert = appliedKeys.toSet()

        assertTrue(writtenDuringApply.isNotEmpty(), "apply should have written at least one key")
        assertTrue(
            writtenDuringApply.all { it in writtenDuringRevert },
            "every key apply writes must be nulled on revert; missing: ${writtenDuringApply - writtenDuringRevert}",
        )
    }

    @Test
    fun `id and displayName match the CHROME registry entry`() {
        val element = NavBarElement()

        assertEquals(AccentElementId.NAV_BAR, element.id)
        assertEquals("Navigation bar", element.displayName)
    }

    @Test
    fun `apply invokes LiveChromeRefresher refreshByClassName for navbar peer (Gap 4)`() {
        state.chromeTintIntensity = 40
        state.chromeTintKeepForegroundReadable = false

        NavBarElement().apply(accent)

        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassName(
                "com.intellij.platform.navbar.frontend.MyNavBarWrapperPanel",
                blended,
            )
        }
        verify(exactly = 0) { LiveChromeRefresher.clearByClassName(any()) }
    }

    @Test
    fun `revert invokes LiveChromeRefresher clearByClassName for navbar peer (D-14 symmetry)`() {
        NavBarElement().revert()

        verify(exactly = 1) {
            LiveChromeRefresher.clearByClassName("com.intellij.platform.navbar.frontend.MyNavBarWrapperPanel")
        }
        verify(exactly = 0) { LiveChromeRefresher.refreshByClassName(any(), any()) }
    }
}
