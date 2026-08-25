package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
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
import kotlin.test.assertFalse
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
    fun `inherited color remains inherited after restore`() {
        val colors = mutableMapOf<ColorKey, Color?>()
        val inheritedColors = mutableMapOf(colorKey to Color.RED)
        val scheme = scheme(colors = colors, inheritedColors = inheritedColors)
        every { editorColorsManager.globalScheme } returns scheme

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        AyuEditorSchemeScope.restore(elementOwner)
        inheritedColors[colorKey] = Color.GREEN

        assertFalse(colors.containsKey(colorKey))
        assertEquals(Color.GREEN, scheme.getColor(colorKey))
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
    fun `inherited attributes remain inherited after restore`() {
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>()
        val inheritedAttributes = mutableMapOf(attributesKey to fullAttributes(Color.RED))
        val scheme = scheme(attributes = attributes, inheritedAttributes = inheritedAttributes)
        every { editorColorsManager.globalScheme } returns scheme

        AyuEditorSchemeScope.writeAttributes(elementOwner, attributesKey, fullAttributes(Color.ORANGE))
        AyuEditorSchemeScope.restore(elementOwner)
        inheritedAttributes[attributesKey] = fullAttributes(Color.GREEN)

        assertFalse(attributes.containsKey(attributesKey))
        assertEquals(inheritedAttributes[attributesKey], scheme.getAttributes(attributesKey))
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
    fun `syntax ownership restores exact direct attributes after runtime state is lost`() {
        val syntaxKey = TextAttributesKey.find("TEST_SYNTAX_ATTRIBUTES")
        val original = fullAttributes(Color.RED)
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(syntaxKey to original)
        val scheme = scheme(attributes = attributes)

        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.ORANGE),
        )
        EditorSchemeOverrides.reset()
        EditorSchemeOverrides.restore(scheme, EditorSchemeOwner.Syntax)

        assertEquals(original, attributes[syntaxKey])
    }

    @Test
    fun `inactive syntax cell rearms after reset without overwriting an active external edit`() {
        val syntaxKey = TextAttributesKey.find("TEST_REARMED_SYNTAX_ATTRIBUTES")
        val original = fullAttributes(Color.RED)
        val external = fullAttributes(Color.GREEN)
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(syntaxKey to original)
        val scheme = scheme(attributes = attributes)

        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.ORANGE),
        )
        attributes[syntaxKey] = external
        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.YELLOW),
        )
        EditorSchemeOverrides.rearm(
            EditorSchemeOwner.Syntax,
            listOf(scheme),
            mapOf(scheme to setOf(syntaxKey.externalName)),
        )
        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.BLUE),
        )
        assertEquals(external, attributes[syntaxKey], "an active external edit must retain ownership")

        EditorSchemeOverrides.rearm(
            EditorSchemeOwner.Syntax,
            listOf(scheme),
            mapOf(scheme to emptySet()),
        )
        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.BLUE),
        )
        assertEquals(Color.BLUE, attributes[syntaxKey]?.foregroundColor)

        EditorSchemeOverrides.restore(scheme, EditorSchemeOwner.Syntax)
        assertEquals(external, attributes[syntaxKey], "re-added tuning must restore the post-edit user value")
    }

    @Test
    fun `targeted syntax rearm leaves other schemes relinquished`() {
        val syntaxKey = TextAttributesKey.find("TEST_TARGETED_REARM_ATTRIBUTES")
        val firstExternal = fullAttributes(Color.GREEN)
        val secondExternal = fullAttributes(Color.CYAN)
        val firstAttributes = mutableMapOf<TextAttributesKey, TextAttributes?>(syntaxKey to fullAttributes(Color.RED))
        val secondAttributes = mutableMapOf<TextAttributesKey, TextAttributes?>(syntaxKey to fullAttributes(Color.BLUE))
        val firstScheme = scheme(name = "First", attributes = firstAttributes)
        val secondScheme = scheme(name = "Second", attributes = secondAttributes)

        listOf(firstScheme, secondScheme).forEach { scheme ->
            EditorSchemeOverrides.writeAttributes(
                scheme,
                EditorSchemeOwner.Syntax,
                syntaxKey,
                fullAttributes(Color.ORANGE),
            )
        }
        firstAttributes[syntaxKey] = firstExternal
        secondAttributes[syntaxKey] = secondExternal
        listOf(firstScheme, secondScheme).forEach { scheme ->
            EditorSchemeOverrides.writeAttributes(
                scheme,
                EditorSchemeOwner.Syntax,
                syntaxKey,
                fullAttributes(Color.YELLOW),
            )
        }

        EditorSchemeOverrides.rearm(
            EditorSchemeOwner.Syntax,
            listOf(firstScheme),
            mapOf(firstScheme to emptySet()),
        )
        EditorSchemeOverrides.writeAttributes(
            firstScheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.MAGENTA),
        )
        EditorSchemeOverrides.writeAttributes(
            secondScheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.MAGENTA),
        )

        assertEquals(Color.MAGENTA, firstAttributes[syntaxKey]?.foregroundColor)
        assertEquals(secondExternal, secondAttributes[syntaxKey])
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
        inheritedColors: MutableMap<ColorKey, Color> = mutableMapOf(),
        inheritedAttributes: MutableMap<TextAttributesKey, TextAttributes> = mutableMapOf(),
    ): AbstractColorsScheme =
        mockk<AbstractColorsScheme>(relaxed = true) {
            val metadata = Properties()
            every { this@mockk.name } returns name
            every { metaProperties } returns metadata
            every { directlyDefinedColors } answers {
                colors.mapValues { (_, value) -> value ?: AbstractColorsScheme.NULL_COLOR_MARKER }
            }
            every { directlyDefinedAttributes } answers {
                attributes
                    .mapNotNull { (key, value) ->
                        value?.let { key.externalName to it }
                    }.toMap()
            }
            every { getColor(any()) } answers {
                val key = firstArg<ColorKey>()
                if (colors.containsKey(key)) colors[key] else inheritedColors[key]
            }
            every { setColor(any(), any()) } answers {
                val key = firstArg<ColorKey>()
                val value = secondArg<Color?>()
                if (value === AbstractColorsScheme.INHERITED_COLOR_MARKER) {
                    colors.remove(key)
                } else {
                    colors[key] = value
                }
            }
            every { getAttributes(any<TextAttributesKey>()) } answers {
                val key = firstArg<TextAttributesKey>()
                if (attributes.containsKey(key)) attributes[key] else inheritedAttributes[key]
            }
            every { setAttributes(any(), any()) } answers {
                val key = firstArg<TextAttributesKey>()
                val value = secondArg<TextAttributes?>()
                if (value === AbstractColorsScheme.INHERITED_ATTRS_MARKER) {
                    attributes.remove(key)
                } else {
                    attributes[key] = value
                }
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
