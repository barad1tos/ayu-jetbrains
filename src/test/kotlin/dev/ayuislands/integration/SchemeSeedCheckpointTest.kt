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
import dev.ayuislands.theme.SchemeSeedCheckpoint
import dev.ayuislands.theme.SchemeSeedState
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemeSeedCheckpointTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `preparation captures seeds without writes and application leaves metadata unchanged`() {
        SwingUtilities.invokeAndWait {
            val target = ColorKey.createColorKey("AYU_SEED_CAPTURE")
            val scheme = SeedScheme()
            scheme.setColor(target, Color.RED)
            scheme.writes = 0
            scheme.setSaveNeeded(false)
            val metadata = Any()
            scheme.metaProperties["foreign"] = metadata
            val checkpoint = SchemeSeedCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { true }
            assertEquals(0, scheme.writes)
            assertEquals(SchemeState.UNCHANGED, scheme.schemeState)
            assertSame(Color.RED, scheme.directlyDefinedColors[target])
            checkpoint.apply()
            assertFalse(checkpoint.hasPending)
            assertEquals(1, scheme.writes)
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, scheme.directlyDefinedColors[target])
            assertSame(metadata, scheme.metaProperties["foreign"])
            assertEquals(SchemeState.POSSIBLY_CHANGED, scheme.schemeState)
        }
    }

    @Test
    fun `throw before mutation retries the same seed but throw after mutation does not replay it`() {
        for (afterMutation in listOf(false, true)) {
            SwingUtilities.invokeAndWait {
                val target = ColorKey.createColorKey("AYU_SEED_FAILURE_$afterMutation")
                val scheme = SeedScheme()
                scheme.setColor(target, Color.RED)
                scheme.writes = 0
                val checkpoint = SchemeSeedCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { true }
                scheme.failure = if (afterMutation) Failure.AFTER_WRITE else Failure.BEFORE_WRITE
                assertFailsWith<IllegalStateException> { checkpoint.apply() }
                assertEquals(!afterMutation, checkpoint.hasPending)
                scheme.failure = Failure.NONE
                checkpoint.apply()
                assertFalse(checkpoint.hasPending)
                assertEquals(if (afterMutation) 1 else 2, scheme.writes)
                assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, scheme.directlyDefinedColors[target])
            }
        }
    }

    @Test
    fun `unreadable post-write state is observed before deciding whether to retry a setter`() {
        for (failure in Failure.entries) {
            SwingUtilities.invokeAndWait {
                val target = ColorKey.createColorKey("AYU_SEED_UNREADABLE_$failure")
                val scheme = SeedScheme()
                scheme.setColor(target, Color.RED)
                scheme.writes = 0
                val checkpoint = SchemeSeedCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { true }
                scheme.failure = failure
                scheme.failReadsAfterWrite = true
                assertFailsWith<IllegalStateException> { checkpoint.apply() }
                assertTrue(checkpoint.hasPending)
                val interrupted = checkpoint.observations.single()
                assertEquals(SchemeSeedState.WRITING, interrupted.state)
                assertEquals(failure == Failure.NONE, interrupted.hasReturnedNormally)
                scheme.failure = Failure.NONE
                scheme.failReadsAfterWrite = false
                scheme.failReads = false
                checkpoint.apply()
                assertFalse(checkpoint.hasPending)
                assertEquals(if (failure == Failure.BEFORE_WRITE) 2 else 1, scheme.writes)
                assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, scheme.directlyDefinedColors[target])
                assertEquals(failure != Failure.AFTER_WRITE, checkpoint.observations.single().hasReturnedNormally)
                assertEquals(failure == Failure.NONE, interrupted.hasReturnedNormally)
            }
        }
    }

    @Test
    fun `an attribute inheritance seed keeps the native marker identity`() {
        SwingUtilities.invokeAndWait {
            val target = TextAttributesKey.createTextAttributesKey("AYU_SEED_ATTRIBUTE")
            val scheme = SeedScheme()
            val original = TextAttributes().apply { foregroundColor = Color.RED }
            scheme.setAttributes(target, original)
            val checkpoint = SchemeSeedCheckpoint.prepare(scheme, setOf(SchemeEntry.AttributesEntry(target))) { true }
            assertSame(original, scheme.directlyDefinedAttributes[target.externalName])
            checkpoint.apply()
            assertSame(
                AbstractColorsScheme.INHERITED_ATTRS_MARKER,
                scheme.directlyDefinedAttributes[target.externalName],
            )
            assertFalse(checkpoint.hasPending)
        }
    }

    @Test
    fun `a successful no-op retains the actual value and does not authorize premature deletion`() {
        SwingUtilities.invokeAndWait {
            val target = ColorKey.createColorKey("AYU_SEED_NOOP")
            val scheme = SeedScheme()
            scheme.setColor(target, Color.RED)
            scheme.writes = 0
            val entry = SchemeEntry.ColorEntry(target)
            val checkpoint = SchemeSeedCheckpoint.prepare(scheme, setOf(entry)) { true }
            scheme.ignoreWrites = true
            checkpoint.apply()
            checkpoint.apply()
            assertEquals(1, scheme.writes)
            assertFalse(checkpoint.hasPending)
            assertSame(Color.RED, scheme.directlyDefinedColors[target])
            assertFailsWith<IllegalStateException> {
                SchemeDeletionCheckpoint.prepare(scheme, checkpoint.expectedCells.keys) { true }
            }
        }
    }

    @Test
    fun `foreign replacement during a failing setter is preserved on recovery`() {
        SwingUtilities.invokeAndWait {
            val target = ColorKey.createColorKey("AYU_SEED_FOREIGN_AFTER_WRITE")
            val scheme = SeedScheme()
            scheme.setColor(target, Color.RED)
            scheme.writes = 0
            val entry = SchemeEntry.ColorEntry(target)
            val checkpoint = SchemeSeedCheckpoint.prepare(scheme, setOf(entry)) { true }
            scheme.replacementAfterWrite = Color.MAGENTA
            scheme.failure = Failure.AFTER_WRITE
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertFalse(checkpoint.hasPending)
            scheme.failure = Failure.NONE
            scheme.replacementAfterWrite = null
            checkpoint.apply()
            assertEquals(1, scheme.writes)
            assertFalse(entry in checkpoint.expectedCells)
            assertSame(Color.MAGENTA, scheme.directlyDefinedColors[target])
        }
    }

    @Test
    fun `interrupted two-step color seeds do not replay a completed barrier`() {
        for (afterMutation in listOf(false, true)) {
            SwingUtilities.invokeAndWait {
                val target = ColorKey.createColorKey("AYU_SEED_BARRIER_$afterMutation")
                val parent = DefaultColorsScheme()
                parent.readExternal(
                    JDOMUtil.load(
                        """
                        <scheme name="Seed parent" version="142" default_scheme="true">
                          <colors><option name="${target.externalName}" value="00ff00" /></colors>
                        </scheme>
                        """.trimIndent(),
                    ),
                )
                val scheme = SeedScheme(parent)
                scheme.setColor(target, AbstractColorsScheme.INHERITED_COLOR_MARKER)
                val opaque =
                    object : Color(0) {
                        override fun equals(other: Any?): Boolean = error("Opaque equality must not run")
                    }
                scheme.setColor(target, opaque)
                scheme.writes = 0
                val checkpoint = SchemeSeedCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { true }
                scheme.failure = if (afterMutation) Failure.AFTER_WRITE else Failure.BEFORE_WRITE
                scheme.failureAtWrite = 2
                assertFailsWith<IllegalStateException> { checkpoint.apply() }
                assertEquals(!afterMutation, checkpoint.hasPending)
                scheme.failure = Failure.NONE
                checkpoint.apply()
                assertEquals(if (afterMutation) 2 else 3, scheme.writes)
                assertSame(parent.directlyDefinedColors[target], scheme.directlyDefinedColors[target])
                assertFalse(checkpoint.hasPending)
            }
        }
    }

    @Test
    fun `a foreign equal value or newer owner prevents seed writes`() {
        for (replaceValue in listOf(false, true)) {
            SwingUtilities.invokeAndWait {
                val target = ColorKey.createColorKey("AYU_SEED_FOREIGN_$replaceValue")
                val scheme = SeedScheme()
                scheme.setColor(target, Color.RED)
                var owned = true
                val entry = SchemeEntry.ColorEntry(target)
                val checkpoint = SchemeSeedCheckpoint.prepare(scheme, setOf(entry)) { owned }
                val foreign = if (replaceValue) Color(Color.RED.rgb, true) else Color.RED
                if (replaceValue) {
                    scheme.setColor(target, AbstractColorsScheme.INHERITED_COLOR_MARKER)
                    scheme.setColor(target, foreign)
                } else {
                    owned = false
                }
                scheme.writes = 0
                checkpoint.apply()
                assertFalse(checkpoint.hasPending)
                assertEquals(0, scheme.writes)
                assertSame(foreign, scheme.directlyDefinedColors[target])
                assertFalse(entry in checkpoint.expectedCells)
            }
        }
    }

    @Test
    fun `unrelated prunable fields reject seed preparation without mutation`() {
        SwingUtilities.invokeAndWait {
            val target = ColorKey.createColorKey("AYU_SEED_COLLATERAL_TARGET")
            val foreign = ColorKey.createColorKey("AYU_SEED_COLLATERAL_FOREIGN")
            val scheme = SeedScheme()
            scheme.setColor(target, Color.RED)
            scheme.setColor(foreign, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            scheme.writes = 0
            assertFailsWith<IllegalStateException> {
                SchemeSeedCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { true }
            }
            assertEquals(0, scheme.writes)
            assertSame(Color.RED, scheme.directlyDefinedColors[target])
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, scheme.directlyDefinedColors[foreign])
        }
    }

    @Test
    fun `ownership changed by an observation prevents the next seed write`() {
        SwingUtilities.invokeAndWait {
            val target = ColorKey.createColorKey("AYU_SEED_OWNER_DURING_READ")
            val scheme = SeedScheme()
            scheme.setColor(target, Color.RED)
            scheme.writes = 0
            var owned = true
            val checkpoint = SchemeSeedCheckpoint.prepare(scheme, setOf(SchemeEntry.ColorEntry(target))) { owned }
            scheme.onColorsRead = { owned = false }
            checkpoint.apply()
            assertEquals(0, scheme.writes)
            assertSame(Color.RED, scheme.directlyDefinedColors[target])
            assertFalse(checkpoint.hasPending)
        }
    }

    private enum class Failure { NONE, BEFORE_WRITE, AFTER_WRITE }

    private class SeedScheme(
        parent: DefaultColorsScheme = DefaultColorsScheme(),
    ) : EditorColorsSchemeImpl(parent) {
        var writes = 0
        var failure = Failure.NONE
        var failureAtWrite: Int? = null
        var failReadsAfterWrite = false
        var failReads = false
        var ignoreWrites = false
        var replacementAfterWrite: Color? = null
        var onColorsRead: (() -> Unit)? = null

        override fun getDirectlyDefinedColors(): Map<ColorKey, Color> {
            check(!failReads) { "Seed observation failed" }
            val callback = onColorsRead
            onColorsRead = null
            callback?.invoke()
            return super.getDirectlyDefinedColors()
        }

        override fun setColor(
            key: ColorKey,
            color: Color?,
        ) {
            writes++
            if (failReadsAfterWrite) failReads = true
            val currentFailure = if (failureAtWrite == null || writes == failureAtWrite) failure else Failure.NONE
            check(currentFailure != Failure.BEFORE_WRITE) { "Seed setter failed before mutation" }
            if (ignoreWrites) return
            super.setColor(key, color)
            replacementAfterWrite?.let { foreign ->
                super.setColor(key, INHERITED_COLOR_MARKER)
                super.setColor(key, foreign)
            }
            check(currentFailure != Failure.AFTER_WRITE) { "Seed setter failed after mutation" }
        }
    }
}
