package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.ClassFqn
import dev.ayuislands.accent.LiveChromeRefresher
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * User-space + algorithmic coverage for [PanelBorderElement] per CHROME-05.
 *
 * Two invariants lock this element in addition to the standard apply/revert shape:
 * 1. `OnePixelDivider.background` is NEVER in `uiKeys` (AccentApplicator owns it).
 * 2. `uiKeys` has no overlap with [AccentApplicator.ALWAYS_ON_UI_KEYS] — double
 *    writers on the same UIManager key would make revert ambiguous (whose null wins).
 */
class PanelBorderElementTest {
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

        mockkObject(LiveChromeRefresher)
        every { LiveChromeRefresher.refreshByClassName(any(), any()) } returns Unit
        every { LiveChromeRefresher.clearByClassName(any()) } returns Unit
        every {
            LiveChromeRefresher.refreshByClassNameInsideAncestorClass(any(), any(), any())
        } returns Unit
        every {
            LiveChromeRefresher.clearByClassNameInsideAncestorClass(any(), any())
        } returns Unit
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply writes blended color to both ToolWindow border keys`() {
        state.chromeTintIntensity = 30

        PanelBorderElement().apply(accent)

        verify { UIManager.put("ToolWindow.Header.borderColor", blended) }
        verify { UIManager.put("ToolWindow.borderColor", blended) }
    }

    @Test
    fun `revert nulls both ToolWindow border keys`() {
        PanelBorderElement().revert()

        verify { UIManager.put("ToolWindow.Header.borderColor", null) }
        verify { UIManager.put("ToolWindow.borderColor", null) }
    }

    @Test
    fun `apply passes intensity from state to blender per D-03`() {
        state.chromeTintIntensity = 45

        val intensitySlot = slot<Int>()
        every {
            ChromeTintBlender.blend(any<Color>(), any<Color>(), capture(intensitySlot))
        } returns blended

        PanelBorderElement().apply(accent)

        assertTrue(intensitySlot.isCaptured)
        assertEquals(45, intensitySlot.captured)
    }

    @Test
    fun `revert symmetry — every key apply writes is nulled on revert`() {
        state.chromeTintIntensity = 30

        val appliedKeys = mutableListOf<String>()
        every {
            UIManager.put(capture(appliedKeys), any<Any>())
        } returns null

        val element = PanelBorderElement()
        element.apply(accent)
        val writtenDuringApply = appliedKeys.toSet()
        appliedKeys.clear()

        element.revert()
        val writtenDuringRevert = appliedKeys.toSet()

        assertTrue(writtenDuringApply.isNotEmpty(), "apply should have written at least one key")
        assertEquals(
            writtenDuringApply,
            writtenDuringRevert,
            "revert must null exactly the keys apply wrote",
        )
    }

    @Test
    fun `id and displayName match the CHROME registry entry`() {
        val element = PanelBorderElement()

        assertEquals(AccentElementId.PANEL_BORDER, element.id)
        assertEquals("Panel borders", element.displayName)
    }

    @Test
    fun `uiKeys never contains OnePixelDivider-background — AccentApplicator owns it`() {
        val element = PanelBorderElement()

        assertFalse(
            "OnePixelDivider.background" in element.uiKeys,
            "OnePixelDivider.background is owned by AccentApplicator.ALWAYS_ON_UI_KEYS; " +
                "including it here would cause revert ambiguity (whose null wins)",
        )
    }

    @Test
    fun `uiKeys has no overlap with AccentApplicator ALWAYS_ON_UI_KEYS`() {
        val element = PanelBorderElement()
        val alwaysOn = readAlwaysOnUiKeys()
        val overlap = element.uiKeys.toSet() intersect alwaysOn.toSet()

        assertTrue(
            overlap.isEmpty(),
            "PanelBorderElement.uiKeys must not overlap with ALWAYS_ON_UI_KEYS; found: $overlap",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readAlwaysOnUiKeys(): List<String> {
        val field = AccentApplicator::class.java.getDeclaredField("ALWAYS_ON_UI_KEYS")
        field.isAccessible = true
        return field.get(AccentApplicator) as List<String>
    }

    @Test
    fun `apply invokes ancestor-scoped refresh for OnePixelDivider inside tool-window decorator (Round 2 A-1)`() {
        state.chromeTintIntensity = 30

        PanelBorderElement().apply(accent)

        verify(exactly = 1) {
            LiveChromeRefresher.refreshByClassNameInsideAncestorClass(
                ClassFqn.require("com.intellij.openapi.ui.OnePixelDivider"),
                ClassFqn.require("com.intellij.toolWindow.InternalDecoratorImpl"),
                blended,
            )
        }
        verify(exactly = 0) { LiveChromeRefresher.clearByClassNameInsideAncestorClass(any(), any()) }
    }

    @Test
    fun `apply must NOT invoke blind refreshByClassName (would over-tint IDE-wide dividers) (Round 2 A-1)`() {
        state.chromeTintIntensity = 30

        PanelBorderElement().apply(accent)

        // Blind refresh would walk Window.getWindows() and paint every OnePixelDivider in
        // the IDE — editor splitters, Settings dialog, diff gutter, Run/Debug splitter, etc.
        verify(exactly = 0) { LiveChromeRefresher.refreshByClassName(any(), any()) }
    }

    @Test
    fun `revert invokes ancestor-scoped clear for OnePixelDivider inside tool-window decorator (D-14 symmetry)`() {
        PanelBorderElement().revert()

        verify(exactly = 1) {
            LiveChromeRefresher.clearByClassNameInsideAncestorClass(
                ClassFqn.require("com.intellij.openapi.ui.OnePixelDivider"),
                ClassFqn.require("com.intellij.toolWindow.InternalDecoratorImpl"),
            )
        }
        verify(exactly = 0) { LiveChromeRefresher.refreshByClassNameInsideAncestorClass(any(), any(), any()) }
        verify(exactly = 0) { LiveChromeRefresher.clearByClassName(any()) }
    }
}
