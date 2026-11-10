package dev.ayuislands.studio

import dev.ayuislands.studio.fields.StudioRuntimeFields
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioRuntimeState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioRuntimeOutputTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `font ownership edits preserve unknown entries order unrelated facts and opaque bytes`() {
        val initial = baseline()
        val changes =
            listOf(
                entry("changed", text("before"), text("after")),
                entry("removed", text("remove"), FieldValue.Absent),
                entry("new", FieldValue.Absent, text("added")),
            )
        val updated = StudioRuntimeFields.merge(initial, changes)
        assertEquals(
            listOf(
                RawValue.Entry("unknown", RawValue.Text("<future opaque='  '/>")),
                RawValue.Entry("changed", RawValue.Text("after")),
                RawValue.Entry("new", RawValue.Text("added")),
            ),
            (field(updated, "fontOwnershipSnapshots").value as RawValue.OrderedMap).entries,
        )
        assertEquals(initial.legacy.extensions, updated.legacy.extensions)
        assertEquals(initial.legacy.records.map { it.key }, updated.legacy.records.map { it.key })
        assertEquals(
            initial.legacy.records.filterNot { it.key.name == "fontOwnershipSnapshots" },
            updated.legacy.records.filterNot { it.key.name == "fontOwnershipSnapshots" },
        )
        assertEquals(
            initial,
            StudioRuntimeFields.merge(initial, listOf(entry("changed", text("before"), text("before")))),
        )
    }

    @Test
    fun `stale overlapping mistyped or preference outputs cannot become runtime authority`() {
        val initial = baseline()
        val version = field(initial, "fontOwnershipVersion")
        val valid =
            RuntimeFieldChange(
                FieldAddress(version.key),
                FieldValue.Present(version.value),
                FieldValue.Present(RawValue.Integer(2)),
            )
        val map = field(initial, "fontOwnershipSnapshots")
        val invalid =
            listOf(
                listOf(valid.copy(before = text("stale"))),
                listOf(valid.copy(after = text("wrong type"))),
                listOf(valid.copy(address = FieldAddress(key("glowEnabled")))),
                listOf(valid.copy(address = FieldAddress(key("unknownRuntime")))),
                listOf(valid, valid),
                listOf(
                    entry("changed", text("before"), text("after")),
                    entry("changed", text("before"), text("other")),
                ),
                listOf(
                    RuntimeFieldChange(
                        FieldAddress(map.key),
                        FieldValue.Present(map.value),
                        FieldValue.Present(map.value),
                    ),
                ),
                listOf(valid.copy(address = FieldAddress(map.key, FieldPart.MapOrder))),
            )
        for (changes in invalid) {
            assertFailsWith<IllegalArgumentException> { StudioRuntimeFields.merge(initial, changes) }
        }
    }

    @Test
    fun `owner output reaches the lease and durable state while rollback keeps its old runtime`(): Unit =
        runBlocking {
            for (fail in listOf(false, true)) {
                StudioApplyFixture(directory.resolve(fail.toString())).use { fixture ->
                    fixture.effect(EffectOwner.FONT).runtimeChanges =
                        StudioList(listOf(entry("new", FieldValue.Absent, text("owned"))))
                    fixture.effect(EffectOwner.VCS).failAfter = fail
                    val result = fixture.coordinator.submit(fixture.allOwnersRequest())
                    if (fail) {
                        assertIs<AcceptanceResult.NotAccepted>(result)
                        assertEquals(fixture.initial.runtime, fixture.document().runtime)
                    } else {
                        assertIs<AcceptanceResult.Accepted>(result)
                        assertEquals(
                            listOf(RawValue.Entry("new", RawValue.Text("owned"))),
                            (
                                field(
                                    fixture.document().runtime,
                                    "fontOwnershipSnapshots",
                                ).value as RawValue.OrderedMap
                            ).entries,
                        )
                        assertEquals(fixture.document().runtime, fixture.runtimeSeen)
                    }
                }
            }
        }

    @Test
    fun `invalid native output causes outer rollback instead of partial acceptance`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.effect(EffectOwner.GLOW).runtimeChanges =
                    StudioList(
                        listOf(RuntimeFieldChange(FieldAddress(key("glowEnabled")), text("invalid"), text("invalid"))),
                    )
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(fixture.request()))
                assertEquals(fixture.initial.runtime, fixture.document().runtime)
                assertTrue(fixture.effects.all { it.value == 0 })
            }
        }

    private fun baseline(): StudioRuntimeState {
        val runtime = sessionDocument().runtime
        val map =
            RawValue.OrderedMap(
                StudioList(
                    listOf(
                        RawValue.Entry("unknown", RawValue.Text("<future opaque='  '/>")),
                        RawValue.Entry("changed", RawValue.Text("before")),
                        RawValue.Entry("removed", RawValue.Text("remove")),
                    ),
                ),
            )
        return runtime.copy(
            legacy =
                runtime.legacy.copy(
                    records =
                        StudioList(
                            runtime.legacy.records.map {
                                if (it.key.name == "fontOwnershipSnapshots") {
                                    it.copy(
                                        value = map,
                                        origin =
                                            FieldOrigin(
                                                SourcePresence.VALUE,
                                                StudioBytes("raw source".toByteArray()),
                                            ),
                                    )
                                } else {
                                    it
                                }
                            },
                        ),
                    extensions = StudioList(listOf(OpaqueRecord("future", StudioBytes(byteArrayOf(0, 1, 2, -1))))),
                ),
        )
    }

    private fun entry(
        name: String,
        before: FieldValue,
        after: FieldValue,
    ): RuntimeFieldChange =
        RuntimeFieldChange(FieldAddress(key("fontOwnershipSnapshots"), FieldPart.MapEntry(name)), before, after)

    private fun text(value: String): FieldValue = FieldValue.Present(RawValue.Text(value))

    private fun key(name: String): LegacyFieldKey = LegacyFieldKey(LegacyStore.SETTINGS, name)

    private fun field(
        runtime: StudioRuntimeState,
        name: String,
    ): StudioField =
        runtime.legacy.records.single {
            it.key.name ==
                name
        }
}
