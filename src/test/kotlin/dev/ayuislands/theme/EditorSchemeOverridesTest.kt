package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorSchemeOverridesTest {
    private val editorColorsManager = mockk<EditorColorsManager>()
    private val colorKey = mockk<ColorKey>()
    private val attributesKey = mockk<TextAttributesKey>()
    private val elementOwner = EditorSchemeOwner.Element(AccentElementId.BRACKET_MATCH)

    @BeforeTest
    fun setUp() {
        AyuEditorSchemeScope.resetClaims()
        mockkObject(AyuVariant.Companion)
        mockkStatic(EditorColorsManager::class)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { EditorColorsManager.getInstance() } returns editorColorsManager
        every { editorColorsManager.allSchemes } answers { arrayOf(editorColorsManager.globalScheme) }
        every { colorKey.externalName } returns "TEST_COLOR"
        every { attributesKey.externalName } returns "TEST_ATTRIBUTES"
    }

    @AfterTest
    fun tearDown() {
        AyuEditorSchemeScope.resetClaims()
        unmockkAll()
    }

    @Test
    fun `repeated color writes restore the first user value`() {
        val colors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        every { editorColorsManager.globalScheme } returns scheme(colors = colors)

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.YELLOW)
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(Color.RED, colors[colorKey])
    }

    @Test
    fun `external color change relinquishes ownership across reapply and restore`() {
        val colors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        every { editorColorsManager.globalScheme } returns scheme(colors = colors)

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        colors[colorKey] = Color.GREEN
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.YELLOW)
        AyuEditorSchemeScope.restore(elementOwner)
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.BLUE)

        assertEquals(Color.GREEN, colors[colorKey])
    }

    @Test
    fun `null color baseline remains null after restore`() {
        val colors = mutableMapOf<ColorKey, Color?>()
        every { editorColorsManager.globalScheme } returns scheme(colors = colors)

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        AyuEditorSchemeScope.restore(elementOwner)

        assertNull(colors[colorKey])
    }

    @Test
    fun `attribute snapshot is isolated from later mutation of the source object`() {
        val original = fullAttributes(Color.RED)
        val expected = original.clone()
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(attributesKey to original)
        every { editorColorsManager.globalScheme } returns scheme(attributes = attributes)

        AyuEditorSchemeScope.writeAttributes(elementOwner, attributesKey, fullAttributes(Color.ORANGE))
        original.foregroundColor = Color.GREEN
        original.fontType = 0
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(expected, attributes[attributesKey])
    }

    @Test
    fun `external attribute change relinquishes ownership`() {
        val attributes =
            mutableMapOf<TextAttributesKey, TextAttributes?>(
                attributesKey to fullAttributes(Color.RED),
            )
        every { editorColorsManager.globalScheme } returns scheme(attributes = attributes)

        AyuEditorSchemeScope.writeAttributes(elementOwner, attributesKey, fullAttributes(Color.ORANGE))
        val external = fullAttributes(Color.GREEN)
        attributes[attributesKey] = external
        AyuEditorSchemeScope.writeAttributes(elementOwner, attributesKey, fullAttributes(Color.YELLOW))
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(external, attributes[attributesKey])
    }

    @Test
    fun `same-name schemes keep independent original values`() {
        val firstColors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        val secondColors = mutableMapOf<ColorKey, Color?>(colorKey to Color.BLUE)
        val firstScheme = scheme(colors = firstColors)
        val secondScheme = scheme(colors = secondColors)
        var currentScheme = firstScheme
        every { editorColorsManager.globalScheme } answers { currentScheme }

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        currentScheme = secondScheme
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.YELLOW)
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(Color.RED, firstColors[colorKey])
        assertEquals(Color.BLUE, secondColors[colorKey])
    }

    @Test
    fun `only an explicit disable enable transition rearms a relinquished element`() {
        val colors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        every { editorColorsManager.globalScheme } returns scheme(colors = colors)
        AyuEditorSchemeScope.observeElementEnabled(AccentElementId.BRACKET_MATCH, true)
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        colors[colorKey] = Color.GREEN
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.YELLOW)

        AyuEditorSchemeScope.observeElementEnabled(AccentElementId.BRACKET_MATCH, true)
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.BLUE)
        assertEquals(Color.GREEN, colors[colorKey])

        AyuEditorSchemeScope.observeElementEnabled(AccentElementId.BRACKET_MATCH, false)
        AyuEditorSchemeScope.observeElementEnabled(AccentElementId.BRACKET_MATCH, true)
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.BLUE)
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(Color.GREEN, colors[colorKey])
    }

    @Test
    fun `persisted ownership restores the user value after runtime state is lost`() {
        val colors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        val scheme = scheme(colors = colors)
        every { editorColorsManager.globalScheme } returns scheme

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        AyuEditorSchemeScope.resetClaims()
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.YELLOW)
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(Color.RED, colors[colorKey])
    }

    @Test
    fun `editable copy inherits the canonical restoration ledger`() {
        val canonicalColors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        val canonical = scheme(name = "Ayu Islands Mirage", colors = canonicalColors)
        var current = canonical
        every { editorColorsManager.globalScheme } answers { current }

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        val editableColors = canonicalColors.toMutableMap()
        val editable = scheme(colors = editableColors)
        every { editorColorsManager.allSchemes } returns arrayOf(canonical, editable)
        current = editable

        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(Color.RED, editableColors[colorKey])
    }

    private fun scheme(
        name: String = "_@user_Ayu Islands Mirage",
        colors: MutableMap<ColorKey, Color?> = mutableMapOf(),
        attributes: MutableMap<TextAttributesKey, TextAttributes?> = mutableMapOf(),
    ): EditorColorsScheme =
        mockk(relaxed = true) {
            val metadata = Properties()
            every { this@mockk.name } returns name
            every { metaProperties } returns metadata
            every { getColor(any()) } answers { colors[firstArg()] }
            every { setColor(any(), any()) } answers {
                colors[firstArg()] = secondArg()
            }
            every { getAttributes(any<TextAttributesKey>()) } answers { attributes[firstArg()] }
            every { setAttributes(any(), any()) } answers {
                attributes[firstArg()] = secondArg()
            }
        }

    private fun fullAttributes(foreground: Color): TextAttributes =
        TextAttributes().apply {
            foregroundColor = foreground
            backgroundColor = Color.BLACK
            effectColor = Color.CYAN
            errorStripeColor = Color.MAGENTA
            effectType = EffectType.WAVE_UNDERSCORE
            fontType = 3
        }
}
