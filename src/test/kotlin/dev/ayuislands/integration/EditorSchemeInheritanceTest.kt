package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.AttributesFlyweight
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.theme.SchemeAttributeCell
import dev.ayuislands.theme.SchemeAttributeFacts
import dev.ayuislands.theme.SchemeColorCell
import dev.ayuislands.theme.SchemeColorFacts
import dev.ayuislands.theme.captureAttributeFacts
import dev.ayuislands.theme.captureColorFacts
import dev.ayuislands.theme.readSchemeAttributes
import dev.ayuislands.theme.readSchemeColor
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Font
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Qualifies native inheritance semantics before a Studio adapter can promise exact rollback. */
class EditorSchemeInheritanceTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `native comparison capture freezes scalar facts from the same flyweight`() {
        SwingUtilities.invokeAndWait {
            val attributes =
                TextAttributes().apply {
                    foregroundColor = Color.RED
                    errorStripeColor = Color.BLUE
                    fontType = Font.BOLD
                }
            val captured = assertIs<SchemeAttributeFacts.Native>(captureAttributeFacts(attributes))
            val flyweight = attributes.flyweight
            attributes.fontType = Font.ITALIC
            assertSame(attributes, captured.identity)
            assertSame(flyweight, captured.flyweight)
            assertEquals(Font.BOLD, captured.fields.fontType)
            assertEquals(Color.RED.rgb, assertIs<SchemeColorFacts.Plain>(captured.fields.foreground).argb)
            assertSame(Color.BLUE, assertIs<SchemeColorFacts.Plain>(captured.fields.errorStripe).identity)
            assertIs<SchemeColorFacts.Marker>(captureColorFacts(AbstractColorsScheme.INHERITED_COLOR_MARKER))
            assertIs<SchemeColorFacts.Marker>(captureColorFacts(AbstractColorsScheme.NULL_COLOR_MARKER))
            assertNull(captureAttributeFacts(null))
            assertNull(captureColorFacts(null))
        }
    }

    @Test
    fun `comparison capture never evaluates opaque attributes or colors`() {
        SwingUtilities.invokeAndWait {
            var isArmed = false
            val opaque =
                object : Color(0xAC01FE) {
                    override fun getRGB(): Int {
                        check(!isArmed) { "Opaque RGB must not run" }
                        return super.getRGB()
                    }

                    override fun hashCode(): Int {
                        check(!isArmed) { "Opaque hash must not run" }
                        return super.hashCode()
                    }
                }
            val native = TextAttributes().apply { backgroundColor = opaque }
            val foreign =
                object : TextAttributes() {
                    override fun getFlyweight(): AttributesFlyweight = error("Opaque attributes must not be inspected")
                }
            isArmed = true
            assertSame(opaque, assertIs<SchemeColorFacts.Opaque>(captureColorFacts(opaque)).identity)
            assertSame(foreign, assertIs<SchemeAttributeFacts.Opaque>(captureAttributeFacts(foreign)).identity)
            val captured = assertIs<SchemeAttributeFacts.Native>(captureAttributeFacts(native))
            assertSame(opaque, assertIs<SchemeColorFacts.Opaque>(captured.fields.background).identity)
        }
    }

    @Test
    fun `native local color cells distinguish absence inheritance and explicit null`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            val key = ColorKey.createColorKey("AYU_STUDIO_LOCAL_COLOR")
            assertSame(SchemeColorCell.Absent, readSchemeColor(scheme, key))
            scheme.setColor(key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            assertSame(SchemeColorCell.Inherited, readSchemeColor(scheme, key))
            scheme.setColor(key, AbstractColorsScheme.NULL_COLOR_MARKER)
            assertSame(SchemeColorCell.Null, readSchemeColor(scheme, key))
            val color = Color(0x123456)
            scheme.setColor(key, color)
            assertSame(color, assertIs<SchemeColorCell.Value>(readSchemeColor(scheme, key)).color)
            assertTrue(scheme.directlyDefinedColors.containsKey(key))
        }
    }

    @Test
    fun `native local attribute cells distinguish absence inheritance and ordinary empty values`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            val key = TextAttributesKey.createTextAttributesKey("AYU_STUDIO_LOCAL_ATTRIBUTES")
            assertSame(SchemeAttributeCell.Absent, readSchemeAttributes(scheme, key))
            scheme.setAttributes(key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            assertSame(SchemeAttributeCell.Inherited, readSchemeAttributes(scheme, key))
            scheme.setAttributes(key, TextAttributes().apply { fontType = Font.BOLD })
            val empty = TextAttributes()
            scheme.setAttributes(key, empty)
            assertSame(empty, assertIs<SchemeAttributeCell.Value>(readSchemeAttributes(scheme, key)).attributes)
            assertTrue(scheme.directlyDefinedAttributes.containsKey(key.externalName))
        }
    }

    @Test
    fun `native local capture retains foreign objects without effective reads or cloning`() {
        SwingUtilities.invokeAndWait {
            var isArmed = false
            val scheme =
                object : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
                    override fun getAttributes(key: TextAttributesKey?): TextAttributes? {
                        check(!isArmed) { "Effective attributes must not be read" }
                        return super.getAttributes(key)
                    }

                    override fun getColor(key: ColorKey?): Color? {
                        check(!isArmed) { "Effective colors must not be read" }
                        return super.getColor(key)
                    }
                }
            val attributeKey = TextAttributesKey.createTextAttributesKey("AYU_STUDIO_FOREIGN_ATTRIBUTES")
            val colorKey = ColorKey.createColorKey("AYU_STUDIO_FOREIGN_COLOR")
            val foreignAttributes =
                object : TextAttributes() {
                    override fun clone(): TextAttributes = error("Native capture must not clone a foreign object")
                }.apply { foregroundColor = Color.MAGENTA }
            val foreignColor =
                object : Color(0xABCDEF) {
                    override fun getRGB(): Int {
                        check(!isArmed) { "Native capture must not evaluate a foreign color" }
                        return super.getRGB()
                    }
                }
            scheme.setAttributes(attributeKey, foreignAttributes)
            scheme.setColor(colorKey, foreignColor)
            scheme.metaProperties["foreign"] = "  IA;unknown\nЖ\t"
            val before = scheme.metaProperties.toMap()
            isArmed = true

            val attributes = assertIs<SchemeAttributeCell.Value>(readSchemeAttributes(scheme, attributeKey))
            val color = assertIs<SchemeColorCell.Value>(readSchemeColor(scheme, colorKey))
            assertSame(foreignAttributes, attributes.attributes)
            assertSame(foreignColor, color.color)
            assertEquals(before, scheme.metaProperties.toMap())
        }
    }

    @Test
    fun `inherited marker does not restore the value or membership of an absent local key`() {
        SwingUtilities.invokeAndWait {
            val fallback = TextAttributesKey.createTextAttributesKey("AYU_STUDIO_ABSENCE_FALLBACK")
            val key = TextAttributesKey.createTextAttributesKey("AYU_STUDIO_ABSENCE_VALUE", fallback)
            val parent = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            val child = EditorColorsSchemeImpl(parent)
            parent.setAttributes(key, attributes(Color.RED))
            child.setAttributes(fallback, attributes(Color.GREEN))

            assertFalse(child.directlyDefinedAttributes.containsKey(key.externalName))
            assertEquals(Color.RED, child.getAttributes(key).foregroundColor)

            child.setAttributes(key, attributes(Color.BLUE))
            assertEquals(Color.BLUE, child.getAttributes(key).foregroundColor)
            child.setAttributes(key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)

            assertTrue(child.directlyDefinedAttributes.containsKey(key.externalName))
            assertSame(
                AbstractColorsScheme.INHERITED_ATTRS_MARKER,
                child.directlyDefinedAttributes.getValue(key.externalName),
            )
            assertEquals(Color.GREEN, child.getAttributes(key).foregroundColor)
            assertEquals(Color.RED, parent.getAttributes(key).foregroundColor)
        }
    }

    private fun attributes(color: Color): TextAttributes = TextAttributes().apply { foregroundColor = color }
}
