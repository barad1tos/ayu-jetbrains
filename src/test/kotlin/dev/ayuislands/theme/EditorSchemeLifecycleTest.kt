package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.elements.BracketFadeManager
import dev.ayuislands.accent.elements.BracketMatchElement
import dev.ayuislands.accent.elements.CaretRowElement
import dev.ayuislands.accent.elements.ProgressBarElement
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorSchemeLifecycleTest {
    private val manager = mockk<EditorColorsManager>()
    private val colorKeys = mutableMapOf<String, ColorKey>()
    private val attributeKeys = mutableMapOf<String, TextAttributesKey>()
    private var variant: AyuVariant? = AyuVariant.MIRAGE
    private lateinit var currentScheme: EditorColorsScheme

    @BeforeTest
    fun setUp() {
        AyuEditorSchemeScope.resetClaims()
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns manager
        every { manager.globalScheme } answers { currentScheme }

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } answers { variant }

        mockkStatic(ColorKey::class)
        every { ColorKey.find(any<String>()) } answers {
            colorKeys.getOrPut(firstArg()) { mockk(relaxed = true) }
        }
        mockkStatic(TextAttributesKey::class)
        every { TextAttributesKey.find(any<String>()) } answers {
            attributeKeys.getOrPut(firstArg()) { mockk(relaxed = true) }
        }
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        mockkStatic(UIManager::class)
        mockkObject(BracketFadeManager)
        every { BracketFadeManager.activate(any()) } returns Unit
        every { BracketFadeManager.deactivate() } returns Unit
    }

    @AfterTest
    fun tearDown() {
        AyuEditorSchemeScope.resetClaims()
        unmockkAll()
    }

    @Test
    fun `scheme eligibility matrix changes only the active matching Ayu identity`() {
        val cases =
            listOf(
                "Ayu Islands Mirage" to true,
                "_@user_Ayu Islands Mirage" to true,
                "Solarized Dark" to false,
                "_@user_Solarized Dark" to false,
                "Ayu Islands Dark" to false,
            )

        for ((name, shouldApply) in cases) {
            AyuEditorSchemeScope.resetClaims()
            val store = SchemeStore(name)
            currentScheme = store.scheme
            val caret = CaretRowElement()
            val brace = BracketMatchElement()

            caret.apply(Color.ORANGE)
            brace.apply(Color.ORANGE)

            if (shouldApply) {
                store.assertChanged()
                caret.revert()
                brace.revert()
            }
            store.assertOriginalValues()
        }
    }

    @Test
    fun `manual and appearance variant round trips restore every claimed scheme`() {
        val mirage = SchemeStore("Ayu Islands Mirage")
        val dark = SchemeStore("_@user_Ayu Islands Dark")
        val light = SchemeStore("Ayu Islands Light")
        val foreign = SchemeStore("Solarized Dark")
        val caret = CaretRowElement()
        val brace = BracketMatchElement()

        listOf(
            AyuVariant.MIRAGE to mirage,
            AyuVariant.DARK to dark,
            AyuVariant.LIGHT to light,
        ).forEach { (nextVariant, store) ->
            variant = nextVariant
            currentScheme = store.scheme
            caret.apply(Color.ORANGE)
            brace.apply(Color.ORANGE)
            store.assertChanged()
        }

        variant = null
        currentScheme = foreign.scheme
        caret.revert()
        brace.revert()

        listOf(mirage, dark, light, foreign).forEach(SchemeStore::assertOriginalValues)
    }

    @Test
    fun `user edits win before during and after ownership transitions`() {
        val store = SchemeStore("_@user_Ayu Islands Mirage")
        currentScheme = store.scheme
        val caret = CaretRowElement()

        store.replaceCaret(Color.PINK)
        caret.apply(Color.ORANGE)
        caret.revert()
        store.assertCaret(Color.PINK)

        caret.apply(Color.ORANGE)
        store.replaceCaret(Color.GREEN)
        caret.apply(Color.YELLOW)
        caret.revert()
        store.assertCaret(Color.GREEN)

        store.replaceCaret(Color.BLUE)
        caret.apply(Color.ORANGE)
        caret.revert()
        store.assertCaret(Color.BLUE)
    }

    @Test
    fun `scheme replacement between attribute read and write leaves both identities unchanged`() {
        val first = SchemeStore("Ayu Islands Mirage")
        val second = SchemeStore("_@user_Ayu Islands Mirage")
        var readCount = 0
        every { manager.globalScheme } answers {
            if (readCount++ == 0) first.scheme else second.scheme
        }

        BracketMatchElement().apply(Color.ORANGE)

        first.assertOriginalValues()
        second.assertOriginalValues()
    }

    @Test
    fun `queued editor apply ignores a replaced scheme identity`() {
        val first = SchemeStore("Ayu Islands Mirage")
        val second = SchemeStore("_@user_Ayu Islands Mirage")
        currentScheme = first.scheme
        val callback = slot<Runnable>()
        every { SwingUtilities.isEventDispatchThread() } returns false
        every { SwingUtilities.invokeLater(capture(callback)) } returns Unit

        ProgressBarElement().apply(Color.ORANGE)
        currentScheme = second.scheme
        callback.captured.run()

        first.assertOriginalValues()
        second.assertOriginalValues()
    }

    private inner class SchemeStore(
        name: String,
    ) {
        private val originalColor = Color(0x12, 0x34, 0x56)
        private val originalAttributes = attributes(Color(0x21, 0x43, 0x65))
        private val colors =
            mutableMapOf<ColorKey, Color?>(
                ColorKey.find("CARET_ROW_COLOR") to originalColor,
                ColorKey.find("CARET_COLOR") to originalColor,
                ColorKey.find("LINE_NUMBER_ON_CARET_ROW_COLOR") to originalColor,
                ColorKey.find("PROGRESS_BAR_TRACK") to originalColor,
            )
        private val textAttributes =
            mutableMapOf<TextAttributesKey, TextAttributes?>(
                TextAttributesKey.find("MATCHED_BRACE_ATTRIBUTES") to originalAttributes.clone(),
            )

        val scheme: EditorColorsScheme =
            mockk(relaxed = true) {
                every { this@mockk.name } returns name
                every { defaultBackground } returns Color(0x1F, 0x24, 0x30)
                every { getColor(any()) } answers {
                    val key = firstArg<ColorKey>()
                    colors.getOrPut(key) { originalColor }
                }
                every { setColor(any(), any()) } answers {
                    colors[firstArg()] = secondArg()
                }
                every { getAttributes(any<TextAttributesKey>()) } answers {
                    val key = firstArg<TextAttributesKey>()
                    textAttributes.getOrPut(key) { originalAttributes.clone() }
                }
                every { setAttributes(any(), any()) } answers {
                    textAttributes[firstArg()] = secondArg<TextAttributes?>()?.clone()
                }
            }

        fun replaceCaret(color: Color) {
            listOf("CARET_ROW_COLOR", "CARET_COLOR", "LINE_NUMBER_ON_CARET_ROW_COLOR")
                .forEach { key -> colors[ColorKey.find(key)] = color }
        }

        fun assertCaret(color: Color) {
            listOf("CARET_ROW_COLOR", "CARET_COLOR", "LINE_NUMBER_ON_CARET_ROW_COLOR")
                .forEach { key -> assertEquals(color, colors[ColorKey.find(key)]) }
        }

        fun assertChanged() {
            assertEquals(Color.ORANGE, colors[ColorKey.find("CARET_COLOR")])
            val braceAttributes = textAttributes[TextAttributesKey.find("MATCHED_BRACE_ATTRIBUTES")]
            assertEquals(Color.ORANGE, braceAttributes?.foregroundColor)
        }

        fun assertOriginalValues() {
            colors.values.forEach { value -> assertEquals(originalColor, value) }
            textAttributes.values.forEach { value -> assertEquals(originalAttributes, value) }
        }
    }

    private fun attributes(foreground: Color): TextAttributes =
        TextAttributes().apply {
            foregroundColor = foreground
            backgroundColor = Color.BLACK
            effectColor = Color.CYAN
            errorStripeColor = Color.MAGENTA
            effectType = EffectType.WAVE_UNDERSCORE
            fontType = 3
        }
}
