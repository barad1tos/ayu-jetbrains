package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.SchemeState
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.SchemeDeletionCheckpoint
import dev.ayuislands.theme.SchemeDeletionState
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemeDeletionCheckpointTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `prepared deletion changes only acknowledged seeded targets`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val color = ColorKey.createColorKey("AYU_DELETE_COLOR")
            val attributes = TextAttributesKey.createTextAttributesKey("AYU_DELETE_ATTRIBUTES")
            val foreignKey = TextAttributesKey.createTextAttributesKey("AYU_DELETE_FOREIGN")
            val foreign = TextAttributes().apply { foregroundColor = Color.GREEN }
            scheme.setColor(color, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            scheme.setAttributes(attributes, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            scheme.setAttributes(foreignKey, foreign)
            val metadata = Any()
            scheme.metaProperties["foreign"] = metadata
            scheme.setSaveNeeded(false)
            val checkpoint =
                SchemeDeletionCheckpoint.prepare(
                    scheme,
                    setOf(SchemeEntry.ColorEntry(color), SchemeEntry.AttributesEntry(attributes)),
                ) { true }
            assertEquals(0, scheme.deletions)
            assertEquals(SchemeState.UNCHANGED, scheme.schemeState)
            assertTrue(scheme.directlyDefinedColors.containsKey(color))
            checkpoint.apply()
            assertFalse(checkpoint.hasPending)
            assertTrue(checkpoint.states.values.all { it == SchemeDeletionState.RESTORED })
            assertEquals(1, scheme.deletions)
            assertEquals(SchemeState.POSSIBLY_CHANGED, scheme.schemeState)
            assertFalse(scheme.directlyDefinedColors.containsKey(color))
            assertFalse(scheme.directlyDefinedAttributes.containsKey(attributes.externalName))
            assertSame(foreign, scheme.directlyDefinedAttributes[foreignKey.externalName])
            assertSame(metadata, scheme.metaProperties["foreign"])
        }
    }

    @Test
    fun `fresh collateral census blocks deletion and retains its prepared targets for retry`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val target = ColorKey.createColorKey("AYU_DELETE_RETRY")
            val foreign = ColorKey.createColorKey("AYU_DELETE_LATER_FOREIGN")
            scheme.setColor(target, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            val checkpoint =
                SchemeDeletionCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { true }
            scheme.setColor(foreign, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertEquals(0, scheme.deletions)
            assertTrue(checkpoint.hasPending)
            assertTrue(scheme.directlyDefinedColors.containsKey(target))
            assertTrue(scheme.directlyDefinedColors.containsKey(foreign))
            scheme.setColor(foreign, Color.GREEN)
            checkpoint.apply()
            assertEquals(1, scheme.deletions)
            assertFalse(checkpoint.hasPending)
            assertSame(Color.GREEN, scheme.directlyDefinedColors[foreign])
        }
    }

    @Test
    fun `failed optimizer preserves observed progress and does not replay a completed deletion`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val target = ColorKey.createColorKey("AYU_DELETE_THROW")
            scheme.setColor(target, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            val checkpoint =
                SchemeDeletionCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { true }
            scheme.failAfterDeletion = true
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertEquals(SchemeState.POSSIBLY_CHANGED, scheme.schemeState)
            assertFalse(checkpoint.hasPending)
            assertFalse(scheme.directlyDefinedColors.containsKey(target))
            assertEquals(SchemeDeletionState.RESTORED, checkpoint.states[SchemeEntry.ColorEntry(target)])
            checkpoint.apply()
            assertEquals(1, scheme.deletions)
        }
    }

    @Test
    fun `a newer owner prevents deletion even when the original seed is unchanged`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val target = ColorKey.createColorKey("AYU_DELETE_OWNER")
            scheme.setColor(target, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            var owned = true
            val checkpoint =
                SchemeDeletionCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { owned }
            owned = false
            checkpoint.apply()
            assertEquals(0, scheme.deletions)
            assertFalse(checkpoint.hasPending)
            assertEquals(SchemeDeletionState.FOREIGN, checkpoint.states[SchemeEntry.ColorEntry(target)])
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, scheme.directlyDefinedColors[target])
        }
    }

    @Test
    fun `ownership changed during observation prevents native deletion`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val target = ColorKey.createColorKey("AYU_DELETE_OWNER_DURING_READ")
            scheme.setColor(target, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            var owned = true
            val checkpoint = SchemeDeletionCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { owned }
            scheme.onColorsRead = { owned = false }
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertEquals(0, scheme.deletions)
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, scheme.directlyDefinedColors[target])
            checkpoint.apply()
            assertFalse(checkpoint.hasPending)
            assertEquals(0, scheme.deletions)
        }
    }

    @Test
    fun `a foreign replacement during the final census is not adopted as a deletion seed`() {
        SwingUtilities.invokeAndWait {
            val target = ColorKey.createColorKey("AYU_DELETE_FOREIGN_DURING_CENSUS")
            val parent = DefaultColorsScheme()
            parent.readExternal(
                JDOMUtil.load(
                    """
                    <scheme name="Deletion parent" version="142" default_scheme="true">
                      <colors><option name="${target.externalName}" value="00ff00" /></colors>
                    </scheme>
                    """.trimIndent(),
                ),
            )
            val scheme = CountingScheme(parent)
            scheme.setColor(target, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            scheme.setColor(target, parent.directlyDefinedColors.getValue(target))
            val checkpoint = SchemeDeletionCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { true }
            val foreign = Color(0, 255, 0)
            var reads = 0
            scheme.onColorsRead = {
                reads++
                if (reads == 2) {
                    scheme.setColor(target, AbstractColorsScheme.INHERITED_COLOR_MARKER)
                    scheme.setColor(target, foreign)
                }
            }
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertEquals(0, scheme.deletions)
            assertSame(foreign, scheme.directlyDefinedColors[target])
            checkpoint.apply()
            assertFalse(checkpoint.hasPending)
            assertEquals(0, scheme.deletions)
            assertSame(foreign, scheme.directlyDefinedColors[target])
        }
    }

    private class CountingScheme(
        parent: DefaultColorsScheme = DefaultColorsScheme(),
    ) : EditorColorsSchemeImpl(parent) {
        var deletions = 0
        var failAfterDeletion = false
        var onColorsRead: (() -> Unit)? = null

        override fun getDirectlyDefinedColors(): Map<ColorKey, Color> {
            onColorsRead?.invoke()
            return super.getDirectlyDefinedColors()
        }

        override fun optimizeAttributeMap() {
            deletions++
            super.optimizeAttributeMap()
            check(!failAfterDeletion) { "Optimizer interrupted after native mutation" }
        }
    }
}
