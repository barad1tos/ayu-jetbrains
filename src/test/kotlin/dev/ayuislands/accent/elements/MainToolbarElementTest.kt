package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests for [MainToolbarElement] — CHROME-02.
 *
 * Locks:
 *  - Probe gate on `apply` (no UIManager writes when JBR custom decorations inactive — D-13)
 *  - Unconditional `revert` regardless of probe (cleans up if probe flips between calls)
 *  - Forbidden-key invariant: never touches `MainToolbar.Dropdown.transparentHoverBackground`
 *    (intentional translucency) or any `RecentProject.Color*.MainToolbarGradient*` key
 *    (per-project IntelliJ gradient palette)
 *  - Intensity sourced from `AyuIslandsSettings.state.chromeTintIntensity`
 *  - Optional contrast-foreground write on `MainToolbar.foreground`
 *  - `MainToolbar.Icon.foreground` is NEVER written — javap-verified absent from
 *    platformVersion 2026.1 metadata + lib JAR string tables (drop-icon decision
 *    on plan 40-10). The regression guard below makes a future platform addition
 *    fail loudly so it must be opted-in explicitly rather than silently activated.
 *
 * Key scope is limited to javap-verified platform 2025.1 UIManager keys:
 * `MainToolbar.background` is registered in `IntelliJPlatform.themeMetadata.json`
 * (since 2022.3) and `MainToolbar.foreground` is a live string literal inside
 * `app-client.jar`. `MainToolbar.borderColor` was NOT found in platform metadata
 * or JAR string tables at 2025.1 — see 40-06-SUMMARY "Deviation from PLAN".
 */
class MainToolbarElementTest {
    private lateinit var mockSettings: AyuIslandsSettings
    private lateinit var mockState: AyuIslandsState
    private lateinit var mockApplication: Application

    private val testAccent = Color(0xE6, 0xB4, 0x50)
    private val blended = Color(0x22, 0x33, 0x44)
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

        mockkObject(ChromeDecorationsProbe)
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true

        mockkObject(LiveChromeRefresher)
        every { LiveChromeRefresher.refreshByClassName(any(), any()) } returns Unit
        every { LiveChromeRefresher.clearByClassName(any()) } returns Unit

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
        val element = MainToolbarElement()
        assertEquals(AccentElementId.MAIN_TOOLBAR, element.id)
        assertEquals("Main toolbar", element.displayName)
    }

    @Test
    fun `apply with probe active writes blended color to MainToolbar background`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true
        mockState.chromeTintIntensity = 30

        MainToolbarElement().apply(testAccent)

        verify(exactly = 1) { UIManager.put("MainToolbar.background", blended) }
    }

    @Test
    fun `apply short-circuits when probe reports native chrome — no UIManager writes`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        mockState.chromeTintIntensity = 40

        MainToolbarElement().apply(testAccent)

        // Silent no-op at element level per D-13
        verify(exactly = 0) { UIManager.put(any<String>(), any()) }
        verify(exactly = 0) { ChromeTintBlender.blend(any(), any(), any()) }
        verify(exactly = 0) { WcagForeground.pickForeground(any(), any()) }
    }

    @Test
    fun `apply always writes WcagForeground PRIMARY_TEXT to MainToolbar foreground`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true
        mockState.chromeTintIntensity = 40

        MainToolbarElement().apply(testAccent)

        verify(exactly = 1) {
            WcagForeground.pickForeground(blended, WcagForeground.TextTarget.PRIMARY_TEXT)
        }
        verify(exactly = 1) { UIManager.put("MainToolbar.foreground", contrastFg) }
    }

    @Test
    fun `revert nulls MainToolbar background and foreground unconditionally even when probe returns false`() {
        // Simulate probe flipping between apply and revert: probe OFF during revert,
        // but the keys we might have written earlier still get cleaned up.
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false

        MainToolbarElement().revert()

        verify(exactly = 1) { UIManager.put("MainToolbar.background", null) }
        verify(exactly = 1) { UIManager.put("MainToolbar.foreground", null) }
    }

    @Test
    fun `MainToolbar Icon foreground is never written — drop-icon guard for 2026 1 platform`() {
        // Regression guard: MainToolbar.Icon.foreground is absent from platform
        // 2026.1 metadata + lib/*.jar string tables (javap-verified). If a future
        // platform release adds the key and someone naively turns it on, this
        // assertion fails loudly and forces an explicit opt-in rather than a
        // silent activation. Covers BOTH apply and revert paths.
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true
        mockState.chromeTintIntensity = 80

        val element = MainToolbarElement()
        element.apply(testAccent)
        element.revert()

        verify(exactly = 0) { UIManager.put("MainToolbar.Icon.foreground", any()) }
    }

    @Test
    fun `apply passes chromeTintIntensity through to blender`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true
        mockState.chromeTintIntensity = 45

        MainToolbarElement().apply(testAccent)

        verify(exactly = 1) { ChromeTintBlender.blend(testAccent, stockBase, 45) }
    }

    @Test
    fun `apply invokes LiveChromeRefresher refreshByClassName for MainToolbar peer when probe active (Gap 4)`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true
        mockState.chromeTintIntensity = 30

        MainToolbarElement().apply(testAccent)

        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassName(
                "com.intellij.openapi.wm.impl.headertoolbar.MainToolbar",
                blended,
            )
        }
        verify(exactly = 0) { LiveChromeRefresher.clearByClassName(any()) }
    }

    @Test
    fun `apply skips LiveChromeRefresher when probe reports native chrome (D-13 gate)`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        mockState.chromeTintIntensity = 30

        MainToolbarElement().apply(testAccent)

        verify(exactly = 0) { LiveChromeRefresher.refreshByClassName(any(), any()) }
    }

    @Test
    fun `revert invokes LiveChromeRefresher clearByClassName unconditionally (D-14 symmetry)`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false

        MainToolbarElement().revert()

        verify(exactly = 1) {
            LiveChromeRefresher.clearByClassName("com.intellij.openapi.wm.impl.headertoolbar.MainToolbar")
        }
    }

    @Test
    fun `apply never touches Dropdown translucent key nor RecentProject gradient keys`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true
        mockState.chromeTintIntensity = 80

        // Capture every UIManager.put invocation and assert none of them hit the forbidden namespace.
        val writtenKeys = mutableListOf<String>()
        every { UIManager.put(capture(writtenKeys), any()) } returns Unit

        MainToolbarElement().apply(testAccent)

        for (key in writtenKeys) {
            assertFalse(
                key == "MainToolbar.Dropdown.transparentHoverBackground",
                "MainToolbarElement must not tint the intentional translucent dropdown hover",
            )
            assertFalse(
                key.startsWith("RecentProject.Color"),
                "MainToolbarElement must not tint per-project gradient palette keys (got $key)",
            )
        }
    }
}
