package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.theme.AyuEditorSchemeScope
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccentElementsTest {
    private lateinit var mockScheme: EditorColorsScheme
    private lateinit var mockColorsManager: EditorColorsManager
    private val colorKeys = mutableMapOf<String, ColorKey>()
    private val attributeKeys = mutableMapOf<String, TextAttributesKey>()

    private val testColor = Color(255, 204, 102)

    @BeforeTest
    fun setUp() {
        AyuEditorSchemeScope.resetClaims()
        colorKeys.clear()
        attributeKeys.clear()
        mockScheme = mockk(relaxed = true)
        mockColorsManager = mockk(relaxed = true)
        every { mockColorsManager.globalScheme } returns mockScheme
        every { mockScheme.defaultBackground } returns Color(0x1F, 0x24, 0x30)
        every { mockScheme.name } returns "Ayu Islands Mirage"

        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockColorsManager

        mockkStatic(UIManager::class)

        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }

        mockkStatic(ColorKey::class)
        every { ColorKey.find(any<String>()) } answers {
            colorKeys.getOrPut(firstArg()) { mockk(relaxed = true) }
        }

        mockkStatic(TextAttributesKey::class)
        every { TextAttributesKey.find(any<String>()) } answers {
            attributeKeys.getOrPut(firstArg()) { mockk(relaxed = true) }
        }

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        mockkObject(BracketFadeManager)
        every { BracketFadeManager.activate(any()) } returns Unit
        every { BracketFadeManager.deactivate() } returns Unit
    }

    @AfterTest
    fun tearDown() {
        AyuEditorSchemeScope.resetClaims()
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
            MatchingTagElement(),
        )

    private fun editorElements(): List<AccentElement> =
        listOf(
            InlayHintsElement(),
            CaretRowElement(),
            ProgressBarElement(),
            ScrollbarElement(),
            LinksElement(),
            BracketMatchElement(),
            MatchingTagElement(),
        )

    @Test
    fun `all elements have unique IDs`() {
        val elements = allElements()
        val ids = elements.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Element IDs must be unique")
    }

    @Test
    fun `all AccentElementId values have an implementation`() {
        // CHROME-group elements are owned by the phase 40 chrome-tinting subsystem,
        // not the VISUAL/INTERACTIVE AccentElement EP registered here. Limit this
        // coverage check to VISUAL + INTERACTIVE so the registry gate stays accurate
        // for the subsystem this test file covers.
        val implementedIds = allElements().map { it.id }.toSet()
        for (entry in AccentElementId.entries.filter { it.group != AccentGroup.CHROME }) {
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
    fun `scheme-backed elements preserve a foreign editor scheme`() {
        every { mockScheme.name } returns "Solarized Dark"
        val elements =
            listOf(
                LinksElement(),
                ScrollbarElement(),
                ProgressBarElement(),
                InlayHintsElement(),
                CaretRowElement(),
                BracketMatchElement(),
                MatchingTagElement(),
            )

        elements.forEach { element ->
            element.apply(testColor)
            element.applyNeutral(AyuVariant.MIRAGE)
            element.revert()
        }

        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any<Color>()) }
        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), null) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), null) }
        verify(exactly = 2) { BracketFadeManager.deactivate() }
    }

    @Test
    fun `disabled editor elements restore exact user values`() {
        for (element in editorElements()) {
            AyuEditorSchemeScope.resetClaims()
            try {
                val store = EditorSchemeStore()
                every { mockColorsManager.globalScheme } returns store.scheme

                element.apply(testColor)
                store.assertWasChanged(element)
                element.applyNeutral(AyuVariant.MIRAGE)

                store.assertOriginalValues(element)
            } finally {
                AyuEditorSchemeScope.resetClaims()
            }
        }
    }

    @Test
    fun `editor element revert restores exact user values`() {
        for (element in editorElements()) {
            AyuEditorSchemeScope.resetClaims()
            try {
                val store = EditorSchemeStore()
                every { mockColorsManager.globalScheme } returns store.scheme

                element.apply(testColor)
                store.assertWasChanged(element)
                element.revert()

                store.assertOriginalValues(element)
            } finally {
                AyuEditorSchemeScope.resetClaims()
            }
        }
    }

    @Test
    fun `external editor changes survive every later element action`() {
        for (element in editorElements()) {
            AyuEditorSchemeScope.resetClaims()
            try {
                val store = EditorSchemeStore()
                every { mockColorsManager.globalScheme } returns store.scheme

                element.apply(testColor)
                store.assertWasChanged(element)
                store.replaceTouchedValues()
                element.apply(Color.YELLOW)
                element.applyNeutral(AyuVariant.MIRAGE)
                element.revert()

                store.assertExternalValues(element)
            } finally {
                AyuEditorSchemeScope.resetClaims()
            }
        }
    }

    @Test
    fun `mixed elements still update UI keys for a foreign editor scheme`() {
        every { mockScheme.name } returns "Solarized Dark"
        val elements = listOf(LinksElement(), ScrollbarElement(), ProgressBarElement())

        elements.forEach { element ->
            element.apply(testColor)
            element.revert()
        }

        verify(atLeast = 1) { UIManager.put(any<String>(), any<Color>()) }
        verify(atLeast = 1) { UIManager.put(any<String>(), null) }
    }

    @Test
    fun `scheme-backed reverts clean owned Ayu scheme after leaving Ayu`() {
        val store = EditorSchemeStore()
        every { mockColorsManager.globalScheme } returns store.scheme
        val elements =
            listOf(
                LinksElement(),
                ScrollbarElement(),
                ProgressBarElement(),
                InlayHintsElement(),
                CaretRowElement(),
                BracketMatchElement(),
                MatchingTagElement(),
            )

        elements.forEach { it.apply(testColor) }
        every { AyuVariant.detect() } returns null
        elements.forEach { it.revert() }

        elements.forEach(store::assertOriginalValues)
    }

    @Test
    fun `revert cleans every exact scheme claimed across Ayu variants`() {
        val firstStore = EditorSchemeStore()
        val secondStore = EditorSchemeStore("Ayu Islands Dark")
        var currentScheme = firstStore.scheme
        every { mockColorsManager.globalScheme } answers { currentScheme }
        val element = CaretRowElement()

        element.apply(testColor)
        currentScheme = secondStore.scheme
        every { AyuVariant.detect() } returns AyuVariant.DARK
        element.apply(testColor)
        every { AyuVariant.detect() } returns null

        element.revert()

        firstStore.assertOriginalValues(element)
        secondStore.assertOriginalValues(element)
    }

    @Test
    fun `editor-only elements call setAttributes on apply`() {
        val editorElements: List<AccentElement> =
            listOf(
                InlayHintsElement(),
                CaretRowElement(),
                BracketMatchElement(),
                MatchingTagElement(),
            )
        for (element in editorElements) {
            element.apply(testColor)
        }
        // InlayHintsElement and BracketMatchElement use setAttributes, CaretRowElement uses setColor
        verify(atLeast = 1) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
        verify(atLeast = 1) { mockScheme.setColor(any<ColorKey>(), any()) }
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
    fun `MatchingTagElement apply sets blended opaque background on MATCHED_TAG_NAME`() {
        val attributesSlot = slot<TextAttributes>()
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns null
        every { mockScheme.setAttributes(any<TextAttributesKey>(), capture(attributesSlot)) } just Runs

        val element = MatchingTagElement()
        element.apply(testColor)

        assertTrue(attributesSlot.isCaptured, "setAttributes should have been called")
        val captured = attributesSlot.captured
        assertNotNull(captured.backgroundColor, "backgroundColor should be set")
        assertEquals(255, captured.backgroundColor.alpha, "backgroundColor must be persisted as opaque RGB")
        assertEquals(Color(0x49, 0x44, 0x3A), captured.backgroundColor)
        assertNull(captured.foregroundColor, "foregroundColor should remain null")
    }

    @Test
    fun `MatchingTagElement apply does not persist full accent when scheme background is unresolved`() {
        val attributesSlot = slot<TextAttributes>()
        every { mockScheme.defaultBackground } returns Color.WHITE
        every { mockScheme.name } returns "_@user_Ayu Islands Mirage"
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns null
        every { mockScheme.setAttributes(any<TextAttributesKey>(), capture(attributesSlot)) } just Runs

        JBColor.setDark(true)
        try {
            val element = MatchingTagElement()
            element.apply(testColor)

            assertEquals(Color(0x49, 0x44, 0x3A), attributesSlot.captured.backgroundColor)
        } finally {
            JBColor.setDark(false)
        }
    }

    @Test
    fun `MatchingTagElement apply keeps light background when light scheme is unresolved`() {
        val attributesSlot = slot<TextAttributes>()
        every { AyuVariant.detect() } returns AyuVariant.LIGHT
        every { mockScheme.defaultBackground } returns Color.WHITE
        every { mockScheme.name } returns "Ayu Islands Light"
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns null
        every { mockScheme.setAttributes(any<TextAttributesKey>(), capture(attributesSlot)) } just Runs

        val element = MatchingTagElement()
        element.apply(testColor)

        assertEquals(Color(0xFF, 0xF5, 0xE2), attributesSlot.captured.backgroundColor)
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
    fun `ProgressBarElement revert clears UI keys`() {
        val element = ProgressBarElement()
        element.revert()
        verify { UIManager.put("ProgressBar.foreground", null) }
        verify { UIManager.put("ProgressBar.progressCounterBackground", null) }
    }

    @Test
    fun `ProgressBarElement runOnEdt uses invokeLater when not on EDT`() {
        every { SwingUtilities.isEventDispatchThread() } returns false
        val application = mockk<Application>()
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(any(), ModalityState.nonModal()) } answers {
            firstArg<Runnable>().run()
        }

        val element = ProgressBarElement()
        element.apply(testColor)

        verify { application.invokeLater(any(), ModalityState.nonModal()) }
        verify(exactly = 0) { SwingUtilities.invokeLater(any()) }
        verify { UIManager.put("ProgressBar.foreground", testColor) }
    }

    @Test
    fun `ProgressBarElement revert off EDT uses invokeLater`() {
        val store = EditorSchemeStore()
        every { mockColorsManager.globalScheme } returns store.scheme
        val element = ProgressBarElement()
        element.apply(testColor)
        every { SwingUtilities.isEventDispatchThread() } returns false
        val application = mockk<Application>()
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(any(), ModalityState.nonModal()) } answers {
            firstArg<Runnable>().run()
        }

        element.revert()

        verify { application.invokeLater(any(), ModalityState.nonModal()) }
        verify(exactly = 0) { SwingUtilities.invokeLater(any()) }
        store.assertOriginalValues(element)
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
    fun `LinksElement revert clears all UI keys`() {
        val element = LinksElement()
        element.revert()

        verify(exactly = 6) { UIManager.put(any<String>(), null) }
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
    fun `BracketMatchElement revert deactivates bracket fade`() {
        val element = BracketMatchElement()
        element.revert()

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

    // SearchResultsElement blend coverage

    @Test
    fun `SearchResultsElement apply produces opaque blended colors`() {
        val treeBg = Color(31, 36, 48) // typical dark theme Tree.background
        every { UIManager.getColor("Tree.background") } returns treeBg
        every { UIManager.getColor("List.background") } returns treeBg
        every { UIManager.getColor("Table.background") } returns treeBg

        val colorSlots = mutableListOf<Any?>()
        every { UIManager.put(any<String>(), captureNullable(colorSlots)) } returns null

        val element = SearchResultsElement()
        element.apply(testColor)

        val colors = colorSlots.filterIsInstance<Color>()
        assertTrue(colors.isNotEmpty(), "Should have put Color values into UIManager")
        for (color in colors) {
            assertEquals(
                255,
                color.alpha,
                "Selection color must be opaque (alpha=255), got ${color.alpha}",
            )
        }
    }

    @Test
    fun `SearchResultsElement apply falls back to Panel background`() {
        every { UIManager.getColor("Tree.background") } returns null
        every { UIManager.getColor("List.background") } returns null
        every { UIManager.getColor("Table.background") } returns null
        every { UIManager.getColor("Panel.background") } returns Color(30, 30, 30)

        val colorSlots = mutableListOf<Any?>()
        every { UIManager.put(any<String>(), captureNullable(colorSlots)) } returns null

        val element = SearchResultsElement()
        element.apply(testColor)

        val colors = colorSlots.filterIsInstance<Color>()
        assertTrue(colors.isNotEmpty(), "Should produce colors even with fallback")
        for (color in colors) {
            assertEquals(255, color.alpha, "Fallback colors must be opaque")
        }
    }

    @Test
    fun `SearchResultsElement apply falls back to accent when no background available`() {
        every { UIManager.getColor(any<String>()) } returns null

        val colorSlots = mutableListOf<Any?>()
        every { UIManager.put(any<String>(), captureNullable(colorSlots)) } returns null

        val element = SearchResultsElement()
        element.apply(testColor)

        val colors = colorSlots.filterIsInstance<Color>()
        assertTrue(colors.isNotEmpty(), "Should produce colors with accent fallback")
        for (color in colors) {
            assertEquals(255, color.alpha, "Accent-fallback colors must be opaque")
        }
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
        assertEquals(AccentGroup.INTERACTIVE, AccentElementId.MATCHING_TAG.group)
    }

    private class EditorSchemeStore(
        schemeName: String = "_@user_Ayu Islands Mirage",
    ) {
        private val originalColor = Color(0x12, 0x34, 0x56)
        private val externalColor = Color(0x65, 0x43, 0x21)
        private val originalAttributes = attributes(Color(0x21, 0x43, 0x65))
        private val externalAttributes = attributes(Color(0x56, 0x34, 0x12))
        private val colors = mutableMapOf<ColorKey, Color?>()
        private val textAttributes = mutableMapOf<TextAttributesKey, TextAttributes?>()
        private val touchedColors = linkedSetOf<ColorKey>()
        private val touchedAttributes = linkedSetOf<TextAttributesKey>()

        val scheme: EditorColorsScheme =
            mockk(relaxed = true) {
                every { name } returns schemeName
                every { defaultBackground } returns Color(0x1F, 0x24, 0x30)
                every { getColor(any()) } answers {
                    val key = firstArg<ColorKey>()
                    if (!colors.containsKey(key)) colors[key] = originalColor
                    colors[key]
                }
                every { setColor(any(), any()) } answers {
                    val key = firstArg<ColorKey>()
                    touchedColors.add(key)
                    colors[key] = secondArg()
                }
                every { getAttributes(any<TextAttributesKey>()) } answers {
                    val key = firstArg<TextAttributesKey>()
                    if (!textAttributes.containsKey(key)) textAttributes[key] = originalAttributes.clone()
                    textAttributes[key]
                }
                every { setAttributes(any(), any()) } answers {
                    val key = firstArg<TextAttributesKey>()
                    touchedAttributes.add(key)
                    textAttributes[key] = secondArg<TextAttributes?>()?.clone()
                }
            }

        fun replaceTouchedValues() {
            touchedColors.forEach { key -> colors[key] = externalColor }
            touchedAttributes.forEach { key -> textAttributes[key] = externalAttributes.clone() }
        }

        fun assertWasChanged(element: AccentElement) {
            assertTrue(
                touchedColors.isNotEmpty() || touchedAttributes.isNotEmpty(),
                "${element.displayName} must exercise at least one editor scheme key",
            )
        }

        fun assertOriginalValues(element: AccentElement) {
            touchedColors.forEach { key ->
                assertEquals(originalColor, colors[key], "${element.displayName} must restore its original color")
            }
            touchedAttributes.forEach { key ->
                assertEquals(
                    originalAttributes,
                    textAttributes[key],
                    "${element.displayName} must restore its original attributes",
                )
            }
        }

        fun assertExternalValues(element: AccentElement) {
            touchedColors.forEach { key ->
                assertEquals(externalColor, colors[key], "${element.displayName} must preserve an external color")
            }
            touchedAttributes.forEach { key ->
                assertEquals(
                    externalAttributes,
                    textAttributes[key],
                    "${element.displayName} must preserve external attributes",
                )
            }
        }

        companion object {
            private fun attributes(foreground: Color): TextAttributes =
                TextAttributes().apply {
                    foregroundColor = foreground
                    backgroundColor = Color.BLACK
                    effectColor = Color.CYAN
                    errorStripeColor = Color.MAGENTA
                    effectType = EffectType.BOLD_DOTTED_LINE
                    fontType = 3
                }
        }
    }
}
