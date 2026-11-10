package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.CheckpointEntry
import dev.ayuislands.theme.EditorSchemeOverrides.OverrideState
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.SchemeComponent
import dev.ayuislands.theme.SchemeDeletionState
import dev.ayuislands.theme.SchemeSeedState
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeTarget
import dev.ayuislands.theme.SchemeWritePhase
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.plaf.ColorUIResource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemeWriteBatchTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `ordinary value changes retain the exact existing ownership and metadata`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_RETAINED_OWNER"))
            scheme.setColor(entry.key, Color.RED)
            val claim =
                OverrideState.Owned(
                    EditorSchemeOwner.Vcs,
                    SchemeValue.ColorValue(Color.RED),
                    SchemeValue.ColorValue(Color.ORANGE),
                )
            val original =
                EditorSchemeOverrides.checkpoints.prepareBatch(
                    target(scheme),
                    mapOf(entry to CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), claim, "retained claim")),
                )
            assertTrue(original.apply())
            original.accept()
            val accepted = original.records.single().planned
            val desired =
                CheckpointEntry(
                    SchemeValue.ColorValue(Color.PINK),
                    accepted.owner as OverrideState,
                    accepted.metadataValue as String,
                )
            val update = EditorSchemeOverrides.checkpoints.prepareBatch(target(scheme), mapOf(entry to desired))
            val record = update.records.single()
            assertSame(accepted.owner, record.before.owner)
            assertSame(accepted.owner, record.planned.owner)
            assertSame(accepted.metadataValue, record.planned.metadataValue)
            assertTrue(update.apply())
            val observed = EditorSchemeOverrides.checkpoints.prepareBatch(target(scheme), mapOf(entry to desired))
            assertSame(
                accepted.owner,
                observed.records
                    .single()
                    .before.owner,
            )
            assertSame(accepted.metadataValue, scheme.metaProperties[entry.metadataKey])
            observed.accept()
            update.prepareRestore()
            assertTrue(update.restore())
            assertSame(Color.ORANGE, scheme.directlyDefinedColors[entry.key])
            assertSame(accepted.metadataValue, scheme.metaProperties[entry.metadataKey])
        }
    }

    @Test
    fun `unsupported deletion parent is rejected before effects but existing cells remain usable`() {
        SwingUtilities.invokeAndWait {
            val parent = EditorColorsSchemeImpl(DefaultColorsScheme()).apply { name = "Custom parent" }
            val scheme = CountingScheme(parent)
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_UNSUPPORTED_PARENT"))
            val metadata = scheme.metaProperties.toMap()
            assertFailsWith<IllegalStateException> { prepare(scheme, entry) }
            assertEquals(0, scheme.writes)
            assertEquals(metadata, scheme.metaProperties.toMap())

            scheme.setColor(entry.key, Color.RED)
            val batch = prepare(scheme, entry)
            assertTrue(batch.apply())
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertSame(Color.RED, scheme.directlyDefinedColors[entry.key])
            assertEquals(0, scheme.deletions)
        }
    }

    @Test
    fun `known unrelated prunable values reject preparation without native effects`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_KNOWN_COLLATERAL"))
            val foreign = ColorKey.createColorKey("AYU_BATCH_EXISTING_FOREIGN")
            scheme.setColor(foreign, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            val metadata = scheme.metaProperties.toMap()
            assertFailsWith<IllegalStateException> { prepare(scheme, entry) }
            assertEquals(1, scheme.writes)
            assertEquals(metadata, scheme.metaProperties.toMap())
            assertFalse(scheme.directlyDefinedColors.containsKey(entry.key))
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, scheme.directlyDefinedColors[foreign])
        }
    }

    @Test
    fun `new prunable collateral after capture is rejected before the first write`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_LATE_COLLATERAL"))
            val foreign = ColorKey.createColorKey("AYU_BATCH_LATE_FOREIGN")
            val batch = prepare(scheme, entry)
            scheme.setColor(foreign, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            assertFailsWith<IllegalStateException> { batch.apply() }
            assertEquals(1, scheme.writes)
            assertFalse(scheme.directlyDefinedColors.containsKey(entry.key))
            assertFalse(scheme.metaProperties.containsKey(entry.metadataKey))
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertEquals(0, scheme.deletions)
        }
    }

    @Test
    fun `projected additions never mask a future target changed before apply`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val first = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_MASKED_FIRST"))
            val second = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_MASKED_SECOND"))
            val batch = EditorSchemeOverrides.checkpoints.prepareBatch(target(scheme), changes(first) + changes(second))
            scheme.setColor(second.key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            assertFalse(batch.apply())
            assertFalse(scheme.directlyDefinedColors.containsKey(first.key))
            assertEquals(1, scheme.writes)
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, scheme.directlyDefinedColors[second.key])
        }
    }

    @Test
    fun `ordinary native no-op is rechecked before creating absent deletion debt`() {
        SwingUtilities.invokeAndWait {
            val absent = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_NOOP_ABSENT"))
            val explicit = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_NOOP_EXPLICIT"))
            val scheme = CountingScheme(parentWithRed(explicit.key))
            val original = ColorUIResource(Color.GREEN)
            scheme.setColor(explicit.key, original)
            val batch =
                EditorSchemeOverrides.checkpoints.prepareBatch(
                    target(scheme),
                    linkedMapOf(
                        absent to CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, null),
                        explicit to CheckpointEntry(SchemeValue.ColorValue(Color.GREEN), null, null),
                    ),
                )
            assertFailsWith<IllegalStateException> { batch.apply() }
            assertSame(original, scheme.directlyDefinedColors[explicit.key])
            assertFalse(scheme.directlyDefinedColors.containsKey(absent.key))
            assertEquals(2, scheme.writes)
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertEquals(2, scheme.writes)
            assertEquals(0, scheme.deletions)
        }
    }

    @Test
    fun `interrupted ordinary writes create no absent deletion debt`() {
        SwingUtilities.invokeAndWait {
            val absent = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_INTERRUPTED_ABSENT"))
            val explicit = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_INTERRUPTED_EXPLICIT"))
            val scheme = CountingScheme(parentWithRed(explicit.key))
            scheme.setColor(explicit.key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            scheme.setColor(explicit.key, Color.RED)
            val batch =
                EditorSchemeOverrides.checkpoints.prepareBatch(
                    target(scheme),
                    linkedMapOf(
                        absent to CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, null),
                        explicit to CheckpointEntry(SchemeValue.ColorValue(Color.PINK), null, null),
                    ),
                )
            scheme.failAfterWrite = true
            assertFailsWith<IllegalStateException> { batch.apply() }
            assertFalse(scheme.directlyDefinedColors.containsKey(absent.key))
            scheme.failAfterWrite = false
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertSame(Color.RED, scheme.directlyDefinedColors[explicit.key])
            assertEquals(0, scheme.deletions)
        }
    }

    @Test
    fun `absent rollback retains two separately prepared stages and original evidence`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_ABSENT"))
            val batch = prepare(scheme, entry)
            val record = batch.records.single()
            assertEquals(0, scheme.writes)
            assertTrue(batch.apply())
            assertSame(Color.ORANGE, scheme.directlyDefinedColors[entry.key])
            assertEquals(AccentWriteState.OWNED, batch.states[entry])
            assertFailsWith<IllegalStateException> { batch.restore() }

            batch.prepareRestore()
            val seed = batch.seeds.single()
            val seedProgress = batch.seedProgress.single()
            assertEquals(SchemeSeedState.PENDING, seedProgress.state)
            assertEquals(1, scheme.writes)
            assertTrue(batch.deletions.isEmpty())
            assertFalse(batch.restore())
            assertEquals(SchemeSeedState.SETTLED, batch.seedProgress.single().state)
            assertEquals(SchemeSeedState.PENDING, seedProgress.state)
            assertEquals(2, scheme.writes)
            assertEquals(0, scheme.deletions)
            assertSame(AbstractColorsScheme.INHERITED_COLOR_MARKER, scheme.directlyDefinedColors[entry.key])
            assertFailsWith<IllegalStateException> { batch.restore() }

            batch.prepareRestore()
            assertEquals(setOf(entry), batch.deletions.keys)
            val deletionProgress = batch.deletionProgress
            assertEquals(SchemeDeletionState.PENDING, deletionProgress[entry])
            assertTrue(batch.restore())
            assertEquals(SchemeDeletionState.RESTORED, batch.deletionProgress[entry])
            assertEquals(SchemeDeletionState.PENDING, deletionProgress[entry])
            assertEquals(1, scheme.deletions)
            assertFalse(scheme.directlyDefinedColors.containsKey(entry.key))
            assertFalse(scheme.metaProperties.containsKey(entry.metadataKey))
            assertEquals(AccentWriteState.RESTORED, batch.states[entry])
            assertSame(record, batch.records.single())
            assertSame(seed, batch.seeds.single())
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertEquals(2, scheme.writes)
            assertEquals(1, scheme.deletions)
        }
    }

    @Test
    fun `failed seed and optimizer retain progress without replaying native mutations`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_RETRY"))
            val batch = prepare(scheme, entry)
            assertTrue(batch.apply())
            batch.prepareRestore()
            val seed = batch.seeds.single()
            scheme.failAfterWrite = true
            assertFailsWith<IllegalStateException> { batch.restore() }
            assertEquals(2, scheme.writes)
            assertEquals(0, scheme.deletions)
            scheme.failAfterWrite = false
            batch.prepareRestore()
            assertSame(seed, batch.seeds.single())
            assertFalse(batch.restore())
            assertEquals(2, scheme.writes)
            batch.prepareRestore()
            scheme.failAfterDeletion = true
            assertFailsWith<IllegalStateException> { batch.restore() }
            assertFalse(scheme.directlyDefinedColors.containsKey(entry.key))
            scheme.failAfterDeletion = false
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertEquals(1, scheme.deletions)
            assertEquals(2, scheme.writes)
            assertFalse(scheme.metaProperties.containsKey(entry.metadataKey))
        }
    }

    @Test
    fun `foreign equal replacement between acknowledgements is preserved`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_FOREIGN"))
            val batch = prepare(scheme, entry)
            assertTrue(batch.apply())
            batch.prepareRestore()
            assertFalse(batch.restore())
            val foreign = Color(255, 200, 0)
            scheme.setColor(entry.key, foreign)
            batch.prepareRestore()
            assertTrue(batch.deletions.isEmpty())
            assertTrue(batch.restore())
            assertSame(foreign, scheme.directlyDefinedColors[entry.key])
            assertEquals(0, scheme.deletions)
        }
    }

    @Test
    fun `newer blocked write prevents seed preparation and retries keep outer ownership`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_BLOCKED"))
            val batch = prepare(scheme, entry)
            assertTrue(batch.apply())
            assertFailsWith<IllegalStateException> { batch.prepareRestore(setOf(entry)) }
            assertTrue(batch.seeds.isEmpty())
            assertEquals(1, scheme.writes)
            assertEquals(AccentWriteState.OWNED, batch.states[entry])
            batch.prepareRestore()
            assertFailsWith<IllegalStateException> { batch.restore(setOf(entry)) }
            assertEquals(1, scheme.writes)
            batch.accept()
            assertEquals(AccentWriteState.RESTORED, batch.states[entry])
            assertSame(Color.ORANGE, scheme.directlyDefinedColors[entry.key])
            assertTrue(batch.restore())
            assertEquals(1, scheme.writes)
        }
    }

    @Test
    fun `blocked update leaves independent entries recoverable without losing pending ownership`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val first = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_BLOCKED_UPDATE_FIRST"))
            val second = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_BLOCKED_UPDATE_SECOND"))
            scheme.setColor(first.key, Color.RED)
            scheme.setColor(second.key, Color.RED)
            val batch = EditorSchemeOverrides.checkpoints.prepareBatch(target(scheme), changes(first) + changes(second))
            assertTrue(batch.apply())

            batch.prepareRestore(setOf(second))
            assertFailsWith<IllegalStateException> { batch.restore(setOf(second)) }
            assertSame(Color.RED, scheme.directlyDefinedColors[first.key])
            assertSame(Color.ORANGE, scheme.directlyDefinedColors[second.key])
            assertEquals(AccentWriteState.RESTORED, batch.states[first])
            assertEquals(AccentWriteState.OWNED, batch.states[second])
            assertEquals(5, scheme.writes)

            batch.prepareRestore()
            assertTrue(batch.restore())
            assertSame(Color.RED, scheme.directlyDefinedColors[second.key])
            assertEquals(6, scheme.writes)
            assertEquals(0, scheme.deletions)
        }
    }

    @Test
    fun `failed update restoration continues independent entries and retry does not replay writes`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val first = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_FAILED_UPDATE_FIRST"))
            val second = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_FAILED_UPDATE_SECOND"))
            scheme.setColor(first.key, Color.RED)
            scheme.setColor(second.key, Color.RED)
            val batch = EditorSchemeOverrides.checkpoints.prepareBatch(target(scheme), changes(first) + changes(second))
            assertTrue(batch.apply())
            batch.prepareRestore()

            scheme.failAfterWrite = true
            val failure = assertFailsWith<IllegalStateException> { batch.restore() }
            assertSame(Color.RED, scheme.directlyDefinedColors[first.key])
            assertSame(Color.RED, scheme.directlyDefinedColors[second.key])
            assertEquals(1, failure.suppressed.size)
            assertEquals(6, scheme.writes)

            scheme.failAfterWrite = false
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertTrue(batch.states.values.all { it == AccentWriteState.RESTORED })
            assertEquals(6, scheme.writes)
            assertEquals(0, scheme.deletions)
        }
    }

    @Test
    fun `pending absent restoration keeps mixed batch preparation blocked without changing ownership`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val absent = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_BLOCKED_MIXED_ABSENT"))
            val explicit = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_BLOCKED_MIXED_EXPLICIT"))
            scheme.setColor(explicit.key, Color.RED)
            val batch =
                EditorSchemeOverrides.checkpoints.prepareBatch(
                    target(scheme),
                    changes(absent) + changes(explicit),
                )
            assertTrue(batch.apply())
            val progress = batch.progress

            assertFailsWith<IllegalStateException> { batch.prepareRestore(setOf(explicit)) }
            for ((entry, components) in progress) {
                for ((component, before) in components) {
                    val after = batch.progress.getValue(entry).getValue(component)
                    assertEquals(before.phase, after.phase)
                    assertSame(before.expected, after.expected)
                    assertSame(before.destination, after.destination)
                }
            }
            assertTrue(batch.seeds.isEmpty())
            assertEquals(3, scheme.writes)

            batch.prepareRestore()
            assertFalse(batch.restore())
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertSame(Color.RED, scheme.directlyDefinedColors[explicit.key])
            assertFalse(scheme.directlyDefinedColors.containsKey(absent.key))
        }
    }

    @Test
    fun `cancellation retains its identity while independent update recovery continues`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val first = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_CANCELLED_UPDATE_FIRST"))
            val second = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_CANCELLED_UPDATE_SECOND"))
            scheme.setColor(first.key, Color.RED)
            scheme.setColor(second.key, Color.RED)
            val batch = EditorSchemeOverrides.checkpoints.prepareBatch(target(scheme), changes(first) + changes(second))
            assertTrue(batch.apply())
            batch.prepareRestore()
            val cancellation = ProcessCanceledException()
            scheme.writeFailure = cancellation

            assertSame(cancellation, assertFailsWith<ProcessCanceledException> { batch.restore() })
            assertSame(Color.RED, scheme.directlyDefinedColors[first.key])
            assertSame(Color.RED, scheme.directlyDefinedColors[second.key])
            assertTrue(cancellation.suppressed.isEmpty())
            assertEquals(6, scheme.writes)

            scheme.writeFailure = null
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertEquals(6, scheme.writes)
        }
    }

    @Test
    fun `unreadable seed result is resolved before restoring metadata or deleting`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_UNREADABLE"))
            val batch = prepare(scheme, entry)
            assertTrue(batch.apply())
            batch.prepareRestore()
            scheme.unreadableAfterWrite = true
            assertFailsWith<IllegalStateException> { batch.restore() }
            assertEquals(2, scheme.writes)
            assertEquals("batch metadata", scheme.metaProperties[entry.metadataKey])
            batch.prepareRestore()
            assertFailsWith<IllegalStateException> { batch.restore() }
            assertEquals(2, scheme.writes)
            assertEquals(0, scheme.deletions)
            scheme.unreadable = false
            scheme.unreadableAfterWrite = false
            batch.prepareRestore()
            assertFalse(batch.restore())
            assertEquals(2, scheme.writes)
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertFalse(scheme.directlyDefinedColors.containsKey(entry.key))
            assertFalse(scheme.metaProperties.containsKey(entry.metadataKey))
        }
    }

    @Test
    fun `absent cells are deleted before restoring an explicit equal parent value`() {
        SwingUtilities.invokeAndWait {
            val absent = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_MIXED_ABSENT"))
            val explicit = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_MIXED_EXPLICIT"))
            val parent = DefaultColorsScheme()
            parent.readExternal(
                JDOMUtil.load(
                    """
                    <scheme name="Batch parent" version="142" default_scheme="true">
                      <colors><option name="${explicit.key.externalName}" value="ff0000" /></colors>
                    </scheme>
                    """.trimIndent(),
                ),
            )
            val scheme = CountingScheme(parent)
            scheme.setColor(explicit.key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            scheme.setColor(explicit.key, Color.RED)
            val batch =
                EditorSchemeOverrides.checkpoints.prepareBatch(
                    target(scheme),
                    linkedMapOf(
                        absent to CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, null),
                        explicit to CheckpointEntry(SchemeValue.ColorValue(Color.PINK), null, null),
                    ),
                )
            assertTrue(batch.apply())
            batch.prepareRestore()
            assertFalse(batch.restore())
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertEquals(1, scheme.deletions)
            assertFalse(scheme.directlyDefinedColors.containsKey(absent.key))
            assertSame(Color.RED, scheme.directlyDefinedColors[explicit.key])
        }
    }

    @Test
    fun `unrelated prunable value blocks recovery without enrolling a legacy retry`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_COLLATERAL"))
            val foreign = ColorKey.createColorKey("AYU_BATCH_PRUNABLE_FOREIGN")
            val batch = prepare(scheme, entry)
            assertTrue(batch.apply())
            scheme.setColor(foreign, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            assertFailsWith<IllegalStateException> { batch.prepareRestore() }
            assertTrue(batch.seeds.isEmpty())
            assertEquals(AccentWriteState.OWNED, batch.states[entry])
            EditorSchemeOverrides.checkpoints.prepare()
            assertSame(Color.ORANGE, scheme.directlyDefinedColors[entry.key])
            assertEquals(2, scheme.writes)
            assertEquals(0, scheme.deletions)
            scheme.setColor(foreign, Color.GREEN)
            batch.prepareRestore()
            assertFalse(batch.restore())
            batch.prepareRestore()
            assertTrue(batch.restore())
            assertSame(Color.GREEN, scheme.directlyDefinedColors[foreign])
        }
    }

    @Test
    fun `raw active and named replacements reject apply even with identical names`() {
        SwingUtilities.invokeAndWait {
            for (source in listOf(SchemeSource.Active, SchemeSource.Named("lookup"))) {
                val scheme = CountingScheme().apply { name = "External custom scheme" }
                var selected: EditorColorsSchemeImpl = scheme
                val captured = assertNotNull(SchemeTarget.capture(source) { selected })
                val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_REPLACED"))
                val batch = EditorSchemeOverrides.checkpoints.prepareBatch(captured, changes(entry))
                selected = EditorColorsSchemeImpl(DefaultColorsScheme()).apply { name = scheme.name }
                assertFalse(batch.apply())
                assertEquals(0, scheme.writes)
                assertFalse(selected.directlyDefinedColors.containsKey(entry.key))
                batch.prepareRestore()
                assertTrue(batch.restore())
            }
        }
    }

    @Test
    fun `retained rollback survives source replacement and rename without touching replacement`() {
        SwingUtilities.invokeAndWait {
            for (source in listOf(SchemeSource.Active, SchemeSource.Named("lookup"))) {
                val scheme = CountingScheme().apply { name = "Original scheme" }
                var selected: EditorColorsSchemeImpl = scheme
                val captured = assertNotNull(SchemeTarget.capture(source) { selected })
                val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_RETAINED"))
                val batch = EditorSchemeOverrides.checkpoints.prepareBatch(captured, changes(entry))
                assertTrue(batch.apply())
                selected = EditorColorsSchemeImpl(DefaultColorsScheme()).apply { name = scheme.name }
                selected.setColor(entry.key, Color.GREEN)
                scheme.name = "Renamed by user"
                batch.prepareRestore()
                assertFalse(batch.restore())
                batch.prepareRestore()
                assertTrue(batch.restore())
                assertEquals("Renamed by user", scheme.name)
                assertFalse(scheme.directlyDefinedColors.containsKey(entry.key))
                assertSame(Color.GREEN, selected.directlyDefinedColors[entry.key])
            }
        }
    }

    @Test
    fun `name or parent change after capture rejects apply`() {
        SwingUtilities.invokeAndWait {
            for (rename in listOf(false, true)) {
                val scheme = CountingScheme().apply { name = "Original" }
                val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_CHANGED_CONTEXT"))
                val batch = prepare(scheme, entry)
                if (rename) scheme.name = "Renamed" else scheme.setParent(DefaultColorsScheme())
                assertFalse(batch.apply())
                assertEquals(0, scheme.writes)
                batch.prepareRestore()
                assertTrue(batch.restore())
                assertEquals(0, scheme.deletions)
            }
        }
    }

    @Test
    fun `named scheme and active editable copy keep independent selection authority`() {
        SwingUtilities.invokeAndWait {
            val canonical = CountingScheme().apply { name = "Named canonical" }
            val editable = CountingScheme().apply { name = "Active editable" }
            val source = SchemeSource.Named("Canonical lookup")
            val resolver = { selection: SchemeSource -> if (selection is SchemeSource.Named) canonical else editable }
            val named = assertNotNull(SchemeTarget.capture(source, resolver))
            val active = assertNotNull(SchemeTarget.capture(SchemeSource.Active, resolver))
            assertEquals(source, named.source)
            assertEquals("Named canonical", named.name)
            assertSame(canonical, named.scheme)
            assertSame(editable, active.scheme)
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_INDEPENDENT_TARGET"))
            val namedBatch = EditorSchemeOverrides.checkpoints.prepareBatch(named, changes(entry))
            val activeBatch = EditorSchemeOverrides.checkpoints.prepareBatch(active, changes(entry))
            assertTrue(namedBatch.apply())
            assertTrue(activeBatch.apply())
            namedBatch.prepareRestore()
            assertFalse(namedBatch.restore())
            namedBatch.prepareRestore()
            assertTrue(namedBatch.restore())
            assertFalse(canonical.directlyDefinedColors.containsKey(entry.key))
            assertSame(Color.ORANGE, editable.directlyDefinedColors[entry.key])
            activeBatch.accept()
        }
    }

    @Test
    fun `component progress preserves foreign values separately from completed metadata recovery`() {
        SwingUtilities.invokeAndWait {
            val scheme = CountingScheme()
            val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_COMPONENT_PROGRESS"))
            val batch = prepare(scheme, entry)
            val original = batch.progress.getValue(entry)
            assertTrue(original.values.all { it.phase == SchemeWritePhase.PREPARED })
            assertTrue(batch.apply())
            assertTrue(
                batch.progress
                    .getValue(entry)
                    .values
                    .all { it.phase == SchemeWritePhase.APPLIED },
            )
            scheme.setColor(entry.key, Color.PINK)
            batch.prepareRestore()
            assertTrue(batch.restore())
            val completed = batch.progress.getValue(entry)
            assertEquals(SchemeWritePhase.FOREIGN, completed.getValue(SchemeComponent.VALUE).phase)
            assertEquals(SchemeWritePhase.RESTORED, completed.getValue(SchemeComponent.OWNERSHIP).phase)
            assertEquals(SchemeWritePhase.RESTORED, completed.getValue(SchemeComponent.METADATA).phase)
            assertTrue(original.values.all { it.phase == SchemeWritePhase.PREPARED })
            assertSame(Color.PINK, scheme.directlyDefinedColors[entry.key])
        }
    }

    @Test
    fun `unused and invalidated batches expose terminal component evidence without native writes`() {
        SwingUtilities.invokeAndWait {
            for (applied in listOf(false, true)) {
                val scheme = CountingScheme()
                val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_BATCH_TERMINAL_PROGRESS"))
                val batch = prepare(scheme, entry)
                if (applied) {
                    assertTrue(batch.apply())
                    scheme.setParent(DefaultColorsScheme())
                }
                batch.prepareRestore()
                assertTrue(batch.restore())
                val expected = if (applied) SchemeWritePhase.FOREIGN else SchemeWritePhase.RESTORED
                assertTrue(
                    batch.progress
                        .getValue(entry)
                        .values
                        .all { it.phase == expected },
                )
                assertEquals(if (applied) 1 else 0, scheme.writes)
                assertEquals(0, scheme.deletions)
            }
        }
    }

    private fun prepare(
        scheme: CountingScheme,
        entry: SchemeEntry.ColorEntry,
    ) = EditorSchemeOverrides.checkpoints.prepareBatch(target(scheme), changes(entry))

    private fun changes(entry: SchemeEntry.ColorEntry): Map<SchemeEntry, CheckpointEntry> =
        mapOf(entry to CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, "batch metadata"))

    private fun target(scheme: CountingScheme) =
        assertNotNull(SchemeTarget.capture(SchemeSource.Named("fixture")) { scheme })

    private fun parentWithRed(key: ColorKey): DefaultColorsScheme =
        DefaultColorsScheme().apply {
            readExternal(
                JDOMUtil.load(
                    """
                    <scheme name="Batch parent" version="142" default_scheme="true">
                      <colors><option name="${key.externalName}" value="ff0000" /></colors>
                    </scheme>
                    """.trimIndent(),
                ),
            )
        }

    private class CountingScheme(
        parent: AbstractColorsScheme = DefaultColorsScheme(),
    ) : EditorColorsSchemeImpl(parent) {
        init {
            name = "Batch fixture"
        }

        var writes = 0
        var deletions = 0
        var failAfterWrite = false
        var failAfterDeletion = false
        var unreadableAfterWrite = false
        var unreadable = false
        var writeFailure: RuntimeException? = null

        override fun getDirectlyDefinedColors(): Map<ColorKey, Color> {
            check(!unreadable) { "Native observation is unavailable" }
            return super.getDirectlyDefinedColors()
        }

        override fun setColor(
            key: ColorKey,
            color: Color?,
        ) {
            writes++
            super.setColor(key, color)
            if (unreadableAfterWrite) unreadable = true
            check(!failAfterWrite) { "Seed interrupted after mutation" }
            writeFailure?.let { throw it }
        }

        override fun optimizeAttributeMap() {
            deletions++
            super.optimizeAttributeMap()
            check(!failAfterDeletion) { "Deletion interrupted after mutation" }
        }
    }
}
