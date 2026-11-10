package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.SchemeState
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.CheckpointEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import org.jdom.Element
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Font
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Qualifies native serialization only; editor-manager file saves and restart recovery are separate gates. */
class EditorSchemePersistenceTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `detached value and metadata writes retain save intent during apply and rollback`() {
        SwingUtilities.invokeAndWait {
            for (metadataOnly in listOf(false, true)) {
                val scheme = newScheme()
                val key = ColorKey.createColorKey("AYU_DETACHED_SAVE_INTENT")
                scheme.setColor(key, Color.RED)
                scheme.setSaveNeeded(false)
                val prepared =
                    EditorSchemeOverrides.checkpoints.prepareWrite(
                        scheme,
                        SchemeEntry.ColorEntry(key),
                        CheckpointEntry(
                            SchemeValue.ColorValue(if (metadataOnly) Color.RED else Color.ORANGE),
                            null,
                            if (metadataOnly) "owned metadata" else null,
                        ),
                        AyuEditorSchemeScope.activeScheme(),
                    )
                assertEquals(SchemeState.UNCHANGED, scheme.schemeState)
                assertTrue(prepared.apply())
                assertEquals(SchemeState.POSSIBLY_CHANGED, scheme.schemeState)
                // A save may finish between Apply and rollback; restoration needs its own save intent.
                scheme.setSaveNeeded(false)
                prepared.restore()
                assertEquals(SchemeState.POSSIBLY_CHANGED, scheme.schemeState)
                assertSame(Color.RED, scheme.directlyDefinedColors[key])
            }
        }
    }

    @Test
    fun `cancelling unused native capture preserves unrelated save intent`() {
        SwingUtilities.invokeAndWait {
            val scheme = newScheme()
            val key = ColorKey.createColorKey("AYU_DETACHED_UNUSED_SAVE")
            scheme.setColor(key, Color.RED)
            scheme.setSaveNeeded(false)
            val prepared =
                EditorSchemeOverrides.checkpoints.prepareWrite(
                    scheme,
                    SchemeEntry.ColorEntry(key),
                    CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, null),
                    AyuEditorSchemeScope.activeScheme(),
                )
            scheme.setSaveNeeded(true)
            prepared.restore()
            assertEquals(SchemeState.POSSIBLY_CHANGED, scheme.schemeState)
            assertSame(Color.RED, scheme.directlyDefinedColors[key])
        }
    }

    @Test
    fun `native mutation then exception retains save intent before the setter returns`() {
        SwingUtilities.invokeAndWait {
            var failWrites = false
            val failure = IllegalStateException("Native setter interrupted after mutation")
            val scheme =
                object : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
                    override fun setColor(
                        key: ColorKey,
                        color: Color?,
                    ) {
                        super.setColor(key, color)
                        if (failWrites) throw failure
                    }
                }
            val colorKey = ColorKey.createColorKey("AYU_DETACHED_FAILED_SAVE")
            scheme.setColor(colorKey, Color.RED)
            scheme.setSaveNeeded(false)
            val prepared =
                EditorSchemeOverrides.checkpoints.prepareWrite(
                    scheme,
                    SchemeEntry.ColorEntry(colorKey),
                    CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, null),
                    AyuEditorSchemeScope.activeScheme(),
                )
            failWrites = true
            assertSame(failure, assertFailsWith<IllegalStateException> { prepared.apply() })
            assertEquals(SchemeState.POSSIBLY_CHANGED, scheme.schemeState)
            assertSame(Color.ORANGE, scheme.directlyDefinedColors[colorKey])
            failWrites = false
            scheme.setSaveNeeded(false)
            prepared.restore()
            assertEquals(SchemeState.POSSIBLY_CHANGED, scheme.schemeState)
            assertSame(Color.RED, scheme.directlyDefinedColors[colorKey])
        }
    }

    @Test
    fun `detached serialization preserves live cells while native XML projects persisted values`() {
        SwingUtilities.invokeAndWait {
            val manager = EditorColorsManager.getInstance()
            val active = manager.globalScheme
            val scheme = newScheme()
            val attributeKey = TextAttributesKey.createTextAttributesKey("AYU_PERSISTENCE_ATTRIBUTES")
            val colorKey = ColorKey.createColorKey("AYU_PERSISTENCE_COLOR")
            val attributes = layeredAttributes()
            val color = Color(0x12345678, true)
            scheme.setAttributes(attributeKey, attributes)
            scheme.setColor(colorKey, color)
            scheme.metaProperties["dev.ayuislands.override.v1.probe"] = "  IA;unknown\nЖ\t"
            scheme.setSaveNeeded(true)
            val metadata = scheme.metaProperties.toMap()
            val state = scheme.schemeState
            val flyweight = attributes.flyweight
            val parent = scheme.parentScheme

            val detached = detachedCopy(scheme)
            val element = Element("scheme")
            detached.writeExternal(element)
            val nativeMetadata: Map<Any, Any> =
                assertNotNull(element.getChild("metaInfo")).getChildren("property").associate {
                    assertNotNull(it.getAttributeValue("name")) to it.text
                }
            assertEquals(metadata, nativeMetadata)
            val restored = newScheme().apply { readExternal(JDOMUtil.load(JDOMUtil.writeElement(element))) }

            assertEquals(metadata, scheme.metaProperties.toMap())
            assertEquals(state, scheme.schemeState)
            assertSame(attributes, scheme.directlyDefinedAttributes[attributeKey.externalName])
            assertSame(flyweight, attributes.flyweight)
            assertSame(color, scheme.directlyDefinedColors[colorKey])
            assertSame(parent, scheme.parentScheme)
            assertSame(active, manager.globalScheme)
            assertEquals(
                mapOf<Any, Any>("dev.ayuislands.override.v1.probe" to "IA;unknown\nЖ"),
                restored.metaProperties.toMap(),
            )
            assertEquals(color.rgb, assertNotNull(restored.directlyDefinedColors[colorKey]).rgb)

            val reloadedAttributes = assertNotNull(restored.directlyDefinedAttributes[attributeKey.externalName])
            assertEquals(2, flyweight.allEffects.size)
            assertEquals(1, reloadedAttributes.flyweight.allEffects.size)
            assertEquals(0x44, assertNotNull(attributes.foregroundColor).alpha)
            assertEquals(255, assertNotNull(reloadedAttributes.foregroundColor).alpha)
            assertEquals(attributes.effectType, reloadedAttributes.effectType)
            assertEquals(attributes.fontType, reloadedAttributes.fontType)
        }
    }

    @Test
    fun `ordinary clone loses ownership metadata and live serialization clears dirty state`() {
        SwingUtilities.invokeAndWait {
            val scheme = newScheme()
            scheme.metaProperties["dev.ayuislands.override.v1.probe"] = "unparsed owner value"
            val cloned = assertIs<EditorColorsSchemeImpl>(scheme.clone())
            assertNotEquals(scheme.metaProperties.toMap(), cloned.metaProperties.toMap())

            scheme.setSaveNeeded(true)
            val dirty = scheme.schemeState
            scheme.writeExternal(Element("scheme"))

            assertNotEquals(dirty, scheme.schemeState)
            assertEquals("unparsed owner value", scheme.metaProperties["dev.ayuislands.override.v1.probe"])
        }
    }

    @Test
    fun `failed detached serialization leaves original state and native color untouched`() {
        SwingUtilities.invokeAndWait {
            val scheme = newScheme()
            val key = ColorKey.createColorKey("AYU_PERSISTENCE_FAILURE_COLOR")
            val failure = IllegalStateException("Native color cannot be serialized")
            var isArmed = false
            val color =
                object : Color(0x123456) {
                    override fun getRGB(): Int {
                        if (isArmed) throw failure
                        return super.getRGB()
                    }
                }
            scheme.setColor(key, color)
            scheme.metaProperties["foreign"] = "raw\nproperty"
            scheme.setSaveNeeded(true)
            val metadata = scheme.metaProperties.toMap()
            val state = scheme.schemeState
            val detached = detachedCopy(scheme)
            isArmed = true

            assertSame(failure, assertFailsWith<IllegalStateException> { detached.writeExternal(Element("scheme")) })

            assertEquals(metadata, scheme.metaProperties.toMap())
            assertEquals(state, scheme.schemeState)
            assertSame(color, scheme.directlyDefinedColors[key])
        }
    }

    private fun newScheme(): EditorColorsSchemeImpl =
        EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme).apply { name = "Ayu persistence probe" }

    private fun detachedCopy(source: EditorColorsSchemeImpl): EditorColorsSchemeImpl =
        assertIs<EditorColorsSchemeImpl>(source.clone()).apply {
            metaProperties.clear()
            metaProperties.putAll(source.metaProperties)
        }

    private fun layeredAttributes(): TextAttributes =
        assertNotNull(
            TextAttributes.merge(
                TextAttributes(Color(0x44223355, true), null, Color.RED, EffectType.BOXED, Font.BOLD),
                TextAttributes(null, null, Color.GREEN, EffectType.LINE_UNDERSCORE, Font.PLAIN),
            ),
        )
}
