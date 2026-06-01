package dev.ayuislands.accent.toolbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the chip's render, lifecycle, and mouse-routing contracts.
 *
 * The chip is a [javax.swing.JLabel] + [Disposable]; its `addNotify` opens a single
 * [MessageBusConnection] (Pattern E - per-instance parent) and subscribes to BOTH
 * [AccentChangedTopic.TOPIC] and [ApplicationActivationListener.TOPIC]. `removeNotify`
 * and `dispose` disconnect and null the connection. `mousePressed` left-routes to the
 * popup; right-click routes to the context menu.
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
        assertEquals(13, QuickSwitcherChipComponent.CHIP_BOX_PX)
        val chip = QuickSwitcherChipComponent()
        val boxScaled = JBUI.scale(QuickSwitcherChipComponent.CHIP_BOX_PX)
        val expected = Dimension(boxScaled, boxScaled)
        assertEquals(expected, chip.preferredSize)
        assertEquals(boxScaled, chip.icon.iconWidth, "Chip icon must fill the full preferred cell width")
        assertEquals(boxScaled, chip.icon.iconHeight, "Chip icon must fill the full preferred cell height")
    }

    @Test
    fun `initial icon is a LayeredAccentIcon (idle placeholder, unpinned)`() {
        stubAyuActive(true)
        val chip = QuickSwitcherChipComponent()
        val icon = chip.icon
        assertTrue(icon is LayeredAccentIcon, "Initial icon must be a LayeredAccentIcon; got ${icon?.javaClass}")
        // Smart-cast carries `icon` as `LayeredAccentIcon` through the next
        // assertion - no explicit cast needed.
        assertFalse(
            icon.isPinned,
            "Pre-resolve placeholder must render as unpinned (hollow inner)",
        )
    }

    @Test
    fun `refreshFromFocusedProject updates icon color and tooltip (GLOBAL = unpinned)`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.refreshFromFocusedProject()

        val icon = chip.icon as LayeredAccentIcon
        assertEquals(Color(0xFF, 0xCC, 0x66), icon.accentColor)
        assertFalse(icon.isPinned, "GLOBAL source must render the inner island as hollow (unpinned)")
        assertEquals("#FFCC66 \u2014 Global", chip.toolTipText)
    }

    @Test
    fun `refreshFromFocusedProject renders PROJECT_OVERRIDE as a pinned LayeredAccentIcon`() {
        stubAyuActive(true)
        stubResolver(hex = "#5CCFE6", source = AccentResolver.Source.PROJECT_OVERRIDE)

        val chip = QuickSwitcherChipComponent()
        chip.refreshFromFocusedProject()

        val icon = chip.icon as LayeredAccentIcon
        assertTrue(
            icon.isPinned,
            "PROJECT_OVERRIDE source must render the inner island as filled (pinned)",
        )
        assertEquals("#5CCFE6 \u2014 Project override", chip.toolTipText)
    }

    @Test
    fun `refreshFromFocusedProject renders LANGUAGE_OVERRIDE as unpinned LayeredAccentIcon`() {
        // Language overrides count as "no project pin" - the inner-island
        // indicator is project-scoped, not language-scoped.
        stubAyuActive(true)
        stubResolver(hex = "#73D0FF", source = AccentResolver.Source.LANGUAGE_OVERRIDE)

        val chip = QuickSwitcherChipComponent()
        chip.refreshFromFocusedProject()

        val icon = chip.icon as LayeredAccentIcon
        assertFalse(
            icon.isPinned,
            "LANGUAGE_OVERRIDE must render the inner island as hollow (no project-specific pin)",
        )
    }

    @Test
    fun `refreshFromFocusedProject swallows RuntimeException and preserves chip paintability`() {
        stubAyuActive(true)
        mockkObject(AccentResolver)
        every {
            AccentResolver.resolve(any(), any<AyuVariant>())
        } throws RuntimeException("transient mid-LAF-swap")
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null

        val chip = QuickSwitcherChipComponent()
        val originalIcon = chip.icon
        // Must NOT throw - Pattern B catch.
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

        assertEquals(initialIcon, chip.icon, "Icon must not change when LAF is non-Ayu")
        assertEquals(initialTooltip, chip.toolTipText)
    }

    @Test
    fun `addNotify opens exactly one MessageBusConnection with an owned Disposable parent`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()

        // The parent is an owned Disposable (not the chip itself), so we match
        // on the Disposable interface only.
        verify(exactly = 1) { mockMessageBus.connect(any<Disposable>()) }
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
    fun `addNotify is idempotent - second invocation does not re-subscribe`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()
        chip.addNotify()

        // Only one connect call across the two addNotify invocations - the
        // `if (connection != null) return` early-return guard.
        verify(exactly = 1) { mockMessageBus.connect(any<Disposable>()) }
    }

    @Test
    fun `removeNotify disconnects and nulls the connection`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()
        chip.removeNotify()

        verify(atLeast = 1) { mockConnection.disconnect() }
        // After removeNotify, addNotify can subscribe again - proves connection is null.
        chip.addNotify()
        verify(exactly = 2) { mockMessageBus.connect(any<Disposable>()) }
    }

    @Test
    fun `removeNotify owns the only teardown path (no Disposable surface)`() {
        // The chip is not `Disposable` itself - the platform's lifecycle for a
        // `CustomComponentAction` component is purely addNotify/removeNotify.
        // The owned `connectionParent` Disposable is disposed inside
        // removeNotify; the chip exposes no `dispose()` method.
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()
        chip.removeNotify()

        verify(atLeast = 1) { mockConnection.disconnect() }
        // Belt-and-braces: chip must NOT implement Disposable (would invite
        // platform code to Disposer.register the chip and break the
        // addNotify/removeNotify-owned lifecycle). Reflective check so the
        // assertion survives a future Kotlin compiler that hard-errors on
        // "is always false" smart-cast checks.
        val isDisposable = Disposable::class.java.isAssignableFrom(chip.javaClass)
        assertFalse(
            isDisposable,
            "Chip must NOT be Disposable - lifecycle is Swing-owned",
        )
    }

    @Test
    fun `mousePressed left-click invokes QuickSwitcherPopup show with chip self-arg`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)
        mockkObject(QuickSwitcherPopup)
        every { QuickSwitcherPopup.show(any(), any()) } returns Unit

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(leftClick(chip))

        // Chip passes itself as the second arg so the popup can wire a
        // per-popup JBPopupListener that flips popup-attached state.
        verify(exactly = 1) { QuickSwitcherPopup.show(chip, chip) }
    }

    @Test
    fun `mousePressed right-click does NOT invoke popup (RMB routes to context menu)`() {
        stubAyuActive(true)
        stubResolver(hex = "#FFCC66", source = AccentResolver.Source.GLOBAL)
        mockkObject(QuickSwitcherPopup)
        every { QuickSwitcherPopup.show(any(), any()) } returns Unit
        // RMB is routed to `ActionManager.createActionPopupMenu` - stub it so
        // dispatch through `mousePressed` does not NPE. The "RMB does NOT
        // invoke popup" assertion stays - the popup is for LMB only. Detailed
        // RMB->context-menu assertions live in `QuickSwitcherChipContextMenuTest`.
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
    fun `mousePressed is a no-op when AyuVariant detect returns null`() {
        stubAyuActive(false)
        mockkObject(QuickSwitcherPopup)
        every { QuickSwitcherPopup.show(any(), any()) } returns Unit

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(leftClick(chip))
        chip.dispatchEvent(rightClick(chip))

        verify(exactly = 0) { QuickSwitcherPopup.show(any(), any()) }
    }

    @Test
    fun `setPopupAttached toggles ring state on the chip`() {
        stubAyuActive(true)
        val chip = QuickSwitcherChipComponent()
        assertFalse(chip.isPopupAttached, "Fresh chip must start with no popup attached")
        chip.setPopupAttached(true)
        assertTrue(chip.isPopupAttached)
        chip.setPopupAttached(false)
        assertFalse(chip.isPopupAttached)
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
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns hex
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
}
