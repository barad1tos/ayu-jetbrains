package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.theme.SchemeAttributeCell
import dev.ayuislands.theme.SchemeAttributeSeed
import dev.ayuislands.theme.SchemeColorCell
import dev.ayuislands.theme.SchemeColorSeed
import dev.ayuislands.theme.SchemeComparison
import dev.ayuislands.theme.SchemeEquality
import dev.ayuislands.theme.SchemePruningPlan
import dev.ayuislands.theme.SchemeSeedPolicy
import dev.ayuislands.theme.captureAttributeFacts
import dev.ayuislands.theme.prepareSchemePruning
import dev.ayuislands.theme.readSchemeAttributes
import dev.ayuislands.theme.readSchemeColor
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SchemeSeedPolicyTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `pruning preparation captures target steps without mutating either native map`() {
        SwingUtilities.invokeAndWait {
            val parent = DefaultColorsScheme()
            val scheme = EditorColorsSchemeImpl(parent)
            val attribute = TextAttributesKey.createTextAttributesKey("AYU_PREPARED_ATTRIBUTE")
            val color = ColorKey.createColorKey("AYU_PREPARED_COLOR")
            val value = TextAttributes().apply { foregroundColor = Color.BLUE }
            scheme.setAttributes(attribute, value)
            scheme.setColor(color, Color.BLUE)
            val beforeAttributes = scheme.directlyDefinedAttributes
            val beforeColors = scheme.directlyDefinedColors
            val plan =
                assertIs<SchemePruningPlan.Prepared>(
                    prepareSchemePruning(parent, beforeAttributes, beforeColors, setOf(attribute), setOf(color)),
                )
            assertSame(parent, plan.parent)
            assertSame(AbstractColorsScheme.INHERITED_ATTRS_MARKER, plan.attributes.getValue(attribute).single())
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, plan.colors.getValue(color).single())
            assertSame(value, scheme.directlyDefinedAttributes[attribute.externalName])
            assertSame(Color.BLUE, scheme.directlyDefinedColors[color])
            assertEquals(beforeAttributes.keys, scheme.directlyDefinedAttributes.keys)
            assertEquals(beforeColors.keys, scheme.directlyDefinedColors.keys)

            plan.attributes.forEach { (key, steps) -> steps.forEach { scheme.setAttributes(key, it) } }
            plan.colors.forEach { (key, steps) -> steps.forEach { scheme.setColor(key, it) } }
            scheme.optimizeAttributeMap()
            assertSame(SchemeAttributeCell.Absent, readSchemeAttributes(scheme, attribute))
            assertSame(SchemeColorCell.Absent, readSchemeColor(scheme, color))
        }
    }

    @Test
    fun `pruning preparation rejects collateral deletion and treats already absent targets as unchanged`() {
        SwingUtilities.invokeAndWait {
            val parent = DefaultColorsScheme()
            val attribute = TextAttributesKey.createTextAttributesKey("AYU_PREPARED_COLLATERAL")
            val color = ColorKey.createColorKey("AYU_PREPARED_FOREIGN_COLOR")
            val values = mapOf(attribute.externalName to TextAttributes())
            val colors = mapOf(color to AbstractColorsScheme.INHERITED_COLOR_MARKER)
            assertSame(
                SchemePruningPlan.Unavailable,
                prepareSchemePruning(parent, values, colors, setOf(attribute), emptySet()),
            )
            assertSame(
                SchemePruningPlan.Unchanged,
                prepareSchemePruning(parent, emptyMap(), colors, setOf(attribute), emptySet()),
            )
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, colors.getValue(color))
        }
    }

    @Test
    fun `ordinary empty parent uses direct seed and never manufactures an inherited marker`() {
        SwingUtilities.invokeAndWait {
            val parent =
                DefaultColorsScheme().apply {
                    readExternal(
                        JDOMUtil.load(
                            """
                            <scheme name="Seed" version="142" default_scheme="true">
                              <attributes><option name="AYU_EMPTY_SEED"><value /></option></attributes>
                            </scheme>
                            """.trimIndent(),
                        ),
                    )
                }
            val key = TextAttributesKey.find("AYU_EMPTY_SEED")
            val scheme = EditorColorsSchemeImpl(parent)
            val parentCell = assertIs<SchemeAttributeCell.Value>(readSchemeAttributes(parent, key))
            val current = TextAttributes().apply { foregroundColor = Color.BLUE }
            scheme.setAttributes(key, current)
            val comparison =
                SchemeComparison.attributes(
                    captureAttributeFacts(parentCell.attributes),
                    captureAttributeFacts(current),
                )
            assertEquals(
                SchemeAttributeSeed.PARENT,
                SchemeSeedPolicy.attributes(parentCell, readSchemeAttributes(scheme, key), comparison),
            )
            scheme.setAttributes(key, parentCell.attributes)
            assertSame(parentCell.attributes, scheme.directlyDefinedAttributes[key.externalName])
            scheme.optimizeAttributeMap()
            assertSame(SchemeAttributeCell.Absent, readSchemeAttributes(scheme, key))

            val ordinaryEmpty = TextAttributes()
            scheme.setAttributes(key, ordinaryEmpty)
            val equal =
                SchemeComparison.attributes(
                    captureAttributeFacts(parentCell.attributes),
                    captureAttributeFacts(ordinaryEmpty),
                )
            assertEquals(SchemeEquality.EQUAL, equal)
            assertEquals(
                SchemeAttributeSeed.NONE,
                SchemeSeedPolicy.attributes(parentCell, readSchemeAttributes(scheme, key), equal),
            )
            scheme.optimizeAttributeMap()
            assertSame(SchemeAttributeCell.Absent, readSchemeAttributes(scheme, key))
            scheme.setAttributes(key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            assertEquals(
                SchemeAttributeSeed.UNAVAILABLE,
                SchemeSeedPolicy.attributes(parentCell, SchemeAttributeCell.Inherited, equal),
            )
            assertSame(AbstractColorsScheme.INHERITED_ATTRS_MARKER, scheme.directlyDefinedAttributes[key.externalName])
        }
    }

    @Test
    fun `attribute seed preserves absent cells and rejects unknown equality before any setter`() {
        val value = SchemeAttributeCell.Value(TextAttributes())
        assertEquals(
            SchemeAttributeSeed.NONE,
            SchemeSeedPolicy.attributes(value, SchemeAttributeCell.Absent, SchemeEquality.UNKNOWN),
        )
        assertEquals(
            SchemeAttributeSeed.INHERITED,
            SchemeSeedPolicy.attributes(SchemeAttributeCell.Absent, value, SchemeEquality.UNKNOWN),
        )
        assertEquals(
            SchemeAttributeSeed.INHERITED,
            SchemeSeedPolicy.attributes(SchemeAttributeCell.Inherited, value, SchemeEquality.UNKNOWN),
        )
        assertEquals(
            SchemeAttributeSeed.NONE,
            SchemeSeedPolicy.attributes(
                SchemeAttributeCell.Absent,
                SchemeAttributeCell.Inherited,
                SchemeEquality.UNKNOWN,
            ),
        )
        assertEquals(
            SchemeAttributeSeed.UNAVAILABLE,
            SchemeSeedPolicy.attributes(value, value, SchemeEquality.UNKNOWN),
        )
        assertEquals(
            SchemeAttributeSeed.PARENT,
            SchemeSeedPolicy.attributes(value, SchemeAttributeCell.Inherited, SchemeEquality.DIFFERENT),
        )
    }

    @Test
    fun `color barrier restores an opaque parent without evaluating its color`() {
        SwingUtilities.invokeAndWait {
            val parent =
                object : Color(0xFF1234) {
                    override fun getRGB(): Int = error("Opaque RGB must not run")

                    override fun equals(other: Any?): Boolean = error("Opaque equality must not run")

                    override fun hashCode(): Int = error("Opaque hash must not run")
                }
            val scheme = EditorColorsSchemeImpl(DefaultColorsScheme())
            val key = ColorKey.createColorKey("AYU_OPAQUE_SEED")
            scheme.setColor(key, Color.BLUE)
            val parentCell = SchemeColorCell.Value(parent)
            assertEquals(
                SchemeColorSeed.BARRIER,
                SchemeSeedPolicy.colors(parentCell, readSchemeColor(scheme, key), SchemeEquality.UNKNOWN),
            )
            scheme.setColor(key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            assertEquals(
                SchemeColorSeed.PARENT,
                SchemeSeedPolicy.colors(parentCell, readSchemeColor(scheme, key), SchemeEquality.UNKNOWN),
            )
            scheme.setColor(key, parent)
            assertSame(parent, assertIs<SchemeColorCell.Value>(readSchemeColor(scheme, key)).color)
            assertEquals(
                SchemeColorSeed.NONE,
                SchemeSeedPolicy.colors(parentCell, readSchemeColor(scheme, key), SchemeEquality.EQUAL),
            )
        }
    }

    @Test
    fun `color seed preserves absence and explicit null separately from inheritance`() {
        val value = SchemeColorCell.Value(Color.BLUE)
        assertEquals(
            SchemeColorSeed.NONE,
            SchemeSeedPolicy.colors(value, SchemeColorCell.Absent, SchemeEquality.UNKNOWN),
        )
        assertEquals(
            SchemeColorSeed.INHERITED,
            SchemeSeedPolicy.colors(SchemeColorCell.Absent, value, SchemeEquality.UNKNOWN),
        )
        assertEquals(
            SchemeColorSeed.INHERITED,
            SchemeSeedPolicy.colors(SchemeColorCell.Inherited, value, SchemeEquality.UNKNOWN),
        )
        assertEquals(
            SchemeColorSeed.NONE,
            SchemeSeedPolicy.colors(SchemeColorCell.Absent, SchemeColorCell.Inherited, SchemeEquality.UNKNOWN),
        )
        assertEquals(
            SchemeColorSeed.NULL,
            SchemeSeedPolicy.colors(SchemeColorCell.Null, value, SchemeEquality.UNKNOWN),
        )
        assertEquals(
            SchemeColorSeed.NONE,
            SchemeSeedPolicy.colors(SchemeColorCell.Null, SchemeColorCell.Null, SchemeEquality.UNKNOWN),
        )
        assertEquals(
            SchemeColorSeed.PARENT,
            SchemeSeedPolicy.colors(value, SchemeColorCell.Null, SchemeEquality.UNKNOWN),
        )
    }
}
