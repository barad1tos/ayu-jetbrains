package dev.ayuislands.integration

import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.JBColor
import dev.ayuislands.font.FontSurface
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.CheckpointEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.SchemeComparison
import dev.ayuislands.theme.SchemeEquality
import dev.ayuislands.theme.capturePruningFacts
import org.jdom.Element
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Font
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Isolated feasibility probe; no production restoration adapter is wired to this operation. */
class EditorSchemeOptimizationTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `native equal setters preserve local absence throughout detached apply and rollback`() {
        SwingUtilities.invokeAndWait {
            val parent = DefaultColorsScheme()
            parent.readExternal(
                JDOMUtil.load(
                    """
                    <scheme name="Detached parent" version="142" default_scheme="true">
                      <colors><option name="AYU_DETACHED_EQUAL_COLOR" value="ff0000" /></colors>
                      <attributes><option name="AYU_DETACHED_EQUAL_ATTRIBUTES">
                        <value><option name="FOREGROUND" value="00ff00" /></value>
                      </option></attributes>
                    </scheme>
                    """.trimIndent(),
                ),
            )
            val scheme = EditorColorsSchemeImpl(parent)
            val color = ColorKey.createColorKey("AYU_DETACHED_EQUAL_COLOR")
            val attributes = TextAttributesKey.createTextAttributesKey("AYU_DETACHED_EQUAL_ATTRIBUTES")
            val inherited = assertNotNull(parent.getAttributes(attributes)).clone()
            val active = AyuEditorSchemeScope.activeScheme()
            val colorWrite =
                EditorSchemeOverrides.checkpoints.prepareWrite(
                    scheme,
                    SchemeEntry.ColorEntry(color),
                    CheckpointEntry(SchemeValue.ColorValue(Color.RED), null, null),
                    active,
                )
            val attributeWrite =
                EditorSchemeOverrides.checkpoints.prepareWrite(
                    scheme,
                    SchemeEntry.AttributesEntry(attributes),
                    CheckpointEntry(SchemeValue.AttributesValue(inherited), null, null),
                    active,
                )
            assertTrue(colorWrite.apply())
            assertTrue(attributeWrite.apply())
            assertFalse(scheme.directlyDefinedColors.containsKey(color))
            assertFalse(scheme.directlyDefinedAttributes.containsKey(attributes.externalName))
            attributeWrite.restore()
            colorWrite.restore()
            assertFalse(scheme.directlyDefinedColors.containsKey(color))
            assertFalse(scheme.directlyDefinedAttributes.containsKey(attributes.externalName))
        }
    }

    @Test
    fun `native detached attribute rollback recognizes the retained setter payload`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(DefaultColorsScheme())
            val key = TextAttributesKey.createTextAttributesKey("AYU_DETACHED_PAYLOAD")
            val original = TextAttributes().apply { foregroundColor = Color.RED }
            val desired = TextAttributes().apply { foregroundColor = Color.ORANGE }
            scheme.setAttributes(key, original)
            val prepared =
                EditorSchemeOverrides.checkpoints.prepareWrite(
                    scheme,
                    SchemeEntry.AttributesEntry(key),
                    CheckpointEntry(SchemeValue.AttributesValue(desired), null, null),
                    AyuEditorSchemeScope.activeScheme(),
                )
            assertTrue(prepared.apply())
            assertEquals(desired, scheme.directlyDefinedAttributes[key.externalName])
            prepared.restore()
            assertEquals(original, scheme.directlyDefinedAttributes[key.externalName])
            assertFalse(prepared.hasPending)
        }
    }

    @Test
    fun `detached admission preserves exact native cells replaced before apply`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(DefaultColorsScheme())
            val color = ColorKey.createColorKey("AYU_ADMISSION_COLOR")
            val absent = ColorKey.createColorKey("AYU_ADMISSION_ABSENCE")
            val attribute = TextAttributesKey.createTextAttributesKey("AYU_ADMISSION_ATTRIBUTES")
            val original = TextAttributes().apply { foregroundColor = Color.RED }
            scheme.setColor(color, Color.RED)
            scheme.setAttributes(attribute, original)
            val metadataBefore = scheme.metaProperties.toMap()
            val active = AyuEditorSchemeScope.activeScheme()
            val owner = EditorSchemeOverrides.checkpoints
            val colorWrite =
                owner.prepareWrite(
                    scheme,
                    SchemeEntry.ColorEntry(color),
                    CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, null),
                    active,
                )
            val absentWrite =
                owner.prepareWrite(
                    scheme,
                    SchemeEntry.ColorEntry(absent),
                    CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, null),
                    active,
                )
            val attributeWrite =
                owner.prepareWrite(
                    scheme,
                    SchemeEntry.AttributesEntry(attribute),
                    CheckpointEntry(SchemeValue.AttributesValue(TextAttributes()), null, null),
                    active,
                )
            val foreignColor = Color(Color.RED.rgb)
            val foreignAttributes = original.clone()
            // Native setters skip equal values; a real intervening edit makes these new local instances.
            scheme.setColor(color, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            scheme.setColor(color, foreignColor)
            scheme.setColor(absent, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            scheme.setAttributes(attribute, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            scheme.setAttributes(attribute, foreignAttributes)
            assertSame(foreignColor, scheme.directlyDefinedColors[color])
            assertSame(foreignAttributes, scheme.directlyDefinedAttributes[attribute.externalName])
            assertSame(original.flyweight, foreignAttributes.flyweight)
            assertFalse(colorWrite.apply())
            assertFalse(absentWrite.apply())
            assertFalse(attributeWrite.apply())
            assertSame(foreignColor, scheme.directlyDefinedColors[color])
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, scheme.directlyDefinedColors[absent])
            assertSame(foreignAttributes, scheme.directlyDefinedAttributes[attribute.externalName])
            assertEquals(metadataBefore, scheme.metaProperties.toMap())
        }
    }

    @Test
    fun `detached direct cell capture rejects temporary attributes without changing the temp value`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(DefaultColorsScheme())
            val key = TextAttributesKey.createTempTextAttributesKey("AYU_ADMISSION_TEMP", null)
            val original = TextAttributes().apply { foregroundColor = Color.RED }
            scheme.setAttributes(key, original)
            assertFalse(scheme.directlyDefinedAttributes.containsKey(key.externalName))
            assertFailsWith<IllegalStateException> {
                EditorSchemeOverrides.checkpoints.prepareWrite(
                    scheme,
                    SchemeEntry.AttributesEntry(key),
                    CheckpointEntry(SchemeValue.AttributesValue(TextAttributes()), null, null),
                    AyuEditorSchemeScope.activeScheme(),
                )
            }
            assertSame(original, scheme.getAttributes(key))
        }
    }

    @Test
    fun `pruning census requires every deletion in both maps to be an explicit absence target`() {
        SwingUtilities.invokeAndWait {
            val parent = DefaultColorsScheme()
            val key = ColorKey.createColorKey("AYU_CENSUS_COLOR")
            val attributes = mapOf("AYU_CENSUS_ATTRIBUTES" to AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            val colors = mapOf(key to AbstractColorsScheme.INHERITED_COLOR_MARKER)
            val facts = assertNotNull(capturePruningFacts(parent, attributes, colors))
            assertTrue(SchemeComparison.permitsPruning(facts, attributes.keys, setOf(key.externalName)))
            assertFalse(SchemeComparison.permitsPruning(facts, attributes.keys, emptySet()))
            assertFalse(SchemeComparison.permitsPruning(facts, emptySet(), setOf(key.externalName)))
            assertFalse(SchemeComparison.permitsPruning(facts, attributes.keys + "MISSING", setOf(key.externalName)))
            assertSame(AbstractColorsScheme.INHERITED_ATTRS_MARKER, attributes.values.single())
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, colors.values.single())
        }
    }

    @Test
    fun `pruning census uses default parent fallbacks without key defaults`() {
        SwingUtilities.invokeAndWait {
            val parent =
                DefaultColorsScheme().apply {
                    readExternal(
                        JDOMUtil.load(
                            """
                            <scheme name="Census" version="142" default_scheme="true">
                              <colors><option name="AYU_CENSUS_PARENT_COLOR" value="ff0000" /></colors>
                              <attributes><option name="AYU_CENSUS_PARENT_ATTRIBUTE">
                                <value><option name="FOREGROUND" value="00ff00" /></value>
                              </option></attributes>
                            </scheme>
                            """.trimIndent(),
                        ),
                    )
                }
            val parentColor = ColorKey.find("AYU_CENSUS_PARENT_COLOR")
            val fallbackColor = ColorKey.createColorKeyWithFallback("AYU_CENSUS_FALLBACK_COLOR", parentColor)
            val parentAttribute = TextAttributesKey.find("AYU_CENSUS_PARENT_ATTRIBUTE")
            val fallbackAttribute =
                TextAttributesKey.createTextAttributesKey("AYU_CENSUS_FALLBACK_ATTR", parentAttribute)
            val defaultColor = ColorKey.createColorKey("AYU_CENSUS_DEFAULT_COLOR", Color.BLUE)
            val defaultAttribute = HighlighterColors.TEXT
            val values =
                mapOf(
                    fallbackAttribute.externalName to assertNotNull(parent.getAttributes(parentAttribute)),
                    defaultAttribute.externalName to assertNotNull(defaultAttribute.defaultAttributes),
                )
            val colors = mapOf(fallbackColor to Color.RED, defaultColor to Color.BLUE)
            val facts = assertNotNull(capturePruningFacts(parent, values, colors))
            assertTrue(
                SchemeComparison.permitsPruning(
                    facts,
                    setOf(fallbackAttribute.externalName),
                    setOf(fallbackColor.externalName),
                ),
            )
            assertEquals(SchemeEquality.DIFFERENT, facts.attributes[defaultAttribute.externalName])
            assertEquals(SchemeEquality.DIFFERENT, facts.colors[defaultColor.externalName])
        }
    }

    @Test
    fun `pruning census rejects opaque comparisons and custom parent lookups without calling them`() {
        SwingUtilities.invokeAndWait {
            val custom =
                object : DefaultColorsScheme() {
                    override fun getAttributes(
                        key: TextAttributesKey?,
                        useDefaults: Boolean,
                    ): TextAttributes = error("Custom parent lookup must not run")
                }
            assertNull(capturePruningFacts(custom, mapOf("CUSTOM" to TextAttributes()), emptyMap()))
            val parent = DefaultColorsScheme()
            val opaque =
                object : TextAttributes() {
                    override fun equals(other: Any?): Boolean = error("Opaque equality must not run")

                    override fun hashCode(): Int = error("Opaque hash must not run")
                }
            // Both native values can be distinguished from a null parent without evaluating the foreign value.
            val facts = assertNotNull(capturePruningFacts(parent, mapOf("OPAQUE" to opaque), emptyMap()))
            assertEquals(SchemeEquality.DIFFERENT, facts.attributes["OPAQUE"])
            assertTrue(SchemeComparison.permitsPruning(facts, emptySet(), emptySet()))
            parent.readExternal(
                JDOMUtil.load(
                    """
                    <scheme name="Opaque parent" version="142" default_scheme="true">
                      <colors><option name="OPAQUE_COLOR" value="ff0000" /></colors>
                      <attributes><option name="OPAQUE">
                        <value><option name="FOREGROUND" value="00ff00" /></value>
                      </option></attributes>
                    </scheme>
                    """.trimIndent(),
                ),
            )
            val opaqueColor =
                object : Color(0xFF0000) {
                    override fun getRGB(): Int = error("Opaque RGB must not run")
                }
            val unknown =
                assertNotNull(
                    capturePruningFacts(
                        parent,
                        mapOf("OPAQUE" to opaque),
                        mapOf(ColorKey.find("OPAQUE_COLOR") to opaqueColor),
                    ),
                )
            assertEquals(SchemeEquality.UNKNOWN, unknown.attributes["OPAQUE"])
            assertEquals(SchemeEquality.UNKNOWN, unknown.colors["OPAQUE_COLOR"])
            assertFalse(SchemeComparison.permitsPruning(unknown, emptySet(), emptySet()))
        }
    }

    @Test
    fun `public optimization can restore absence while retaining unrelated native values`() {
        SwingUtilities.invokeAndWait {
            val active = EditorColorsManager.getInstance().globalScheme
            val parent = EditorColorsSchemeImpl(active)
            val scheme = EditorColorsSchemeImpl(parent)
            val fallback = TextAttributesKey.createTextAttributesKey("AYU_OPT_FALLBACK")
            val target = TextAttributesKey.createTextAttributesKey("AYU_OPT_TARGET", fallback)
            val redundant = TextAttributesKey.createTextAttributesKey("AYU_OPT_REDUNDANT")
            val empty = TextAttributesKey.createTextAttributesKey("AYU_OPT_EMPTY")
            val inherited = TextAttributesKey.createTextAttributesKey("AYU_OPT_INHERITED")
            val effects = TextAttributesKey.createTextAttributesKey("AYU_OPT_EFFECTS")
            val color = ColorKey.createColorKey("AYU_OPT_COLOR")
            val nullColor = ColorKey.createColorKey("AYU_OPT_NULL_COLOR")
            val inheritedColor = ColorKey.createColorKey("AYU_OPT_INHERITED_COLOR")
            parent.setAttributes(target, attributes(Color.RED))
            parent.setAttributes(redundant, attributes(Color.ORANGE))
            parent.setAttributes(empty, TextAttributes())
            parent.setColor(color, Color(0x327FA12B, true))
            scheme.setAttributes(fallback, attributes(Color.GREEN))
            insertAttributes(scheme, redundant, attributes(Color.ORANGE))
            insertAttributes(scheme, empty, TextAttributes())
            scheme.setAttributes(inherited, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            val extraEffects =
                assertNotNull(
                    TextAttributes.merge(
                        TextAttributes(null, null, Color.CYAN, EffectType.BOXED, Font.PLAIN),
                        TextAttributes(null, null, Color.MAGENTA, EffectType.WAVE_UNDERSCORE, Font.BOLD),
                    ),
                )
            scheme.setAttributes(effects, extraEffects)
            scheme.setColor(color, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            scheme.setColor(color, parent.getColor(color))
            scheme.setColor(nullColor, AbstractColorsScheme.NULL_COLOR_MARKER)
            scheme.setColor(inheritedColor, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            scheme.metaProperties["foreign"] = "  unknown raw\nЖ\t"
            val editor = FontSurface.EDITOR.captureExact(scheme)
            val console = FontSurface.CONSOLE.captureExact(scheme)
            val metadata = scheme.metaProperties.toMap()

            assertFalse(scheme.directlyDefinedAttributes.containsKey(target.externalName))
            assertEquals(Color.RED, scheme.getAttributes(target).foregroundColor)
            scheme.setAttributes(target, attributes(Color.BLUE))
            // A foreign change after the attempted write must survive restoration too.
            scheme.setAttributes(fallback, attributes(Color.YELLOW))
            val retainedAttributes = scheme.directlyDefinedAttributes - target.externalName
            val retainedColors = scheme.directlyDefinedColors

            scheme.setAttributes(target, parent.getAttributes(target))
            scheme.optimizeAttributeMap()
            for ((name, saved) in retainedAttributes) {
                if (!scheme.directlyDefinedAttributes.containsKey(name)) {
                    insertAttributes(scheme, TextAttributesKey.find(name), saved)
                }
            }
            for ((key, saved) in retainedColors) {
                if (!scheme.directlyDefinedColors.containsKey(key)) {
                    scheme.setColor(key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
                    scheme.setColor(key, saved)
                }
            }

            assertFalse(scheme.directlyDefinedAttributes.containsKey(target.externalName))
            assertEquals(Color.RED, scheme.getAttributes(target).foregroundColor)
            assertEquals(retainedAttributes, scheme.directlyDefinedAttributes)
            assertEquals(retainedColors, scheme.directlyDefinedColors)
            for ((name, saved) in retainedAttributes) {
                assertSame(saved, scheme.directlyDefinedAttributes.getValue(name))
            }
            assertSame(AbstractColorsScheme.NULL_COLOR_MARKER, scheme.directlyDefinedColors.getValue(nullColor))
            assertEquals(editor, FontSurface.EDITOR.captureExact(scheme))
            assertEquals(console, FontSurface.CONSOLE.captureExact(scheme))
            assertEquals(metadata, scheme.metaProperties.toMap())
            assertSame(active, EditorColorsManager.getInstance().globalScheme)
            parent.setAttributes(target, attributes(Color.PINK))
            assertEquals(Color.PINK, scheme.getAttributes(target).foregroundColor)
        }
    }

    @Test
    fun `lazy parent color can fail after attribute removal without losing the foreign callback edit`() {
        SwingUtilities.invokeAndWait {
            val parent = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            val scheme = EditorColorsSchemeImpl(parent)
            val target = TextAttributesKey.createTextAttributesKey("AYU_PRUNE_FAULT_TARGET")
            val collateral = TextAttributesKey.createTextAttributesKey("AYU_PRUNE_FAULT_COLLATERAL")
            val trigger = ColorKey.createColorKey("AYU_PRUNE_FAULT_TRIGGER")
            val foreign = ColorKey.createColorKey("AYU_PRUNE_FAULT_FOREIGN")
            val failure = IllegalStateException("Parent color failed after a foreign write")
            var isArmed = false
            parent.setAttributes(target, attributes(Color.RED))
            parent.setAttributes(collateral, attributes(Color.ORANGE))
            parent.setColor(
                trigger,
                JBColor.lazy {
                    if (isArmed) {
                        scheme.setColor(foreign, Color.MAGENTA)
                        throw failure
                    }
                    Color.ORANGE
                },
            )
            scheme.setAttributes(target, attributes(Color.BLUE))
            insertAttributes(scheme, collateral, attributes(Color.ORANGE))
            scheme.setColor(trigger, Color.CYAN)
            scheme.setColor(foreign, Color.GREEN)
            val retained = scheme.directlyDefinedAttributes.getValue(collateral.externalName)
            scheme.setAttributes(target, parent.getAttributes(target))
            isArmed = true

            assertSame(failure, assertFailsWith<IllegalStateException> { scheme.optimizeAttributeMap() })
            assertFalse(scheme.directlyDefinedAttributes.containsKey(target.externalName))
            assertFalse(scheme.directlyDefinedAttributes.containsKey(collateral.externalName))
            assertSame(Color.MAGENTA, scheme.directlyDefinedColors.getValue(foreign))

            insertAttributes(scheme, collateral, retained)
            assertSame(retained, scheme.directlyDefinedAttributes.getValue(collateral.externalName))
            assertSame(Color.MAGENTA, scheme.directlyDefinedColors.getValue(foreign))
            assertEquals(Color.RED, scheme.getAttributes(target).foregroundColor)
        }
    }

    @Test
    fun `marker protection avoids lazy parent callbacks while retaining exact foreign objects`() {
        SwingUtilities.invokeAndWait {
            val parent = GuardedParent(EditorColorsManager.getInstance().globalScheme)
            val scheme = EditorColorsSchemeImpl(parent)
            val target = TextAttributesKey.createTextAttributesKey("AYU_PRUNE_PROTECTED_TARGET")
            val collateral = TextAttributesKey.createTextAttributesKey("AYU_PRUNE_PROTECTED_COLLATERAL")
            val trigger = ColorKey.createColorKey("AYU_PRUNE_PROTECTED_TRIGGER")
            var isArmed = false
            var callbacks = 0
            val lazyColor =
                JBColor.lazy {
                    if (isArmed) {
                        callbacks++
                        error("Protected pruning must not evaluate this supplier")
                    }
                    Color.ORANGE
                }
            parent.setAttributes(target, attributes(Color.RED))
            parent.setColor(trigger, lazyColor)
            scheme.setAttributes(target, attributes(Color.BLUE))
            scheme.setAttributes(collateral, attributes(lazyColor))
            scheme.setColor(trigger, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            scheme.setColor(trigger, lazyColor)
            val retainedAttributes = scheme.directlyDefinedAttributes - target.externalName
            val retainedColors = scheme.directlyDefinedColors
            isArmed = true
            parent.isArmed = true

            for (name in scheme.directlyDefinedAttributes.keys) {
                scheme.setAttributes(TextAttributesKey.find(name), AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            }
            for (key in retainedColors.keys) {
                scheme.setColor(key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            }
            scheme.optimizeAttributeMap()
            for ((name, saved) in retainedAttributes) {
                insertAttributes(scheme, TextAttributesKey.find(name), saved)
            }
            for ((key, saved) in retainedColors) {
                scheme.setColor(key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
                scheme.setColor(key, saved)
            }

            assertEquals(0, callbacks)
            assertFalse(scheme.directlyDefinedAttributes.containsKey(target.externalName))
            for ((name, saved) in retainedAttributes) {
                assertSame(saved, scheme.directlyDefinedAttributes.getValue(name))
            }
            for ((key, saved) in retainedColors) {
                assertSame(saved, scheme.directlyDefinedColors.getValue(key))
            }
            parent.isArmed = false
            assertEquals(Color.RED, scheme.getAttributes(target).foregroundColor)
        }
    }

    @Test
    fun `default parent requires concrete seeds for missing attribute and color restoration`() {
        SwingUtilities.invokeAndWait {
            val target = TextAttributesKey.createTextAttributesKey("AYU_PRUNE_DEFAULT_TARGET")
            val empty = TextAttributesKey.createTextAttributesKey("AYU_PRUNE_DEFAULT_EMPTY")
            val inherited = TextAttributesKey.createTextAttributesKey("AYU_PRUNE_DEFAULT_INHERITED")
            val color = ColorKey.createColorKey("AYU_PRUNE_DEFAULT_COLOR")
            val parent =
                DefaultColorsScheme().apply {
                    readExternal(
                        JDOMUtil.load(
                            """
                            <scheme name="Default" version="142">
                              <colors><option name="${color.externalName}" value="ff0000" /></colors>
                              <attributes>
                                <option name="${target.externalName}">
                                  <value><option name="FOREGROUND" value="ff0000" /></value>
                                </option>
                                <option name="${empty.externalName}"><value /></option>
                              </attributes>
                            </scheme>
                            """.trimIndent(),
                        ),
                    )
                }
            val scheme = EditorColorsSchemeImpl(parent)
            val parentAttributes = parent.directlyDefinedAttributes
            assertEquals(TextAttributes(), parentAttributes.getValue(empty.externalName))
            scheme.setAttributes(target, attributes(Color.BLUE))
            scheme.setAttributes(empty, attributes(Color.BLUE))
            scheme.setAttributes(inherited, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            scheme.setColor(color, Color.BLUE)

            for (name in scheme.directlyDefinedAttributes.keys) {
                scheme.setAttributes(TextAttributesKey.find(name), AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            }
            scheme.setColor(color, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            insertAttributes(scheme, target, parentAttributes.getValue(target.externalName))
            insertAttributes(scheme, empty, parentAttributes.getValue(empty.externalName))
            scheme.setColor(color, parent.directlyDefinedColors.getValue(color))
            scheme.optimizeAttributeMap()
            scheme.setAttributes(inherited, AbstractColorsScheme.INHERITED_ATTRS_MARKER)

            assertFalse(scheme.directlyDefinedAttributes.containsKey(target.externalName))
            assertFalse(scheme.directlyDefinedAttributes.containsKey(empty.externalName))
            assertFalse(scheme.directlyDefinedColors.containsKey(color))
            assertSame(
                AbstractColorsScheme.INHERITED_ATTRS_MARKER,
                scheme.directlyDefinedAttributes.getValue(inherited.externalName),
            )
            assertEquals(Color.RED, scheme.getAttributes(target).foregroundColor)
            assertEquals(Color.RED, scheme.getColor(color))
        }
    }

    @Test
    fun `native save snapshot taken during pruning outlives successful live repair`() {
        SwingUtilities.invokeAndWait {
            val parent = assertNotNull(EditorColorsManager.getInstance().getScheme("Default"))
            val scheme = EditorColorsSchemeImpl(parent)
            scheme.name = "Ayu pruning persistence probe"
            val target = TextAttributesKey.createTextAttributesKey("AYU_PRUNE_SAVE_TARGET")
            val collateral = TextAttributesKey.createTextAttributesKey("AYU_PRUNE_SAVE_COLLATERAL")
            scheme.setAttributes(target, attributes(Color.BLUE))
            val original = attributes(Color.ORANGE)
            scheme.setAttributes(collateral, original)
            for (name in scheme.directlyDefinedAttributes.keys) {
                scheme.setAttributes(TextAttributesKey.find(name), AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            }
            scheme.optimizeAttributeMap()

            // The native save pipeline constructs XML before its later file write.
            val queuedXml = Element("scheme")
            scheme.writeExternal(queuedXml)
            insertAttributes(scheme, collateral, original)
            assertSame(original, scheme.directlyDefinedAttributes.getValue(collateral.externalName))
            assertFalse(scheme.directlyDefinedAttributes.containsKey(target.externalName))

            // Live readback cannot repair a snapshot already handed to the writer.
            val reloaded = EditorColorsSchemeImpl(parent)
            reloaded.readExternal(queuedXml)
            assertFalse(reloaded.directlyDefinedAttributes.containsKey(collateral.externalName))
            assertSame(original, scheme.directlyDefinedAttributes.getValue(collateral.externalName))
        }
    }

    @Test
    fun `bundled Ayu baselines permit pruning the syntax target without collateral writes`() {
        SwingUtilities.invokeAndWait {
            val target = TextAttributesKey.find("KOTLIN_LOCAL_VARIABLE")
            for (variant in listOf("Dark", "Mirage", "Light")) {
                val scheme = EditorColorsSchemeImpl(null)
                val resource = assertNotNull(javaClass.getResourceAsStream("/themes/AyuIslands$variant.xml"))
                resource.use { scheme.readExternal(JDOMUtil.load(it)) }
                // The native bundled loader normalizes before registering these same resources.
                scheme.optimizeAttributeMap()
                val parent = assertIs<DefaultColorsScheme>(scheme.parentScheme)
                val originalAttributes = scheme.directlyDefinedAttributes
                val originalColors = scheme.directlyDefinedColors
                assertFalse(originalAttributes.containsKey(target.externalName), variant)
                scheme.setAttributes(target, attributes(Color.BLUE))

                val inherited = parent.getAttributes(target, false) ?: AbstractColorsScheme.INHERITED_ATTRS_MARKER
                insertAttributes(scheme, target, inherited)
                scheme.optimizeAttributeMap()

                assertEquals(originalAttributes, scheme.directlyDefinedAttributes, variant)
                assertEquals(originalColors, scheme.directlyDefinedColors, variant)
            }
        }
    }

    private class GuardedParent(
        original: EditorColorsScheme,
    ) : EditorColorsSchemeImpl(original) {
        var isArmed = false

        override fun getAttributes(key: TextAttributesKey?): TextAttributes? {
            check(!isArmed) { "Restoration called the parent attribute getter" }
            return super.getAttributes(key)
        }

        override fun getColor(key: ColorKey?): Color? {
            check(!isArmed) { "Restoration called the parent color getter" }
            return super.getColor(key)
        }

        override fun getDirectlyDefinedAttributes(key: TextAttributesKey): TextAttributes? {
            check(!isArmed) { "Restoration called the parent direct attribute getter" }
            return super.getDirectlyDefinedAttributes(key)
        }

        override fun getDirectlyDefinedColor(key: ColorKey): Color? {
            check(!isArmed) { "Restoration called the parent direct color getter" }
            return super.getDirectlyDefinedColor(key)
        }
    }

    private fun insertAttributes(
        scheme: EditorColorsSchemeImpl,
        key: TextAttributesKey,
        saved: TextAttributes,
    ) {
        scheme.setAttributes(key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
        if (saved !== AbstractColorsScheme.INHERITED_ATTRS_MARKER) {
            val sentinel = TextAttributes()
            sentinel.fontType = if (saved.fontType == Font.BOLD) Font.ITALIC else Font.BOLD
            scheme.setAttributes(key, sentinel)
        }
        scheme.setAttributes(key, saved)
    }

    private fun attributes(color: Color): TextAttributes = TextAttributes().apply { foregroundColor = color }
}
