package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.SchemeReceiptIdentity
import dev.ayuislands.studio.session.StudioNativeEvidence
import dev.ayuislands.studio.session.StudioNativeTokens
import dev.ayuislands.studio.session.StudioRestoreContinuation
import dev.ayuislands.studio.session.StudioSchemeCellCodec
import dev.ayuislands.studio.session.StudioSchemeWrite
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.CheckpointEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeStoredCell
import dev.ayuislands.theme.SchemeTarget
import dev.ayuislands.theme.SchemeWriteBatch
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.time.Instant
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSchemeWriteTest {
    @get:Rule val applicationRule = ApplicationRule()

    private val process = StudioProcess(41, Instant.EPOCH)
    private val identity = SchemeReceiptIdentity(EffectOwner.SYNTAX, "shared-scheme-id", "attempt", process)

    @Test
    fun `native capture retains original process and raw metadata without applying any effect`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture("AYU_STUDIO_NATIVE_CAPTURE", metadata = "  original\nЖ\t")
            val metadata = fixture.scheme.metaProperties.toMap()
            val saveState = fixture.scheme.schemeState

            val write = capture(fixture)
            val originals = write.receipts

            assertEquals(3, originals.size)
            assertTrue(originals.all { it.status == RestoreStatus.PENDING })
            originals.forEach { assertEquals(process, StudioNativeEvidence.readProcess(it.before)) }
            assertSame(Color.BLUE, fixture.scheme.directlyDefinedColors[fixture.entry.key])
            assertEquals(metadata, fixture.scheme.metaProperties.toMap())
            assertEquals(saveState, fixture.scheme.schemeState)

            write.apply()
            assertSame(Color.ORANGE, fixture.scheme.directlyDefinedColors[fixture.entry.key])
            assertTrue(write.receipts.all { it.status == RestoreStatus.OWNED })
            assertPayloads(originals, write.receipts)
            write.prepareRestore(emptyList())
            assertSame(Color.ORANGE, fixture.scheme.directlyDefinedColors[fixture.entry.key])
            assertEquals(StudioRestoreContinuation.NONE, write.restore(emptyList()))
            assertSame(Color.BLUE, fixture.scheme.directlyDefinedColors[fixture.entry.key])
            assertEquals(metadata, fixture.scheme.metaProperties.toMap())
            assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
            assertPayloads(originals, write.receipts)
        }
    }

    @Test
    fun `a different target or an already applied no-op batch cannot acquire prewrite receipts`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture("AYU_STUDIO_NATIVE_BINDING")
            val other =
                EditorColorsSchemeImpl(fixture.scheme.parentScheme).apply {
                    name = fixture.scheme.name
                    setColor(fixture.entry.key, Color.BLUE)
                }

            assertFailsWith<IllegalStateException> {
                StudioSchemeWrite.capture(identity, target(other), fixture.batch, StudioNativeTokens("wrong-target"))
            }
            assertSame(Color.BLUE, fixture.scheme.directlyDefinedColors[fixture.entry.key])
            assertSame(Color.BLUE, other.directlyDefinedColors[fixture.entry.key])

            val noOp =
                EditorSchemeOverrides.checkpoints.prepareBatch(
                    fixture.target,
                    mapOf(fixture.entry to CheckpointEntry(SchemeValue.ColorValue(Color.BLUE), null, null)),
                )
            assertTrue(noOp.apply())
            assertFailsWith<IllegalStateException> {
                StudioSchemeWrite.capture(identity, fixture.target, noOp, StudioNativeTokens("late-capture"))
            }
            assertSame(Color.BLUE, fixture.scheme.directlyDefinedColors[fixture.entry.key])
            noOp.prepareRestore()
            assertTrue(noOp.restore())
        }
    }

    @Test
    fun `changed selection or captured base refuses apply without recapturing originals`() {
        SwingUtilities.invokeAndWait {
            for (changeSelection in listOf(true, false)) {
                val key = ColorKey.createColorKey("AYU_STUDIO_NATIVE_DEPENDENCY_$changeSelection")
                val parent = DefaultColorsScheme()
                setParentColor(parent, key, "ff0000")
                val scheme = nativeScheme(parent).apply { setColor(key, Color.BLUE) }
                var selected: EditorColorsScheme? = scheme
                val target = assertNotNull(SchemeTarget.capture(SchemeSource.Named("fixture")) { selected })
                val entry = SchemeEntry.ColorEntry(key)
                val batch = EditorSchemeOverrides.checkpoints.prepareBatch(target, changes(entry))
                val write = StudioSchemeWrite.capture(identity, target, batch, StudioNativeTokens("dependency"))
                val originals = write.receipts
                val metadata = scheme.metaProperties.toMap()
                val saveState = scheme.schemeState
                if (changeSelection) selected = nativeScheme(parent) else setParentColor(parent, key, "00ff00")

                assertFailsWith<IllegalStateException> { write.apply() }

                assertEquals(originals, write.receipts)
                assertSame(Color.BLUE, scheme.directlyDefinedColors[key])
                assertEquals(metadata, scheme.metaProperties.toMap())
                assertEquals(saveState, scheme.schemeState)
                write.prepareRestore(emptyList())
                assertEquals(StudioRestoreContinuation.NONE, write.restore(emptyList()))
                assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
            }
        }
    }

    @Test
    fun `a native guard exception after value mutation retains component progress and exact failure`() {
        SwingUtilities.invokeAndWait {
            val key = ColorKey.createColorKey("AYU_STUDIO_NATIVE_INTERRUPTED")
            val scheme = nativeScheme().apply { setColor(key, Color.BLUE) }
            val failure = ProcessCanceledException()
            var interrupt = false
            val target =
                assertNotNull(
                    SchemeTarget.capture(SchemeSource.Named("fixture")) {
                        if (interrupt && scheme.directlyDefinedColors[key] === Color.ORANGE) {
                            interrupt = false
                            throw failure
                        }
                        scheme
                    },
                )
            val batch = EditorSchemeOverrides.checkpoints.prepareBatch(target, changes(SchemeEntry.ColorEntry(key)))
            val write = StudioSchemeWrite.capture(identity, target, batch, StudioNativeTokens("interrupted"))
            val originals = write.receipts
            interrupt = true

            assertSame(failure, assertFailsWith<ProcessCanceledException> { write.apply() })

            assertSame(Color.ORANGE, scheme.directlyDefinedColors[key])
            assertEquals(
                listOf(RestoreStatus.OWNED, RestoreStatus.PENDING, RestoreStatus.PENDING),
                write.receipts.map { it.status },
            )
            assertPayloads(originals, write.receipts)
            write.prepareRestore(emptyList())
            assertEquals(StudioRestoreContinuation.NONE, write.restore(emptyList()))
            assertSame(Color.BLUE, scheme.directlyDefinedColors[key])
            assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
        }
    }

    @Test
    fun `absent rollback exports seed and deletion evidence before their separate native effects`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture("AYU_STUDIO_NATIVE_ABSENT", before = null)
            val write = capture(fixture)
            val originals = write.receipts
            write.apply()
            assertFailsWith<IllegalStateException> { write.restore(emptyList()) }

            write.prepareRestore(emptyList())
            val seeded = write.receipts
            val seed = seeded.single { role(it) == "seed" }
            assertEquals(RestoreStatus.PENDING, seed.status)
            assertEquals(SchemeStoredCell.Color(Color.ORANGE.rgb), persisted(seed.before))
            assertEquals(SchemeStoredCell.Omitted, persisted(seed.lastWritten))
            assertSame(Color.ORANGE, fixture.scheme.directlyDefinedColors[fixture.entry.key])
            assertEquals(StudioRestoreContinuation.REQUIRED, write.restore(emptyList()))
            assertFalse(StudioNativeEvidence.isTerminal(write.receipts.single { it.id == seed.id }.status))
            assertSame(
                AbstractColorsScheme.INHERITED_COLOR_MARKER,
                fixture.scheme.directlyDefinedColors[fixture.entry.key],
            )

            write.prepareRestore(emptyList())
            val deletion = write.receipts.single { role(it) == "deletion" }
            assertEquals(RestoreStatus.PENDING, deletion.status)
            assertEquals(SchemeStoredCell.Omitted, persisted(deletion.before))
            assertEquals(SchemeStoredCell.Omitted, persisted(deletion.lastWritten))
            assertPayloads(seeded, write.receipts)
            assertSame(
                AbstractColorsScheme.INHERITED_COLOR_MARKER,
                fixture.scheme.directlyDefinedColors[fixture.entry.key],
            )
            assertEquals(StudioRestoreContinuation.NONE, write.restore(emptyList()))
            assertFalse(fixture.scheme.directlyDefinedColors.containsKey(fixture.entry.key))
            assertFalse(fixture.scheme.metaProperties.containsKey(fixture.entry.metadataKey))
            assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
            assertPayloads(originals, write.receipts)
            val settled = write.receipts
            write.prepareRestore(emptyList())
            assertEquals(StudioRestoreContinuation.NONE, write.restore(emptyList()))
            assertEquals(settled, write.receipts)
        }
    }

    @Test
    fun `cross owner blockers defer the same entry while independent entries can restore`() {
        SwingUtilities.invokeAndWait {
            val first = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_STUDIO_NATIVE_UNBLOCKED"))
            val second = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_STUDIO_NATIVE_BLOCKED"))
            val scheme =
                nativeScheme().apply {
                    setColor(first.key, Color.BLUE)
                    setColor(second.key, Color.GREEN)
                }
            val target = target(scheme)
            val batch = EditorSchemeOverrides.checkpoints.prepareBatch(target, changes(first) + changes(second))
            val write = StudioSchemeWrite.capture(identity, target, batch, StudioNativeTokens("blocked"))
            write.apply()
            val blocker =
                write.receipts.single { it.identity.field == "editor/color/${second.key.externalName}/METADATA" }.let {
                    it.copy(id = "newer", owner = EffectOwner.ACCENT, identity = it.identity.copy(ownerToken = "newer"))
                }

            write.prepareRestore(listOf(blocker))
            assertFailsWith<IllegalStateException> { write.restore(listOf(blocker)) }

            assertSame(Color.BLUE, scheme.directlyDefinedColors[first.key])
            assertSame(Color.ORANGE, scheme.directlyDefinedColors[second.key])
            assertTrue(write.receipts.any { it.status == RestoreStatus.OWNED })
            val irrelevant = blocker.copy(identity = blocker.identity.copy(resource = "another-scheme"))
            val terminal = blocker.copy(status = RestoreStatus.FOREIGN)
            write.prepareRestore(listOf(irrelevant, terminal))
            assertEquals(StudioRestoreContinuation.NONE, write.restore(listOf(irrelevant, terminal)))
            assertSame(Color.GREEN, scheme.directlyDefinedColors[second.key])
            assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
        }
    }

    @Test
    fun `equal foreign replacement survives retained cleanup after the native scheme is renamed`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture("AYU_STUDIO_NATIVE_FOREIGN")
            val write = capture(fixture)
            val originals = write.receipts
            write.apply()
            val foreign = Color(Color.ORANGE.rgb, true)
            fixture.scheme.setColor(fixture.entry.key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            fixture.scheme.setColor(fixture.entry.key, foreign)
            fixture.scheme.name = "Renamed native scheme"

            write.prepareRestore(emptyList())
            assertEquals(StudioRestoreContinuation.NONE, write.restore(emptyList()))

            assertSame(foreign, fixture.scheme.directlyDefinedColors[fixture.entry.key])
            val originalStatuses = write.receipts.filter { receipt -> originals.any { it.id == receipt.id } }
            assertEquals(
                listOf(RestoreStatus.FOREIGN, RestoreStatus.RESTORED, RestoreStatus.RESTORED),
                originalStatuses.map { it.status },
            )
            assertPayloads(originals, write.receipts)
        }
    }

    @Test
    fun `only successful application permits outer acceptance and accepted native fields cannot roll back`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture("AYU_STUDIO_NATIVE_ACCEPT")
            val write = capture(fixture)
            assertFailsWith<IllegalStateException> { write.accept() }
            write.apply()
            val applied = write.receipts

            write.accept()
            write.accept()

            assertSame(Color.ORANGE, fixture.scheme.directlyDefinedColors[fixture.entry.key])
            assertEquals(applied, write.receipts)
            assertFailsWith<IllegalStateException> { write.apply() }
            assertFailsWith<IllegalStateException> { write.prepareRestore(emptyList()) }
            assertFailsWith<IllegalStateException> { write.restore(emptyList()) }
        }
    }

    private fun fixture(
        name: String,
        before: Color? = Color.BLUE,
        metadata: String? = null,
    ): WriteFixture {
        val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey(name))
        val scheme =
            nativeScheme().apply {
                if (before != null) setColor(entry.key, before)
                if (metadata != null) metaProperties[entry.metadataKey] = metadata
            }
        val target = target(scheme)
        val batch = EditorSchemeOverrides.checkpoints.prepareBatch(target, changes(entry))
        return WriteFixture(scheme, target, batch, entry)
    }

    private fun capture(fixture: WriteFixture): StudioSchemeWrite =
        StudioSchemeWrite.capture(identity, fixture.target, fixture.batch, StudioNativeTokens("native-write"))

    private fun nativeScheme(parent: DefaultColorsScheme = DefaultColorsScheme()): EditorColorsSchemeImpl =
        EditorColorsSchemeImpl(parent).apply { name = "Studio native fixture" }

    private fun target(scheme: EditorColorsSchemeImpl): SchemeTarget =
        assertNotNull(SchemeTarget.capture(SchemeSource.Named("fixture")) { scheme })

    private fun changes(entry: SchemeEntry.ColorEntry): Map<SchemeEntry, CheckpointEntry> =
        mapOf(entry to CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, "written metadata"))

    private fun setParentColor(
        parent: DefaultColorsScheme,
        key: ColorKey,
        value: String,
    ) {
        parent.readExternal(
            JDOMUtil.load(
                """
                <scheme name="Studio parent" version="142" default_scheme="true">
                  <colors><option name="${key.externalName}" value="$value" /></colors>
                </scheme>
                """.trimIndent(),
            ),
        )
    }

    private fun assertPayloads(
        retained: List<PortableReceipt>,
        current: List<PortableReceipt>,
    ) {
        val byId = current.associateBy { it.id }
        retained.forEach { original ->
            assertEquals(original, byId.getValue(original.id).copy(status = original.status))
        }
    }

    private fun payload(value: NativeValue): Map<String, RawValue> {
        val envelope = StudioNativeEvidence.fields(assertIs<NativeValue.Present>(value).value)
        return StudioNativeEvidence.fields(envelope.getValue("value"))
    }

    private fun role(receipt: PortableReceipt): String =
        assertIs<RawValue.Text>(payload(receipt.before).getValue("role")).value

    private fun persisted(value: NativeValue): SchemeStoredCell =
        StudioSchemeCellCodec.decode(payload(value).getValue("persisted"))

    private class WriteFixture(
        val scheme: EditorColorsSchemeImpl,
        val target: SchemeTarget,
        val batch: SchemeWriteBatch,
        val entry: SchemeEntry.ColorEntry,
    )
}
