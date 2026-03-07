package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AyuVariant
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccentElementsTest {
    private lateinit var mockScheme: EditorColorsScheme
    private lateinit var mockColorsManager: EditorColorsManager

    private val testColor = Color(255, 204, 102)

    @BeforeTest
    fun setUp() {
        mockScheme = mockk(relaxed = true)
        mockColorsManager = mockk(relaxed = true)
        every { mockColorsManager.globalScheme } returns mockScheme

        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockColorsManager

        mockkStatic(UIManager::class)

        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }

        mockkStatic(ColorKey::class)
        every { ColorKey.find(any<String>()) } answers { mockk(relaxed = true) }

        mockkStatic(TextAttributesKey::class)
        every { TextAttributesKey.find(any<String>()) } answers { mockk(relaxed = true) }

        mockkObject(BracketFadeManager)
        every { BracketFadeManager.activate(any()) } returns Unit
        every { BracketFadeManager.deactivate() } returns Unit
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun allElements(): List<AccentElement> =
        listOf(
            LinksElement(),
            ScrollbarElement(),
            ProgressBarElement(),
            SearchResultsElement(),
            InlayHintsElement(),
            CaretRowElement(),
            BracketMatchElement(),
            CheckboxElement(),
        )

    @Test
    fun `all elements have unique IDs`() {
        val elements = allElements()
        val ids = elements.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Element IDs must be unique")
    }

    @Test
    fun `all AccentElementId values have an implementation`() {
        val implementedIds = allElements().map { it.id }.toSet()
        for (entry in AccentElementId.entries) {
            assertTrue(
                entry in implementedIds,
                "AccentElementId.$entry has no implementation",
            )
        }
    }

    @Test
    fun `elements with UI keys call UIManager put on apply`() {
        val uiElements: List<AccentElement> =
            listOf(
                LinksElement(),
                ScrollbarElement(),
                ProgressBarElement(),
                SearchResultsElement(),
            )
        for (element in uiElements) {
            element.apply(testColor)
            verify(atLeast = 1) { UIManager.put(any<String>(), any<Color>()) }
        }
    }

    @Test
    fun `elements with UI keys call UIManager put null on revert`() {
        val uiElements: List<AccentElement> =
            listOf(
                LinksElement(),
                ScrollbarElement(),
                ProgressBarElement(),
                SearchResultsElement(),
            )
        for (element in uiElements) {
            element.revert()
            verify(atLeast = 1) { UIManager.put(any<String>(), null) }
        }
    }

    @Test
    fun `editor-only elements call setAttributes on apply`() {
        val editorElements: List<AccentElement> =
            listOf(
                InlayHintsElement(),
                CaretRowElement(),
                BracketMatchElement(),
            )
        for (element in editorElements) {
            element.apply(testColor)
        }
        // InlayHintsElement and BracketMatchElement use setAttributes, CaretRowElement uses setColor
        verify(atLeast = 1) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
        verify(atLeast = 1) { mockScheme.setColor(any<ColorKey>(), any()) }
    }

    @Test
    fun `editor-only elements call setAttributes null on revert`() {
        val editorElements: List<AccentElement> =
            listOf(
                InlayHintsElement(),
                CaretRowElement(),
                BracketMatchElement(),
            )
        for (element in editorElements) {
            element.revert()
        }
        // InlayHintsElement reverts with setAttributes(key, null), BracketMatchElement uses setAttributes too
        verify(atLeast = 1) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
        // CaretRowElement reverts with setColor(key, null)
        verify(atLeast = 1) { mockScheme.setColor(any<ColorKey>(), null) }
    }

    @Test
    fun `ScrollbarElement apply sets both hover and default alpha colors`() {
        // ScrollbarElement has 8 hover keys + 8 default keys = 16 UIManager keys,
        // plus 16 EditorColorsScheme keys
        val element = ScrollbarElement()
        element.apply(testColor)
        verify(atLeast = 16) { UIManager.put(any<String>(), any<Color>()) }
    }

    @Test
    fun `InlayHintsElement apply uses muted alpha of 140`() {
        val attributesSlot = slot<TextAttributes>()
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns null
        every { mockScheme.setAttributes(any<TextAttributesKey>(), capture(attributesSlot)) } just Runs

        val element = InlayHintsElement()
        element.apply(testColor)

        assertTrue(attributesSlot.isCaptured, "setAttributes should have been called")
        val captured = attributesSlot.captured
        assertEquals(
            140,
            captured.foregroundColor.alpha,
            "InlayHintsElement should apply muted alpha of 140",
        )
    }

    @Test
    fun `CheckboxElement apply and revert do not throw`() {
        val element = CheckboxElement()
        element.apply(testColor)
        element.revert()
    }

    @Test
    fun `LinksElement has correct id and displayName`() {
        val element = LinksElement()
        assertEquals(AccentElementId.LINKS, element.id)
        assertEquals("Links", element.displayName)
    }

    // AccentElement default applyNeutral delegates to revert

    @Test
    fun `default applyNeutral calls revert for elements without override`() {
        // SearchResultsElement does not override applyNeutral, so the default
        // implementation in AccentElement interface should delegate to revert()
        val element = SearchResultsElement()
        element.applyNeutral(AyuVariant.MIRAGE)
        // revert() nulls out all selection keys via UIManager
        verify(atLeast = 1) { UIManager.put(any<String>(), null) }
    }

    @Test
    fun `CheckboxElement applyNeutral uses default which calls revert`() {
        val element = CheckboxElement()
        // Should not throw; the default applyNeutral calls revert()
        element.applyNeutral(AyuVariant.DARK)
    }

    // ProgressBarElement coverage

    @Test
    fun `ProgressBarElement has correct id and displayName`() {
        val element = ProgressBarElement()
        assertEquals(AccentElementId.PROGRESS_BAR, element.id)
        assertEquals("Progress Bar", element.displayName)
    }

    @Test
    fun `ProgressBarElement apply sets UI keys and editor color key`() {
        val element = ProgressBarElement()
        element.apply(testColor)
        verify { UIManager.put("ProgressBar.foreground", testColor) }
        verify { UIManager.put("ProgressBar.progressCounterBackground", testColor) }
        verify { mockScheme.setColor(any(), testColor) }
    }

    @Test
    fun `ProgressBarElement revert nulls UI keys and editor color key`() {
        val element = ProgressBarElement()
        element.revert()
        verify { UIManager.put("ProgressBar.foreground", null) }
        verify { UIManager.put("ProgressBar.progressCounterBackground", null) }
        verify { mockScheme.setColor(any(), null) }
    }

    @Test
    fun `ProgressBarElement applyNeutral restores from parent scheme`() {
        val parentScheme: EditorColorsScheme = mockk(relaxed = true)
        every { mockColorsManager.getScheme("Darcula") } returns parentScheme
        every { parentScheme.getColor(any()) } returns Color(100, 100, 100)

        val element = ProgressBarElement()
        element.applyNeutral(AyuVariant.MIRAGE)

        verify { UIManager.put("ProgressBar.foreground", null) }
        verify { UIManager.put("ProgressBar.progressCounterBackground", null) }
        verify { mockScheme.setColor(any(), Color(100, 100, 100)) }
    }

    @Test
    fun `ProgressBarElement applyNeutral handles null parent scheme`() {
        every { mockColorsManager.getScheme("Darcula") } returns null

        val element = ProgressBarElement()
        element.applyNeutral(AyuVariant.MIRAGE)

        verify { UIManager.put("ProgressBar.foreground", null) }
        verify { mockScheme.setColor(any(), null) }
    }

    @Test
    fun `ProgressBarElement runOnEdt uses invokeLater when not on EDT`() {
        every { SwingUtilities.isEventDispatchThread() } returns false

        val element = ProgressBarElement()
        element.apply(testColor)

        verify { SwingUtilities.invokeLater(any()) }
        verify { UIManager.put("ProgressBar.foreground", testColor) }
    }

    @Test
    fun `ProgressBarElement revert off EDT uses invokeLater`() {
        every { SwingUtilities.isEventDispatchThread() } returns false

        val element = ProgressBarElement()
        element.revert()

        verify { SwingUtilities.invokeLater(any()) }
        verify { mockScheme.setColor(any(), null) }
    }

    // LinksElement coverage

    @Test
    fun `LinksElement apply sets all UI keys`() {
        val element = LinksElement()
        element.apply(testColor)

        verify { UIManager.put("Link.activeForeground", testColor) }
        verify { UIManager.put("Link.hoverForeground", testColor) }
        verify { UIManager.put("Link.secondaryForeground", testColor) }
        verify { UIManager.put("Notification.linkForeground", testColor) }
        verify { UIManager.put("GotItTooltip.linkForeground", testColor) }
        verify { UIManager.put("Tooltip.Learning.linkForeground", testColor) }
    }

    @Test
    fun `LinksElement apply sets editor color keys and text attributes`() {
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns null

        val element = LinksElement()
        element.apply(testColor)

        // 2 editor color keys
        verify(exactly = 2) { mockScheme.setColor(any(), testColor) }
        // 3 text attribute keys
        verify(exactly = 3) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    @Test
    fun `LinksElement apply clones existing attributes when present`() {
        val existingAttrs = TextAttributes()
        existingAttrs.foregroundColor = Color.WHITE
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns existingAttrs

        val attributesSlot = slot<TextAttributes>()
        every { mockScheme.setAttributes(any<TextAttributesKey>(), capture(attributesSlot)) } just Runs

        val element = LinksElement()
        element.apply(testColor)

        assertTrue(attributesSlot.isCaptured)
        val captured = attributesSlot.captured
        assertEquals(testColor, captured.foregroundColor)
        assertEquals(testColor, captured.effectColor)
    }

    @Test
    fun `LinksElement revert nulls all editor color keys and text attributes`() {
        val element = LinksElement()
        element.revert()

        // 6 UI keys nulled
        verify(exactly = 6) { UIManager.put(any<String>(), null) }
        // 2 editor color keys nulled
        verify(exactly = 2) { mockScheme.setColor(any(), null) }
        // 3 text attribute keys nulled
        verify(exactly = 3) { mockScheme.setAttributes(any<TextAttributesKey>(), null) }
    }

    @Test
    fun `LinksElement applyNeutral restores from parent scheme`() {
        val parentScheme: EditorColorsScheme = mockk(relaxed = true)
        val parentAttrs = TextAttributes()
        parentAttrs.foregroundColor = Color.CYAN
        every { mockColorsManager.getScheme("Darcula") } returns parentScheme
        every { parentScheme.getColor(any()) } returns Color(80, 80, 80)
        every { parentScheme.getAttributes(any<TextAttributesKey>()) } returns parentAttrs

        val element = LinksElement()
        element.applyNeutral(AyuVariant.DARK)

        // UI keys nulled
        verify(exactly = 6) { UIManager.put(any<String>(), null) }
        // Editor color keys restored from parent
        verify(exactly = 2) { mockScheme.setColor(any(), Color(80, 80, 80)) }
        // Text attributes restored from parent
        verify(exactly = 3) { mockScheme.setAttributes(any<TextAttributesKey>(), parentAttrs) }
    }

    @Test
    fun `LinksElement applyNeutral handles null parent scheme`() {
        every { mockColorsManager.getScheme("Darcula") } returns null

        val element = LinksElement()
        element.applyNeutral(AyuVariant.MIRAGE)

        verify(exactly = 6) { UIManager.put(any<String>(), null) }
        verify(exactly = 2) { mockScheme.setColor(any(), null) }
        verify(exactly = 3) { mockScheme.setAttributes(any<TextAttributesKey>(), null) }
    }

    // InlayHintsElement coverage

    @Test
    fun `InlayHintsElement has correct id and displayName`() {
        val element = InlayHintsElement()
        assertEquals(AccentElementId.INLAY_HINTS, element.id)
        assertEquals("Inlay Hints", element.displayName)
    }

    @Test
    fun `InlayHintsElement apply clones existing attributes when present`() {
        val existingAttrs = TextAttributes()
        existingAttrs.foregroundColor = Color.RED
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns existingAttrs

        val attributesSlot = slot<TextAttributes>()
        every { mockScheme.setAttributes(any<TextAttributesKey>(), capture(attributesSlot)) } just Runs

        val element = InlayHintsElement()
        element.apply(testColor)

        assertTrue(attributesSlot.isCaptured)
        val captured = attributesSlot.captured
        assertNotNull(captured.foregroundColor)
        assertEquals(140, captured.foregroundColor!!.alpha)
        assertEquals(testColor.red, captured.foregroundColor!!.red)
        assertEquals(testColor.green, captured.foregroundColor!!.green)
        assertEquals(testColor.blue, captured.foregroundColor!!.blue)
    }

    @Test
    fun `InlayHintsElement revert sets attributes to null`() {
        val element = InlayHintsElement()
        element.revert()

        verify(exactly = 1) { mockScheme.setAttributes(any<TextAttributesKey>(), null) }
    }

    @Test
    fun `InlayHintsElement applyNeutral restores from parent scheme`() {
        val parentScheme: EditorColorsScheme = mockk(relaxed = true)
        val parentAttrs = TextAttributes()
        parentAttrs.foregroundColor = Color.GRAY
        every { mockColorsManager.getScheme("Darcula") } returns parentScheme
        every { parentScheme.getAttributes(any<TextAttributesKey>()) } returns parentAttrs

        val element = InlayHintsElement()
        element.applyNeutral(AyuVariant.MIRAGE)

        verify { mockScheme.setAttributes(any<TextAttributesKey>(), parentAttrs) }
    }

    @Test
    fun `InlayHintsElement applyNeutral handles null parent scheme`() {
        every { mockColorsManager.getScheme("Darcula") } returns null

        val element = InlayHintsElement()
        element.applyNeutral(AyuVariant.MIRAGE)

        verify { mockScheme.setAttributes(any<TextAttributesKey>(), null) }
    }

    // CaretRowElement coverage

    @Test
    fun `CaretRowElement has correct id and displayName`() {
        val element = CaretRowElement()
        assertEquals(AccentElementId.CARET_ROW, element.id)
        assertEquals("Caret Row", element.displayName)
    }

    @Test
    fun `CaretRowElement apply sets caret row with alpha and caret and line number colors`() {
        val colorSlots = mutableListOf<Color?>()
        every { mockScheme.setColor(any(), captureNullable(colorSlots)) } just Runs

        val element = CaretRowElement()
        element.apply(testColor)

        assertEquals(3, colorSlots.size, "Should set 3 color keys")
        // First call: caretRowKey with alpha 0x1A
        val caretRowColor = colorSlots[0]!!
        assertEquals(testColor.red, caretRowColor.red)
        assertEquals(testColor.green, caretRowColor.green)
        assertEquals(testColor.blue, caretRowColor.blue)
        assertEquals(0x1A, caretRowColor.alpha)
        // Second and third: caretKey and lineNumberKey with full color
        assertEquals(testColor, colorSlots[1])
        assertEquals(testColor, colorSlots[2])
    }

    @Test
    fun `CaretRowElement revert nulls all three color keys`() {
        val element = CaretRowElement()
        element.revert()

        verify(exactly = 3) { mockScheme.setColor(any(), null) }
    }

    @Test
    fun `CaretRowElement applyNeutral restores from parent scheme`() {
        val parentScheme: EditorColorsScheme = mockk(relaxed = true)
        val parentColor = Color(50, 60, 70)
        every { mockColorsManager.getScheme("Darcula") } returns parentScheme
        every { parentScheme.getColor(any()) } returns parentColor

        val element = CaretRowElement()
        element.applyNeutral(AyuVariant.MIRAGE)

        verify(exactly = 3) { mockScheme.setColor(any(), parentColor) }
    }

    @Test
    fun `CaretRowElement applyNeutral handles null parent scheme`() {
        every { mockColorsManager.getScheme("Darcula") } returns null

        val element = CaretRowElement()
        element.applyNeutral(AyuVariant.MIRAGE)

        verify(exactly = 3) { mockScheme.setColor(any(), null) }
    }

    // BracketMatchElement coverage

    @Test
    fun `BracketMatchElement has correct id and displayName`() {
        val element = BracketMatchElement()
        assertEquals(AccentElementId.BRACKET_MATCH, element.id)
        assertEquals("Bracket Match", element.displayName)
    }

    @Test
    fun `BracketMatchElement apply sets foreground and bold via setAttributes`() {
        val attributesSlot = slot<TextAttributes>()
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns null
        every { mockScheme.setAttributes(any<TextAttributesKey>(), capture(attributesSlot)) } just Runs

        val element = BracketMatchElement()
        element.apply(testColor)

        assertTrue(attributesSlot.isCaptured, "setAttributes should have been called")
        assertEquals(testColor, attributesSlot.captured.foregroundColor)
        assertEquals(java.awt.Font.BOLD, attributesSlot.captured.fontType)
        verify { BracketFadeManager.activate(testColor) }
    }

    @Test
    fun `BracketMatchElement revert restores fallback attributes`() {
        val element = BracketMatchElement()
        element.revert()

        verify(atLeast = 1) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
        verify { BracketFadeManager.deactivate() }
    }

    @Test
    fun `BracketMatchElement applyNeutral restores from parent scheme`() {
        val parentScheme: EditorColorsScheme = mockk(relaxed = true)
        val parentAttrs = TextAttributes()
        every { mockColorsManager.getScheme("Darcula") } returns parentScheme
        every { parentScheme.getAttributes(any<TextAttributesKey>()) } returns parentAttrs

        val element = BracketMatchElement()
        element.applyNeutral(AyuVariant.MIRAGE)

        verify { mockScheme.setAttributes(any<TextAttributesKey>(), parentAttrs) }
        verify { BracketFadeManager.deactivate() }
    }

    @Test
    fun `BracketMatchElement applyNeutral handles null parent scheme`() {
        every { mockColorsManager.getScheme("Darcula") } returns null

        val element = BracketMatchElement()
        element.applyNeutral(AyuVariant.MIRAGE)

        verify { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
        verify { BracketFadeManager.deactivate() }
    }

    @Test
    fun `BracketMatchElement apply clones existing attributes when present`() {
        val existingAttrs = TextAttributes()
        existingAttrs.foregroundColor = Color.WHITE
        val attributesSlot = slot<TextAttributes>()
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns existingAttrs
        every { mockScheme.setAttributes(any<TextAttributesKey>(), capture(attributesSlot)) } just Runs

        val element = BracketMatchElement()
        element.apply(testColor)

        assertTrue(attributesSlot.isCaptured, "setAttributes should have been called")
        assertEquals(testColor, attributesSlot.captured.foregroundColor)
        assertEquals(java.awt.Font.BOLD, attributesSlot.captured.fontType)
        verify { BracketFadeManager.activate(testColor) }
    }

    @Test
    fun `BracketMatchElement applyNeutral with non-null parent but null attrs`() {
        val parentScheme: EditorColorsScheme = mockk(relaxed = true)
        every { mockColorsManager.getScheme("Darcula") } returns parentScheme
        every { parentScheme.getAttributes(any<TextAttributesKey>()) } returns null
        val attributesSlot = slot<TextAttributes>()
        every { mockScheme.setAttributes(any<TextAttributesKey>(), capture(attributesSlot)) } just Runs

        val element = BracketMatchElement()
        element.applyNeutral(AyuVariant.MIRAGE)

        assertTrue(attributesSlot.isCaptured, "setAttributes should have been called")
        assertNotNull(attributesSlot.captured, "Should use fallback TextAttributes when parent attrs are null")
        verify { BracketFadeManager.deactivate() }
    }

    @Test
    fun `BracketMatchElement revert with null fallback attribute key`() {
        val braceKey = mockk<TextAttributesKey>(relaxed = true)
        every { braceKey.fallbackAttributeKey } returns null
        every { TextAttributesKey.find("MATCHED_BRACE_ATTRIBUTES") } returns braceKey
        val attributesSlot = slot<TextAttributes>()
        every { mockScheme.setAttributes(any<TextAttributesKey>(), capture(attributesSlot)) } just Runs

        val element = BracketMatchElement()
        element.revert()

        assertTrue(attributesSlot.isCaptured, "setAttributes should have been called")
        assertNotNull(attributesSlot.captured, "Should use fallback TextAttributes when fallback key is null")
        verify { BracketFadeManager.deactivate() }
    }

    // AccentElementId enum coverage

    @Test
    fun `AccentElementId entries have correct groups`() {
        assertEquals(AccentGroup.VISUAL, AccentElementId.INLAY_HINTS.group)
        assertEquals(AccentGroup.VISUAL, AccentElementId.CARET_ROW.group)
        assertEquals(AccentGroup.VISUAL, AccentElementId.PROGRESS_BAR.group)
        assertEquals(AccentGroup.VISUAL, AccentElementId.SCROLLBAR.group)
        assertEquals(AccentGroup.INTERACTIVE, AccentElementId.LINKS.group)
        assertEquals(AccentGroup.INTERACTIVE, AccentElementId.BRACKET_MATCH.group)
        assertEquals(AccentGroup.INTERACTIVE, AccentElementId.SEARCH_RESULTS.group)
        assertEquals(AccentGroup.INTERACTIVE, AccentElementId.CHECKBOXES.group)
    }

    // AyuVariant coverage for applyNeutral with different variants

    @Test
    fun `applyNeutral works with Light variant using Default parent scheme`() {
        val parentScheme: EditorColorsScheme = mockk(relaxed = true)
        val parentAttrs = TextAttributes()
        every { mockColorsManager.getScheme("Default") } returns parentScheme
        every { parentScheme.getAttributes(any<TextAttributesKey>()) } returns parentAttrs

        val element = BracketMatchElement()
        element.applyNeutral(AyuVariant.LIGHT)

        verify { mockScheme.setAttributes(any<TextAttributesKey>(), parentAttrs) }
    }
}
