package dev.ayuislands.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.Scheme
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.theme.AyuSchemeResource
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.CheckpointEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.SchemeComparison
import dev.ayuislands.theme.SchemePersistenceCapture
import dev.ayuislands.theme.SchemePersistenceResult
import dev.ayuislands.theme.SchemePruningPlan
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeTarget
import dev.ayuislands.theme.capturePruningFacts
import dev.ayuislands.theme.prepareSchemePruning
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercises native registered identities in a separate graphical fixture, never synthetic replacements. */
class RegisteredSchemeTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `native fixture registers every canonical Ayu scheme and editable copy`() {
        SwingUtilities.invokeAndWait {
            val manager = EditorColorsManager.getInstance()
            val active = manager.globalScheme
            for (variant in listOf("Dark", "Mirage", "Light")) {
                val name = "Ayu Islands $variant"
                val bundled = assertIs<AbstractColorsScheme>(assertNotNull(manager.getScheme(name), name))
                val editableName = Scheme.EDITABLE_COPY_PREFIX + name
                val editable =
                    assertIs<AbstractColorsScheme>(assertNotNull(manager.getScheme(editableName), editableName))
                assertNotSame(bundled, editable, name)
                val expectedParent = if (variant == "Light") "Default" else "Darcula"
                assertEquals(expectedParent, assertIs<DefaultColorsScheme>(bundled.parentScheme).name)
                assertSame(bundled.parentScheme, editable.parentScheme)
                assertFalse(bundled.directlyDefinedAttributes.isEmpty(), name)
                assertFalse(bundled.directlyDefinedColors.isEmpty(), name)
                assertSame(bundled, manager.getScheme(name))
                assertSame(editable, manager.getScheme(editableName))
                val resource = assertIs<AbstractColorsScheme>(manager.getScheme("/themes/AyuIslands$variant.xml"))
                assertSame(editable, resource)
                assertSame(bundled, resource.original)
                assertEquals(AyuPlugin.ID_STRING, bundled.metaProperties[AbstractColorsScheme.META_INFO_PLUGIN_ID])
                val expectedVariant = AyuVariant.valueOf(variant.uppercase())
                val identity = assertNotNull(AyuSchemeResource.capture(expectedVariant, bundled))
                assertSame(bundled, identity.scheme)
                assertEquals(expectedVariant, identity.variant)
                assertTrue(identity.isCurrent())
                assertNull(AyuSchemeResource.capture(expectedVariant, editable))
            }
            assertFalse(ApplicationManager.getApplication().isHeadlessEnvironment)
            assertSame(active, manager.globalScheme)
        }
    }

    @Test
    fun `persistence capture qualifies canonical resources without changing their native state`() {
        SwingUtilities.invokeAndWait {
            val manager = EditorColorsManager.getInstance()
            val active = manager.globalScheme
            for (variant in AyuVariant.entries) {
                val name = AyuEditorSchemeBinder.targetSchemeName(variant)
                val scheme = assertIs<AbstractColorsScheme>(assertNotNull(manager.getScheme(name)))
                val resource = assertNotNull(AyuSchemeResource.capture(variant, scheme))
                val target = assertNotNull(SchemeTarget.capture(SchemeSource.Named(name)))
                val colors = scheme.directlyDefinedColors
                val attributes = scheme.directlyDefinedAttributes
                val flyweights = attributes.mapValues { (_, value) -> value.flyweight }
                val metadata = scheme.metaProperties.toMap()
                val saveState = scheme.schemeState
                val entry = SchemeEntry.ColorEntry(EditorColors.CARET_ROW_COLOR)
                assertNotNull(colors[entry.key])
                val batch =
                    EditorSchemeOverrides.checkpoints.prepareBatch(
                        target,
                        mapOf(entry to CheckpointEntry(SchemeValue.ColorValue(Color.BLUE), null, null)),
                    )
                val record = batch.records.single()

                val capture =
                    assertIs<SchemePersistenceResult.Ready>(
                        SchemePersistenceCapture.capture(target, record, resource),
                    )

                assertSame(record, capture.record)
                assertTrue(SchemePersistenceCapture.isCurrent(capture))
                assertEquals(colors.keys, scheme.directlyDefinedColors.keys)
                for ((key, value) in colors) {
                    val observed =
                        scheme.directlyDefinedColors.entries.single {
                            it.key.externalName == key.externalName
                        }
                    assertSame(key, observed.key)
                    assertSame(value, observed.value)
                }
                assertEquals(attributes.keys, scheme.directlyDefinedAttributes.keys)
                for ((key, value) in attributes) {
                    val observed = scheme.directlyDefinedAttributes.getValue(key)
                    assertSame(value, observed)
                    assertSame(flyweights.getValue(key), observed.flyweight)
                }
                assertEquals(metadata, scheme.metaProperties.toMap())
                assertEquals(saveState, scheme.schemeState)
                assertSame(active, manager.globalScheme)
                assertSame(scheme, manager.getScheme(name))
            }
        }
    }

    @Test
    fun `syntax preparation computes registered resources without changing native cells`() {
        SwingUtilities.invokeAndWait {
            val manager = EditorColorsManager.getInstance()
            val active = manager.globalScheme
            val originals =
                AyuVariant.entries.map { variant ->
                    assertIs<AbstractColorsScheme>(manager.getScheme(AyuEditorSchemeBinder.targetSchemeName(variant)))
                }
            val editable =
                AyuVariant.entries.map { variant ->
                    assertIs<AbstractColorsScheme>(manager.getScheme(variant.editorSchemeResource))
                }
            val schemes = originals + editable
            val colors = schemes.associateWith { it.directlyDefinedColors }
            val attributes = schemes.associateWith { it.directlyDefinedAttributes }
            val flyweights =
                schemes.associateWith { scheme ->
                    scheme.directlyDefinedAttributes.mapValues { (_, value) -> value.flyweight }
                }
            val metadata = schemes.associateWith { it.metaProperties.toMap() }
            val saveStates = schemes.associateWith { it.schemeState }

            val prepared =
                SyntaxIntensityService().prepare(
                    SyntaxPresetConfig("NEON", emptyMap()),
                    isLicensed = true,
                    ignorePluginSyntaxColorsEnabled = true,
                )

            assertEquals(editable, prepared.changes.take(editable.size).map { it.scheme })
            assertTrue(prepared.changes.all { it.attributes.isNotEmpty() })
            assertTrue(prepared.replacementFonts.isEmpty())
            for (scheme in schemes) {
                assertEquals(colors.getValue(scheme).keys, scheme.directlyDefinedColors.keys)
                for ((key, color) in colors.getValue(scheme)) {
                    assertSame(color, scheme.directlyDefinedColors.getValue(key))
                }
                assertEquals(attributes.getValue(scheme).keys, scheme.directlyDefinedAttributes.keys)
                for ((key, value) in attributes.getValue(scheme)) {
                    val actual = scheme.directlyDefinedAttributes.getValue(key)
                    assertSame(value, actual)
                    assertSame(flyweights.getValue(scheme).getValue(key), actual.flyweight)
                }
                assertEquals(metadata.getValue(scheme), scheme.metaProperties.toMap())
                assertEquals(saveStates.getValue(scheme), scheme.schemeState)
                assertSame(scheme, manager.getScheme(scheme.name))
            }
            assertSame(active, manager.globalScheme)
        }
    }

    @Test
    fun `registered native schemes restore only owned absence targets and preserve foreign objects`() {
        SwingUtilities.invokeAndWait {
            val manager = EditorColorsManager.getInstance()
            val active = manager.globalScheme
            val attribute = TextAttributesKey.find("KOTLIN_LOCAL_VARIABLE")
            val color = EditorColors.SELECTED_TEARLINE_COLOR
            for (variant in listOf("Dark", "Mirage", "Light")) {
                for (prefix in listOf("", Scheme.EDITABLE_COPY_PREFIX)) {
                    val name = "${prefix}Ayu Islands $variant"
                    val scheme = assertIs<EditorColorsSchemeImpl>(assertNotNull(manager.getScheme(name), name))
                    assertTargetOnlyRestore(scheme, attribute, color)
                    assertSame(scheme, manager.getScheme(name))
                }
            }
            assertSame(active, manager.globalScheme)
        }
    }

    private fun assertTargetOnlyRestore(
        scheme: EditorColorsSchemeImpl,
        attribute: TextAttributesKey,
        color: ColorKey,
    ) {
        val parent = assertIs<DefaultColorsScheme>(scheme.parentScheme)
        val attributes = scheme.directlyDefinedAttributes
        val colors = scheme.directlyDefinedColors
        val flyweights = attributes.mapValues { (_, value) -> value.flyweight }
        val marker = "ANNOTATION_NAME_ATTRIBUTES"
        assertFalse(attributes.containsKey(attribute.externalName), scheme.name)
        assertFalse(colors.containsKey(color), scheme.name)
        assertSame(AbstractColorsScheme.INHERITED_ATTRS_MARKER, attributes.getValue(marker))
        assertPruning(parent, attributes, colors)
        val written = TextAttributes().apply { foregroundColor = Color.BLUE }
        assertIs<SchemePruningPlan.Prepared>(
            prepareSchemePruning(
                parent,
                attributes + (attribute.externalName to written),
                colors + (color to Color.BLUE),
                setOf(attribute),
                setOf(color),
            ),
        )
        try {
            scheme.setAttributes(attribute, written)
            scheme.setColor(color, Color.BLUE)
            assertSame(written, scheme.directlyDefinedAttributes[attribute.externalName])
            assertSame(Color.BLUE, scheme.directlyDefinedColors[color])
        } finally {
            val actualAttributes = scheme.directlyDefinedAttributes
            val actualColors = scheme.directlyDefinedColors
            if (attribute.externalName in actualAttributes || color in actualColors) {
                assertSame(written, actualAttributes[attribute.externalName])
                assertSame(Color.BLUE, actualColors[color])
                val plan =
                    assertIs<SchemePruningPlan.Prepared>(
                        prepareSchemePruning(parent, actualAttributes, actualColors, setOf(attribute), setOf(color)),
                    )
                plan.attributes.forEach { (key, steps) -> steps.forEach { scheme.setAttributes(key, it) } }
                plan.colors.forEach { (key, steps) -> steps.forEach { scheme.setColor(key, it) } }
                scheme.optimizeAttributeMap()
            }
        }

        assertEquals(attributes.keys, scheme.directlyDefinedAttributes.keys, scheme.name)
        assertEquals(colors.keys, scheme.directlyDefinedColors.keys, scheme.name)
        for ((key, value) in attributes) {
            val actual = scheme.directlyDefinedAttributes.getValue(key)
            assertSame(value, actual, key)
            assertSame(flyweights.getValue(key), actual.flyweight, key)
        }
        for ((key, value) in colors) assertSame(value, scheme.directlyDefinedColors.getValue(key), key.externalName)
        assertSame(parent, scheme.parentScheme)
    }

    private fun assertPruning(
        parent: DefaultColorsScheme,
        attributes: Map<String, TextAttributes>,
        colors: Map<ColorKey, Color>,
        attributeTargets: Set<String> = emptySet(),
        colorTargets: Set<String> = emptySet(),
    ) {
        val facts = assertNotNull(capturePruningFacts(parent, attributes, colors))
        assertTrue(SchemeComparison.permitsPruning(facts, attributeTargets, colorTargets))
    }
}
