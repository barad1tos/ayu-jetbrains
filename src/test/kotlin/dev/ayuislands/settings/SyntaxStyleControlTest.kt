package dev.ayuislands.settings

import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.popup.ComponentPopupBuilder
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.PrimitiveCategory
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JComponent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyntaxStyleControlTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `checkbox flags map to sparse emphasis`() {
        // Break caught: an unchecked pair must stay absent instead of creating a destructive plain-style cell.
        assertNull(FontEmphasis.fromFlags(isBold = false, isItalic = false))
        assertEquals(FontEmphasis.BOLD, FontEmphasis.fromFlags(isBold = true, isItalic = false))
        assertEquals(FontEmphasis.ITALIC, FontEmphasis.fromFlags(isBold = false, isItalic = true))
        assertEquals(FontEmphasis.BOLD_ITALIC, FontEmphasis.fromFlags(isBold = true, isItalic = true))
    }

    @Test
    fun `glyph text covers inherited and combined states`() {
        // Break caught: the compact anchor must distinguish inherited, bold, italic, and combined emphasis states.
        assertEquals("Aa", SyntaxStyleControl.glyphFor(null))
        assertEquals("B", SyntaxStyleControl.glyphFor(FontEmphasis.BOLD))
        assertEquals("I", SyntaxStyleControl.glyphFor(FontEmphasis.ITALIC))
        assertEquals("BI", SyntaxStyleControl.glyphFor(FontEmphasis.BOLD_ITALIC))
    }

    @Test
    fun `popup checkboxes publish every sparse emphasis state without closing`() {
        // Break caught: native checkbox toggles must publish additive state while the popup
        // stays open for combination edits.
        val contentSlot = slot<JComponent>()
        val popup = stubPopup(contentSlot = contentSlot)
        var emphasis: FontEmphasis? = null
        val changed = mutableListOf<FontEmphasis?>()
        val control =
            SyntaxStyleControl(
                category = PrimitiveCategory.FUNCTION_DECL,
                language = { "Kotlin" },
                emphasis = { emphasis },
                onEmphasisChanged = {
                    emphasis = it
                    changed += it
                },
            )

        control.component.doClick()

        val content = contentSlot.captured as DialogPanel
        val checkBoxes = findCheckBoxes(content)
        assertEquals(listOf("Bold", "Italic"), checkBoxes.map { it.text })

        checkBoxes.single { it.text == "Bold" }.doClick()
        assertEquals(FontEmphasis.BOLD, changed.single())

        checkBoxes.single { it.text == "Italic" }.doClick()
        assertEquals(FontEmphasis.BOLD_ITALIC, changed.last())

        checkBoxes.single { it.text == "Bold" }.doClick()
        assertEquals(FontEmphasis.ITALIC, changed.last())

        checkBoxes.single { it.text == "Italic" }.doClick()
        assertNull(changed.last())
        verify(exactly = 1) { popup.showUnderneathOf(control.component) }
        verify(exactly = 0) { popup.cancel() }
    }

    @Test
    fun `rebind closes popup before refreshing language accessibility`() {
        // Break caught: a language switch must not leave an editor popup bound to the previous language.
        val popup = stubPopup()
        var language = "Kotlin"
        val control =
            SyntaxStyleControl(
                category = PrimitiveCategory.FUNCTION_DECL,
                language = { language },
                emphasis = { null },
                onEmphasisChanged = {},
            )
        control.component.doClick()
        assertEquals("Font style — Function declaration, Kotlin", control.component.accessibleContext.accessibleName)

        language = "Java"
        every { popup.cancel() } answers {
            assertEquals(
                "Font style — Function declaration, Kotlin",
                control.component.accessibleContext.accessibleName,
                "the old popup must cancel before accessibility is rebound",
            )
        }
        control.rebind()

        verify(exactly = 1) { popup.cancel() }
        assertEquals("Font style — Function declaration, Java", control.component.accessibleContext.accessibleName)
        assertEquals(
            "Adds bold or italic emphasis to the inherited syntax style.",
            control.component.accessibleContext.accessibleDescription,
        )
    }

    @Test
    fun `stale popup closure cannot detach the replacement before dispose`() {
        // Break caught: an old popup's delayed close event must not clear the newer popup reference.
        val listeners = mutableListOf<JBPopupListener>()
        val (firstPopup, secondPopup) = stubPopupSequence(listeners)
        val control =
            SyntaxStyleControl(
                category = PrimitiveCategory.FUNCTION_DECL,
                language = { "Kotlin" },
                emphasis = { null },
                onEmphasisChanged = {},
            )
        control.component.doClick()
        control.component.doClick()
        assertEquals(2, listeners.size)

        listeners.first().onClosed(mockk<LightweightWindowEvent>(relaxed = true))

        control.dispose()
        control.dispose()

        verify(exactly = 1) { firstPopup.cancel() }
        verify(exactly = 1) { secondPopup.cancel() }
    }

    @Test
    fun `popup uses native focus and cancellation policy`() {
        // Break caught: the selector must keep native popup focus and close only at deliberate popup boundaries.
        val contentSlot = slot<JComponent>()
        val listenerSlot = slot<JBPopupListener>()
        val builder = mockk<ComponentPopupBuilder>(relaxed = true)
        stubPopup(contentSlot, listenerSlot, builder)
        val control =
            SyntaxStyleControl(
                category = PrimitiveCategory.FUNCTION_DECL,
                language = { "Kotlin" },
                emphasis = { FontEmphasis.BOLD },
                onEmphasisChanged = {},
            )

        control.component.doClick()

        assertTrue(control.component.isFocusable)
        verify(exactly = 1) { builder.setTitle("Function declaration") }
        verify(exactly = 1) { builder.setRequestFocus(true) }
        verify(exactly = 1) { builder.setCancelOnClickOutside(true) }
        verify(exactly = 1) { builder.setCancelOnWindowDeactivation(false) }
        verify(exactly = 1) { builder.setMovable(false) }
        verify(exactly = 1) { builder.setResizable(false) }
        verify(exactly = 1) { builder.setCancelKeyEnabled(true) }
        assertTrue(listenerSlot.isCaptured)
        val checkBoxes = findCheckBoxes(contentSlot.captured)
        assertTrue(checkBoxes.single { it.text == "Bold" }.isSelected)
        assertFalse(checkBoxes.single { it.text == "Italic" }.isSelected)
    }

    private fun stubPopup(
        contentSlot: CapturingSlot<JComponent> = slot(),
        listenerSlot: CapturingSlot<JBPopupListener> = slot(),
        builder: ComponentPopupBuilder = mockk(relaxed = true),
    ): JBPopup {
        mockkStatic(JBPopupFactory::class)
        val factory = mockk<JBPopupFactory>(relaxed = true)
        val popup = mockk<JBPopup>(relaxed = true)
        every { JBPopupFactory.getInstance() } returns factory
        every { factory.createComponentPopupBuilder(capture(contentSlot), any()) } returns builder
        every { builder.setTitle(any()) } returns builder
        every { builder.setRequestFocus(any()) } returns builder
        every { builder.setCancelOnClickOutside(any()) } returns builder
        every { builder.setCancelOnWindowDeactivation(any()) } returns builder
        every { builder.setMovable(any()) } returns builder
        every { builder.setResizable(any()) } returns builder
        every { builder.setCancelKeyEnabled(any()) } returns builder
        every { builder.createPopup() } returns popup
        every { popup.addListener(capture(listenerSlot)) } returns Unit
        return popup
    }

    private fun stubPopupSequence(listeners: MutableList<JBPopupListener>): Pair<JBPopup, JBPopup> {
        mockkStatic(JBPopupFactory::class)
        val factory = mockk<JBPopupFactory>(relaxed = true)
        val builder = mockk<ComponentPopupBuilder>(relaxed = true)
        val firstPopup = mockk<JBPopup>(relaxed = true)
        val secondPopup = mockk<JBPopup>(relaxed = true)
        every { JBPopupFactory.getInstance() } returns factory
        every { factory.createComponentPopupBuilder(any(), any()) } returns builder
        every { builder.setTitle(any()) } returns builder
        every { builder.setRequestFocus(any()) } returns builder
        every { builder.setCancelOnClickOutside(any()) } returns builder
        every { builder.setCancelOnWindowDeactivation(any()) } returns builder
        every { builder.setMovable(any()) } returns builder
        every { builder.setResizable(any()) } returns builder
        every { builder.setCancelKeyEnabled(any()) } returns builder
        every { builder.createPopup() } returnsMany listOf(firstPopup, secondPopup)
        every { firstPopup.addListener(capture(listeners)) } returns Unit
        every { secondPopup.addListener(capture(listeners)) } returns Unit
        return firstPopup to secondPopup
    }

    private fun findCheckBoxes(container: Container): List<JCheckBox> =
        container.components.flatMap { component ->
            val nested = if (component is Container) findCheckBoxes(component) else emptyList()
            if (component is JCheckBox) listOf(component) + nested else nested
        }
}
