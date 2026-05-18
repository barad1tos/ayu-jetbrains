package dev.ayuislands.accent.toolbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the chip's render, lifecycle, and mouse-routing contracts (Plan 48-02 Task 2).
 *
 * The chip is a [javax.swing.JLabel] + [Disposable]; its `addNotify` opens a single
 * [MessageBusConnection] (Pattern E — per-instance parent) and subscribes to BOTH
 * [AccentChangedTopic.TOPIC] and [ApplicationActivationListener.TOPIC]. `removeNotify`
 * and `dispose` disconnect and null the connection. `mousePressed` left-routes to the
 * popup and right-routes to a Wave 3 TODO.
 *
 * Mirrors `AccentChangedPublishTest`'s [mockkStatic]([ApplicationManager.Companion])
 * harness so the suite runs headless without booting the IntelliJ application.
 */
class QuickSwitcherChipComponentTest {
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockMessageBus = mockk<MessageBus>(relaxed = true)
    private val mockConnection = mockk<MessageBusConnection>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.messageBus } returns mockMessageBus
        every { mockMessageBus.connect(any<Disposable>()) } returns mockConnection
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `preferredSize uses JBUI scaled CHIP_BOX_PX`() {
        stubAyuActive(true)
        val chip = QuickSwitcherChipComponent()
        val boxScaled = JBUI.scale(QuickSwitcherChipComponent.CHIP_BOX_PX)
        val expected = Dimension(boxScaled, boxScaled)
        assertEquals(expected, chip.preferredSize)
    }

    @Test
    fun `initial icon is a ColorIcon`() {
        stubAyuActive(true)
        val chip = QuickSwitcherChipComponent()
        assertTrue(chip.icon is ColorIcon, "Initial icon must be a ColorIcon; got ${chip.icon?.javaClass}")
    }

    @Test
    fun `refreshFromFocusedProject updates icon color and tooltip`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.refreshFromFocusedProject()

        val icon = chip.icon as ColorIcon
        assertEquals(Color(0xFF, 0xCC, 0x66), icon.iconColor)
        assertEquals("#FFCC66 — Global", chip.toolTipText)
    }

    @Test
    fun `refreshFromFocusedProject surfaces tooltip source label for project override`() {
        stubAyuActive(true)
        stubResolver(hex = "#5CCFE6", source = AccentResolver.Source.PROJECT_OVERRIDE)

        val chip = QuickSwitcherChipComponent()
        chip.refreshFromFocusedProject()

        assertEquals("#5CCFE6 — Project override", chip.toolTipText)
    }

    @Test
    fun `refreshFromFocusedProject swallows RuntimeException and preserves chip paintability`() {
        stubAyuActive(true)
        mockkObject(AccentResolver)
        every {
            AccentResolver.resolve(any(), any())
        } throws RuntimeException("transient mid-LAF-swap")
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null

        val chip = QuickSwitcherChipComponent()
        val originalIcon = chip.icon
        // Must NOT throw — Pattern B catch.
        chip.refreshFromFocusedProject()
        // Icon stays unchanged; the previous frame remains paintable.
        assertEquals(originalIcon, chip.icon)
    }

    @Test
    fun `refreshFromFocusedProject is a no-op when AyuVariant detect returns null`() {
        stubAyuActive(false)
        val chip = QuickSwitcherChipComponent()
        val initialIcon = chip.icon
        val initialTooltip = chip.toolTipText

        chip.refreshFromFocusedProject()

        assertEquals(initialIcon, chip.icon, "Icon must not change when LAF is non-Ayu (WIDGET-11)")
        assertEquals(initialTooltip, chip.toolTipText)
    }

    @Test
    fun `addNotify opens exactly one MessageBusConnection with chip as parent`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()

        verify(exactly = 1) { mockMessageBus.connect(chip as Disposable) }
    }

    @Test
    fun `addNotify subscribes to AccentChangedTopic and ApplicationActivationListener (Pattern E)`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()

        verify(exactly = 1) { mockConnection.subscribe(eq(AccentChangedTopic.TOPIC), any()) }
        verify(exactly = 1) { mockConnection.subscribe(eq(ApplicationActivationListener.TOPIC), any()) }
    }

    @Test
    fun `addNotify is idempotent — second invocation does not re-subscribe`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()
        chip.addNotify()

        // Only one connect call across the two addNotify invocations — the
        // `if (connection != null) return` early-return per RESEARCH §7.
        verify(exactly = 1) { mockMessageBus.connect(chip as Disposable) }
    }

    @Test
    fun `removeNotify disconnects and nulls the connection`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()
        chip.removeNotify()

        verify(atLeast = 1) { mockConnection.disconnect() }
        // After removeNotify, addNotify can subscribe again — proves connection is null.
        chip.addNotify()
        verify(exactly = 2) { mockMessageBus.connect(chip as Disposable) }
    }

    @Test
    fun `dispose disconnects the connection`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()
        chip.dispose()

        verify(atLeast = 1) { mockConnection.disconnect() }
    }

    @Test
    fun `mousePressed left-click invokes QuickSwitcherPopup show with chip self-arg`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)
        mockkObject(QuickSwitcherPopup)
        every { QuickSwitcherPopup.show(any(), any()) } returns Unit

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(leftClick(chip))

        // Wave 7: chip passes itself as the second arg so the popup can wire a
        // per-popup JBPopupListener that flips popup-attached state.
        verify(exactly = 1) { QuickSwitcherPopup.show(chip, chip) }
    }

    @Test
    fun `mousePressed right-click does NOT invoke popup (RMB routes to context menu — Wave 3)`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)
        mockkObject(QuickSwitcherPopup)
        every { QuickSwitcherPopup.show(any(), any()) } returns Unit
        // Plan 48-03 Wave 3 routes RMB to ActionManager.createActionPopupMenu — stub it so
        // dispatch through `mousePressed` does not NPE. The "RMB does NOT invoke popup"
        // assertion stays — the popup is for LMB only. Detailed RMB→context-menu
        // assertions live in `QuickSwitcherChipContextMenuTest`.
        val mockActionManager = mockk<com.intellij.openapi.actionSystem.ActionManager>(relaxed = true)
        val mockMenu = mockk<com.intellij.openapi.actionSystem.ActionPopupMenu>(relaxed = true)
        every { mockActionManager.createActionPopupMenu(any(), any()) } returns mockMenu
        every { mockMenu.component } returns mockk(relaxed = true)
        mockkStatic(com.intellij.openapi.actionSystem.ActionManager::class)
        every {
            com.intellij.openapi.actionSystem.ActionManager
                .getInstance()
        } returns mockActionManager

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(rightClick(chip))

        verify(exactly = 0) { QuickSwitcherPopup.show(any(), any()) }
    }

    @Test
    fun `mousePressed is a no-op when AyuVariant detect returns null (WIDGET-11 belt-and-braces)`() {
        stubAyuActive(false)
        mockkObject(QuickSwitcherPopup)
        every { QuickSwitcherPopup.show(any(), any()) } returns Unit

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(leftClick(chip))
        chip.dispatchEvent(rightClick(chip))

        verify(exactly = 0) { QuickSwitcherPopup.show(any(), any()) }
    }

    @Test
    fun `CHIP_BOX_PX equals 13 and icon fills full cell (WIDGET-02 closure)`() {
        assertEquals(13, QuickSwitcherChipComponent.CHIP_BOX_PX)
        // Wave 7 follow-up: icon is sized to the full cell (`CHIP_BOX_PX`), not an
        // inner-disc inset, so it does not look small against the platform's
        // pressed/hover highlight that paints around the cell. The earlier
        // `CHIP_SWATCH_PX = 12` inner-disc constant was removed.
        val source = Files.readString(Paths.get(CHIP_SOURCE_PATH))
        assertTrue(
            source.contains("ColorIcon(JBUI.scale(CHIP_BOX_PX)"),
            "Chip ColorIcon must size to CHIP_BOX_PX (full cell), not an inner-disc constant",
        )
        assertEquals(
            0,
            "CHIP_SWATCH_PX".toRegex().findAll(source).count(),
            "Inner-disc constant CHIP_SWATCH_PX must be fully removed",
        )
    }

    @Test
    fun `Wave 7 setPopupAttached toggles ring state on the chip`() {
        stubAyuActive(true)
        val chip = QuickSwitcherChipComponent()
        assertFalse(chip.isPopupAttached, "Fresh chip must start with no popup attached")
        chip.setPopupAttached(true)
        assertTrue(chip.isPopupAttached)
        chip.setPopupAttached(false)
        assertFalse(chip.isPopupAttached)
    }

    @Test
    fun `Wave 7 chip ColorIcon constructor uses 3-arg border-on form`() {
        val source = Files.readString(Paths.get(CHIP_SOURCE_PATH))
        // Locate every `ColorIcon(` call site; count those whose closing arg is the
        // boolean `true` (border-on overload).
        val matches =
            "ColorIcon\\([^\\n]*?,\\s*true\\)".toRegex().findAll(source).count()
        assertTrue(matches >= 1, "Expected ≥1 3-arg ColorIcon(..., true) call, got $matches")
    }

    @Test
    fun `Wave 7 JBPopupListener is registered on the popup not on chip Disposable (Pattern E)`() {
        val popupSource = Files.readString(Paths.get(POPUP_SOURCE_PATH))
        assertTrue(
            popupSource.contains("popup.addListener"),
            "Popup must register JBPopupListener via popup.addListener (auto-disposes with popup)",
        )
        val chipSource = Files.readString(Paths.get(CHIP_SOURCE_PATH))
        assertFalse(
            chipSource.contains("Disposer.register"),
            "Chip must NOT wire any Disposer.register for the popup listener (Pattern E discipline)",
        )
    }

    @Test
    fun `Wave 7 setPopupAttached calls from JBPopupListener wrap SwingUtilities invokeLater (T-48-07-03)`() {
        val popupSource = Files.readString(Paths.get(POPUP_SOURCE_PATH))
        val listenerBlock = popupSource.substringAfter("object : JBPopupListener", "")
        assertTrue(
            listenerBlock.contains("SwingUtilities.invokeLater"),
            "Chip setPopupAttached invocation from JBPopupListener must hop to EDT (T-48-07-03 mitigation)",
        )
    }

    @Test
    fun `connection field starts null on a fresh chip`() {
        stubAyuActive(true)
        val chip = QuickSwitcherChipComponent()
        val field = QuickSwitcherChipComponent::class.java.getDeclaredField("connection")
        field.isAccessible = true
        assertNull(field.get(chip), "Fresh chip must have null connection until addNotify")
    }

    private fun stubAyuActive(active: Boolean) {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.isAyuActive() } returns active
        every { AyuVariant.detect() } returns if (active) AyuVariant.MIRAGE else null
    }

    private fun stubResolver(
        hex: String,
        source: AccentResolver.Source,
    ) {
        mockkObject(AccentApplicator)
        val mockProject = mockk<Project>(relaxed = true)
        every { AccentApplicator.resolveFocusedProject() } returns mockProject
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any()) } returns hex
        every { AccentResolver.source(any()) } returns source
        every { AccentResolver.sourceLabel(AccentResolver.Source.PROJECT_OVERRIDE) } returns "Project override"
        every { AccentResolver.sourceLabel(AccentResolver.Source.LANGUAGE_OVERRIDE) } returns "Language override"
        every { AccentResolver.sourceLabel(AccentResolver.Source.GLOBAL) } returns "Global"
    }

    private fun leftClick(source: javax.swing.JComponent) =
        MouseEvent(
            source,
            MouseEvent.MOUSE_PRESSED,
            0L,
            0,
            1,
            1,
            1,
            false,
            MouseEvent.BUTTON1,
        )

    private fun rightClick(source: javax.swing.JComponent) =
        MouseEvent(
            source,
            MouseEvent.MOUSE_PRESSED,
            0L,
            MouseEvent.BUTTON3_DOWN_MASK,
            1,
            1,
            1,
            false,
            MouseEvent.BUTTON3,
        )

    private companion object {
        const val CHIP_SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherChipComponent.kt"
        const val POPUP_SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherPopup.kt"
    }
}
