package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.Scheme
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.CheckpointEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.SchemeCellFacts
import dev.ayuislands.theme.SchemeEntryRecord
import dev.ayuislands.theme.SchemePersistenceCapture
import dev.ayuislands.theme.SchemePersistenceResult
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeStoredCell
import dev.ayuislands.theme.SchemeTarget
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Font
import javax.swing.SwingUtilities
import javax.swing.plaf.ColorUIResource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemePersistenceCaptureTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `ARGB to null capture preserves the native record and all live state`() {
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_PERSISTENCE_ARGB")
            val scheme = nativeScheme()
            val original = Color(0x12345678, true)
            scheme.setColor(key, original)
            val target = target(scheme)
            val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(null))
            val metadata = scheme.metaProperties.toMap()
            val saveState = scheme.schemeState

            val capture = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))

            assertSame(record, capture.record)
            assertEquals(SchemeStoredCell.Color(original.rgb), capture.before)
            assertSame(SchemeStoredCell.NullColor, capture.plannedWithCapturedBase)
            assertTrue(SchemePersistenceCapture.isCurrent(capture))
            assertSame(original, scheme.directlyDefinedColors[key])
            assertEquals(metadata, scheme.metaProperties.toMap())
            assertEquals(saveState, scheme.schemeState)
        }
    }

    @Test
    fun `existing stored color key wins over registry replacement but absence uses the setter key`() {
        SwingUtilities.invokeAndWait {
            val stored = ColorKey.createColorKey("AYU_PERSISTENCE_STORED_KEY")
            val parent = parentWithRed(stored)
            val scheme = nativeScheme(parent)
            scheme.setColor(stored, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            val fallback = ColorKey.createColorKey("AYU_PERSISTENCE_FALLBACK")
            val replacement = ColorKey.createColorKeyWithFallback(stored.externalName, fallback)
            val entry = SchemeEntry.ColorEntry(replacement)
            val target = target(scheme)
            val record = record(target, entry, SchemeValue.InheritedColor)

            val existing = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))

            assertSame(SchemeStoredCell.Omitted, existing.before)
            assertSame(SchemeStoredCell.Omitted, existing.plannedWithCapturedBase)
            assertSame(stored, scheme.directlyDefinedColors.keys.single())
            assertTrue(SchemePersistenceCapture.isCurrent(existing))

            val absentScheme = nativeScheme(parent)
            val absentTarget = target(absentScheme)
            val absentRecord = record(absentTarget, entry, SchemeValue.InheritedColor)
            val absent =
                assertIs<SchemePersistenceResult.Ready>(
                    SchemePersistenceCapture.capture(absentTarget, absentRecord),
                )

            assertSame(SchemeStoredCell.Omitted, absent.before)
            assertEquals(SchemeStoredCell.InheritedColor(fallback.externalName), absent.plannedWithCapturedBase)
            assertTrue(absentScheme.directlyDefinedColors.isEmpty())
        }
    }

    @Test
    fun `base cell replacement invalidates capture without rewriting original projections`() {
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_PERSISTENCE_BASE_CHANGED")
            val parent = nativeScheme().apply { setColor(key, Color.RED) }
            val scheme = nativeScheme(parent).apply { setColor(key, Color.BLUE) }
            val target = target(scheme)
            val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(null))
            val capture = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))
            assertTrue(SchemePersistenceCapture.isCurrent(capture))

            parent.setColor(key, Color.GREEN)

            assertFalse(SchemePersistenceCapture.isCurrent(capture))
            assertEquals(SchemeStoredCell.Color(Color.BLUE.rgb), capture.before)
            assertSame(SchemeStoredCell.NullColor, capture.plannedWithCapturedBase)
            assertSame(record, capture.record)
            assertSame(Color.BLUE, scheme.directlyDefinedColors[key])
        }
    }

    @Test
    fun `unqualified native subclass is unavailable without changing its field`() {
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_PERSISTENCE_SUBCLASS")
            val scheme = object : EditorColorsSchemeImpl(DefaultColorsScheme()) {}
            scheme.name = "Unqualified persistence fixture"
            scheme.setColor(key, Color.BLUE)
            val target = target(scheme)
            val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(null))

            assertIs<SchemePersistenceResult.Unavailable>(SchemePersistenceCapture.capture(target, record))

            assertSame(Color.BLUE, scheme.directlyDefinedColors[key])
        }
    }

    @Test
    fun `native attribute projections preserve live effects and reject a changed planned flyweight`() {
        SwingUtilities.invokeAndWait {
            val key = TextAttributesKey.createTextAttributesKey("AYU_CAPTURE_ATTRIBUTE_FIELDS")
            val original = layeredAttributes()
            val flyweight = original.flyweight
            val scheme = nativeScheme().apply { setAttributes(key, original) }
            val target = target(scheme)
            val record =
                record(
                    target,
                    SchemeEntry.AttributesEntry(key),
                    SchemeValue.AttributesValue(TextAttributes().apply { foregroundColor = Color.BLUE }),
                )
            val capture = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))
            val before = assertIs<SchemeStoredCell.Attributes>(capture.before)
            val planned = assertIs<SchemeStoredCell.Attributes>(capture.plannedWithCapturedBase)

            assertEquals(0x223355, before.foregroundRgb)
            assertEquals(assertNotNull(original.effectColor).rgb and 0xFFFFFF, before.effectRgb)
            assertEquals(Font.BOLD, before.fontType)
            assertEquals(Color.BLUE.rgb and 0xFFFFFF, planned.foregroundRgb)
            assertSame(original, scheme.directlyDefinedAttributes[key.externalName])
            assertSame(flyweight, original.flyweight)
            assertEquals(2, flyweight.allEffects.size)
            assertTrue(SchemePersistenceCapture.isCurrent(capture))

            val plannedCell = assertIs<SchemeCellFacts.Attributes>(record.planned.cell)
            val plannedAttributes = assertIs<TextAttributes>(assertNotNull(plannedCell.value).identity)
            plannedAttributes.foregroundColor = Color.GREEN

            assertFalse(SchemePersistenceCapture.isCurrent(capture))
            assertSame(planned, capture.plannedWithCapturedBase)
            assertSame(original, scheme.directlyDefinedAttributes[key.externalName])
            assertSame(flyweight, original.flyweight)
        }
    }

    @Test
    fun `hidden native effects make scalar-equal base comparison unavailable`() {
        SwingUtilities.invokeAndWait {
            val key = TextAttributesKey.createTextAttributesKey("AYU_CAPTURE_HIDDEN_EFFECTS")
            val original = layeredAttributes()
            val scalarCopy =
                TextAttributes(
                    original.foregroundColor,
                    original.backgroundColor,
                    original.effectColor,
                    original.effectType,
                    original.fontType,
                )
            assertNotSame(original.flyweight, scalarCopy.flyweight)
            val parent = nativeScheme().apply { setAttributes(key, scalarCopy) }
            val scheme = nativeScheme(parent).apply { setAttributes(key, original) }
            val target = target(scheme)
            val record = record(target, SchemeEntry.AttributesEntry(key), SchemeValue.InheritedAttributes)

            val result = assertIs<SchemePersistenceResult.Unavailable>(SchemePersistenceCapture.capture(target, record))

            assertEquals("The original projection is unknown", result.reason)
            assertSame(original, scheme.directlyDefinedAttributes[key.externalName])
            assertEquals(2, original.flyweight.allEffects.size)
        }
    }

    @Test
    fun `inherited attributes use the current registry key and traverse direct parent cells`() {
        SwingUtilities.invokeAndWait {
            val oldKey = TextAttributesKey.createTextAttributesKey("AYU_CAPTURE_ATTRIBUTE_KEY")
            val grandparent =
                nativeScheme().apply { setAttributes(oldKey, TextAttributes().apply { foregroundColor = Color.RED }) }
            val parent = nativeScheme(grandparent)
            val scheme =
                nativeScheme(
                    parent,
                ).apply { setAttributes(oldKey, AbstractColorsScheme.INHERITED_ATTRS_MARKER) }
            val target = target(scheme)
            val record = record(target, SchemeEntry.AttributesEntry(oldKey), SchemeValue.InheritedAttributes)
            val capture = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))

            assertEquals(SchemeStoredCell.InheritedAttributes(""), capture.before)
            assertEquals(capture.before, capture.plannedWithCapturedBase)
            assertTrue(parent.directlyDefinedAttributes.isEmpty())
            assertTrue(SchemePersistenceCapture.isCurrent(capture))

            val fallback = TextAttributesKey.createTextAttributesKey("AYU_CAPTURE_ATTRIBUTE_FALLBACK")
            val replacement = TextAttributesKey.createTextAttributesKey(oldKey.externalName, fallback)

            assertNotSame(oldKey, replacement)
            assertFalse(SchemePersistenceCapture.isCurrent(capture))
            assertEquals(SchemeStoredCell.InheritedAttributes(""), capture.before)

            val registered = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))
            assertSame(record, registered.record)
            assertEquals(SchemeStoredCell.InheritedAttributes(fallback.externalName), registered.before)
            assertEquals(registered.before, registered.plannedWithCapturedBase)
            assertTrue(SchemePersistenceCapture.isCurrent(registered))
            assertSame(
                AbstractColorsScheme.INHERITED_ATTRS_MARKER,
                scheme.directlyDefinedAttributes[oldKey.externalName],
            )
        }
    }

    @Test
    fun `effective fallback colors do not turn absent direct base cells into persisted inheritance`() {
        SwingUtilities.invokeAndWait {
            val fallback = ColorKey.createColorKey("AYU_CAPTURE_EFFECTIVE_FALLBACK")
            val key = ColorKey.createColorKeyWithFallback("AYU_CAPTURE_DIRECT_ABSENCE", fallback)
            val parent = parentWithRed(fallback)
            val scheme = nativeScheme(parent)
            val target = target(scheme)
            val entry = SchemeEntry.ColorEntry(key)
            val record = record(target, entry, SchemeValue.InheritedColor)
            val absent = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))

            assertEquals(Color.RED.rgb, assertNotNull(scheme.getColor(key)).rgb)
            assertSame(SchemeStoredCell.Omitted, absent.before)
            assertSame(SchemeStoredCell.Omitted, absent.plannedWithCapturedBase)
            assertTrue(scheme.directlyDefinedColors.isEmpty())

            scheme.setColor(key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            val inheritedRecord = record(target, entry, SchemeValue.InheritedColor)
            val inherited =
                assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, inheritedRecord))

            assertSame(SchemeStoredCell.Omitted, inherited.before)
            assertSame(SchemeStoredCell.Omitted, inherited.plannedWithCapturedBase)
            assertFalse(SchemePersistenceCapture.isCurrent(absent))
            assertSame(SchemeStoredCell.Omitted, absent.before)
        }
    }

    @Test
    fun `only a prefixed editable name with a resolved original changes the persistence base`() {
        SwingUtilities.invokeAndWait {
            val original = assertIs<DefaultColorsScheme>(EditorColorsManager.getInstance().getScheme("Default"))
            val key = ColorKey.createColorKey("AYU_CAPTURE_ORIGINAL_BASE")
            val parent = parentWithRed(key)
            val cases =
                listOf(
                    Triple("Ordinary original metadata", "Default", SchemeStoredCell.Omitted),
                    Triple(
                        Scheme.EDITABLE_COPY_PREFIX + "Resolved original",
                        "Default",
                        SchemeStoredCell.Color(Color.RED.rgb),
                    ),
                    Triple(
                        Scheme.EDITABLE_COPY_PREFIX + "Missing original",
                        "Missing capture original",
                        SchemeStoredCell.Omitted,
                    ),
                )
            for ((name, originalName, expected) in cases) {
                val scheme = nativeScheme(parent)
                scheme.name = name
                scheme.metaProperties[AbstractColorsScheme.META_INFO_ORIGINAL] = originalName
                scheme.setColor(key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
                scheme.setColor(key, Color.RED)
                val target = target(scheme)
                val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(null))
                val capture = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))

                assertEquals(expected, capture.before, name)
                assertSame(if (originalName == "Default") original else null, capture.dependencies.original)
                assertTrue(SchemePersistenceCapture.isCurrent(capture), name)

                scheme.metaProperties[AbstractColorsScheme.META_INFO_ORIGINAL] = "Changed capture original"

                assertFalse(SchemePersistenceCapture.isCurrent(capture), name)
                assertEquals(expected, capture.before, name)
                assertSame(record, capture.record)
                assertSame(Color.RED, scheme.directlyDefinedColors[key])
            }
        }
    }

    @Test
    fun `selection name and parent changes invalidate the retained native target`() {
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_CAPTURE_TARGET_CHANGED")
            val scheme = nativeScheme().apply { setColor(key, Color.BLUE) }
            var selected = scheme
            val target = assertNotNull(SchemeTarget.capture(SchemeSource.Active) { selected })
            val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(null))
            val capture = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))

            selected = nativeScheme()
            assertFalse(SchemePersistenceCapture.isCurrent(capture))
            assertIs<SchemePersistenceResult.Unavailable>(SchemePersistenceCapture.capture(target, record))
            selected = scheme
            assertTrue(SchemePersistenceCapture.isCurrent(capture))

            scheme.name = "Renamed capture target"
            assertFalse(SchemePersistenceCapture.isCurrent(capture))
            scheme.name = target.name
            assertTrue(SchemePersistenceCapture.isCurrent(capture))

            scheme.setParent(DefaultColorsScheme())
            assertFalse(SchemePersistenceCapture.isCurrent(capture))
            assertIs<SchemePersistenceResult.Unavailable>(SchemePersistenceCapture.capture(target, record))
            assertEquals(SchemeStoredCell.Color(Color.BLUE.rgb), capture.before)
        }
    }

    @Test
    fun `stored color key replacement invalidates even when the original color identity returns`() {
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_CAPTURE_KEY_CHANGED")
            val scheme = nativeScheme(parentWithRed(key)).apply { setColor(key, Color.BLUE) }
            val target = target(scheme)
            val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.InheritedColor)
            val capture = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))
            val replacement =
                ColorKey.createColorKeyWithFallback(
                    key.externalName,
                    ColorKey.createColorKey("AYU_CAPTURE_NEW_FALLBACK"),
                )

            assertTrue(SchemePersistenceCapture.isCurrent(capture))
            scheme.setColor(key, Color.RED)
            scheme.optimizeAttributeMap()
            assertTrue(scheme.directlyDefinedColors.isEmpty())
            scheme.setColor(replacement, Color.BLUE)

            assertSame(Color.BLUE, scheme.directlyDefinedColors[key])
            assertSame(replacement, scheme.directlyDefinedColors.keys.single())
            assertFalse(SchemePersistenceCapture.isCurrent(capture))
            assertSame(SchemeStoredCell.Omitted, capture.plannedWithCapturedBase)
            assertEquals(SchemeStoredCell.Color(Color.BLUE.rgb), capture.before)
        }
    }

    @Test
    fun `changed original local cell is refused instead of becoming a new before`() {
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_CAPTURE_RECORD_CHANGED")
            val scheme = nativeScheme().apply { setColor(key, Color.BLUE) }
            val target = target(scheme)
            val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(null))
            scheme.setColor(key, Color.GREEN)

            val result = assertIs<SchemePersistenceResult.Unavailable>(SchemePersistenceCapture.capture(target, record))

            assertEquals("The original native record changed", result.reason)
            assertSame(Color.BLUE, assertIs<SchemeCellFacts.Color>(record.before.cell).value?.identity)
            assertSame(Color.GREEN, scheme.directlyDefinedColors[key])
        }
    }

    @Test
    fun `shared parent and color identity cannot bind a different native scheme record`() {
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_CAPTURE_OTHER_SCHEME_RECORD")
            val parent = DefaultColorsScheme()
            val original = nativeScheme(parent).apply { setColor(key, Color.BLUE) }
            val other = nativeScheme(parent).apply { setColor(key, Color.BLUE) }
            val capturedTarget = target(original)
            val record = record(capturedTarget, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(Color.RED))
            val originalMetadata = original.metaProperties.toMap()
            val otherMetadata = other.metaProperties.toMap()
            val originalSaveState = original.schemeState
            val otherSaveState = other.schemeState

            assertNotSame(original.metaProperties, other.metaProperties)
            assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(capturedTarget, record))
            assertIs<SchemePersistenceResult.Unavailable>(SchemePersistenceCapture.capture(target(other), record))

            assertSame(Color.BLUE, original.directlyDefinedColors[key])
            assertSame(Color.BLUE, other.directlyDefinedColors[key])
            assertEquals(originalMetadata, original.metaProperties.toMap())
            assertEquals(otherMetadata, other.metaProperties.toMap())
            assertEquals(originalSaveState, original.schemeState)
            assertEquals(otherSaveState, other.schemeState)
        }
    }

    @Test
    fun `opaque colors and legacy background loader entries are unavailable`() {
        SwingUtilities.invokeAndWait {
            for ((name, value) in listOf(
                "AYU_CAPTURE_OPAQUE" to ColorUIResource(Color.RED),
                "BACKGROUND" to Color.BLUE,
            )) {
                val key = ColorKey.createColorKey(name)
                val scheme = nativeScheme().apply { setColor(key, value) }
                val target = target(scheme)
                val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(null))

                assertIs<SchemePersistenceResult.Unavailable>(SchemePersistenceCapture.capture(target, record))

                assertSame(value, scheme.directlyDefinedColors[key])
            }
        }
    }

    @Test
    fun `temporary attributes cannot reuse an ordinary native persistence record`() {
        SwingUtilities.invokeAndWait {
            val key = TextAttributesKey.createTextAttributesKey("AYU_CAPTURE_ORDINARY_ATTRIBUTES")
            val scheme = nativeScheme().apply { setAttributes(key, TextAttributes()) }
            val target = target(scheme)
            val ordinary = record(target, SchemeEntry.AttributesEntry(key), SchemeValue.InheritedAttributes)
            val tempKey = TextAttributesKey.createTempTextAttributesKey("AYU_CAPTURE_TEMP_ATTRIBUTES", TextAttributes())
            val temporary =
                SchemeEntryRecord(
                    SchemeEntry.AttributesEntry(tempKey),
                    ordinary.before,
                    ordinary.planned,
                    ordinary.restored,
                    ordinary.released,
                )

            val result =
                assertIs<SchemePersistenceResult.Unavailable>(SchemePersistenceCapture.capture(target, temporary))

            assertEquals("The entry has unsupported native loading semantics", result.reason)
            assertFalse(scheme.directlyDefinedAttributes.containsKey(tempKey.externalName))
        }
    }

    @Test
    fun `cyclic and unqualified base links are refused before recursive native lookup`() {
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_CAPTURE_BASE_LINK")
            val cyclic = DefaultColorsScheme().apply { setParent(this) }
            val unqualified = object : DefaultColorsScheme() {}
            for (parent in listOf(cyclic, unqualified)) {
                val scheme = nativeScheme(parent).apply { setColor(key, AbstractColorsScheme.NULL_COLOR_MARKER) }
                val target = target(scheme)
                val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.InheritedColor)

                val result =
                    assertIs<SchemePersistenceResult.Unavailable>(SchemePersistenceCapture.capture(target, record))

                assertEquals("The base chain is unqualified or cyclic", result.reason)
                assertSame(AbstractColorsScheme.NULL_COLOR_MARKER, scheme.directlyDefinedColors[key])
            }
        }
    }

    @Test
    fun `native observation exceptions propagate without becoming capability refusals`() {
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_CAPTURE_OBSERVATION_FAILURE")
            val scheme = nativeScheme().apply { setColor(key, Color.BLUE) }
            val failure = IllegalStateException("Native selection observation failed")
            var failObservation = false
            val target =
                assertNotNull(
                    SchemeTarget.capture(SchemeSource.Active) {
                        if (failObservation) throw failure
                        scheme
                    },
                )
            val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(null))
            val capture = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))
            failObservation = true

            assertSame(
                failure,
                assertFailsWith<IllegalStateException> { SchemePersistenceCapture.capture(target, record) },
            )
            assertSame(failure, assertFailsWith<IllegalStateException> { SchemePersistenceCapture.isCurrent(capture) })
            assertSame(Color.BLUE, scheme.directlyDefinedColors[key])
        }
    }

    @Test
    fun `capture and dependency checks require EDT`() {
        lateinit var captured: SchemePersistenceResult.Ready
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_CAPTURE_EDT")
            val scheme = nativeScheme().apply { setColor(key, Color.BLUE) }
            val target = target(scheme)
            val record = record(target, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(null))
            captured = assertIs<SchemePersistenceResult.Ready>(SchemePersistenceCapture.capture(target, record))
        }

        assertFalse(SwingUtilities.isEventDispatchThread())
        assertFailsWith<IllegalStateException> { SchemePersistenceCapture.isCurrent(captured) }
        assertFailsWith<IllegalStateException> {
            SchemePersistenceCapture.capture(captured.dependencies.target, captured.record)
        }
    }

    private fun nativeScheme(parent: AbstractColorsScheme = DefaultColorsScheme()): EditorColorsSchemeImpl =
        EditorColorsSchemeImpl(parent).apply { name = "Persistence fixture" }

    private fun target(scheme: AbstractColorsScheme): SchemeTarget =
        assertNotNull(SchemeTarget.capture(SchemeSource.Named("fixture")) { scheme })

    private fun record(
        target: SchemeTarget,
        entry: SchemeEntry,
        value: SchemeValue,
    ): SchemeEntryRecord =
        EditorSchemeOverrides.checkpoints
            .prepareBatch(target, mapOf(entry to CheckpointEntry(value, null, null)))
            .records
            .single()

    private fun parentWithRed(key: ColorKey): DefaultColorsScheme =
        DefaultColorsScheme().apply {
            readExternal(
                JDOMUtil.load(
                    """
                    <scheme name="Persistence parent" version="142" default_scheme="true">
                      <colors><option name="${key.externalName}" value="ff0000" /></colors>
                    </scheme>
                    """.trimIndent(),
                ),
            )
        }

    private fun layeredAttributes(): TextAttributes =
        assertNotNull(
            TextAttributes.merge(
                TextAttributes(Color(0x44223355, true), null, Color.RED, EffectType.BOXED, Font.BOLD),
                TextAttributes(null, null, Color.GREEN, EffectType.LINE_UNDERSCORE, Font.PLAIN),
            ),
        )
}
