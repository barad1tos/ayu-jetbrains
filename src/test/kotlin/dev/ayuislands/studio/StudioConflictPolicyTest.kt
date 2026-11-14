package dev.ayuislands.studio

import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldResolution
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioConflictPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioConflictPolicyTest {
    private val key = LegacyFieldKey(LegacyStore.SYNTAX, "cellIntensities")
    private val origin = FieldOrigin(SourcePresence.VALUE, StudioBytes("original".encodeToByteArray()))

    @Test
    fun `untouched fields and opaque extensions remain latest while an explicit field changes alone`() {
        val base = fields(RawValue.Integer(10))
        val mine = fields(RawValue.Integer(20))
        val latest = base.copy(extensions = StudioList(listOf(OpaqueRecord("future", StudioBytes(byteArrayOf(0, -1))))))
        assertEquals(latest, StudioConflictPolicy.merge(base, mine, latest, StudioList(emptyList())).fields)
        val merged = StudioConflictPolicy.merge(base, mine, latest, StudioList(listOf(FieldAddress(key))))
        assertEquals(
            mine.records.single().value,
            merged.fields.records
                .single()
                .value,
        )
        assertEquals(latest.extensions, merged.fields.extensions)
        assertTrue(merged.conflicts.isEmpty())
    }

    @Test
    fun `equal values retain provenance and conflicts require exact authorization`() {
        val base = fields(RawValue.Integer(10))
        val mine = fields(RawValue.Integer(20))
        val equal = mine.copy(records = StudioList(listOf(mine.records.single().copy(origin = origin))))
        val touch = StudioList(listOf(FieldAddress(key)))
        assertEquals(equal, StudioConflictPolicy.merge(base, mine, equal, touch).fields)
        val latest = fields(RawValue.Integer(30))
        val conflicted = StudioConflictPolicy.merge(base, mine, latest, touch)
        val conflict = conflicted.conflicts.single()
        assertEquals(latest, conflicted.fields)
        val choices = StudioList(listOf(FieldResolution(conflict, ConflictChoice.USE_MINE)))
        assertEquals(mine, StudioConflictPolicy.merge(base, mine, latest, touch, choices).fields)
        assertEquals(
            1,
            StudioConflictPolicy.merge(base, mine, fields(RawValue.Integer(40)), touch, choices).conflicts.size,
        )
        val keep = StudioList(listOf(FieldResolution(conflict, ConflictChoice.KEEP_LATEST)))
        val kept = StudioConflictPolicy.merge(base, mine, latest, touch, keep)
        assertEquals(latest, kept.fields)
        assertTrue(kept.conflicts.isEmpty())
    }

    @Test
    fun `disjoint map edits retain unknown entries and latest order then append local additions`() {
        val base = fields(map("unknown" to RawValue.Bytes(StudioBytes(byteArrayOf(-1))), "a" to RawValue.Text("old")))
        val mine =
            fields(
                map(
                    "unknown" to RawValue.Bytes(StudioBytes(byteArrayOf(-1))),
                    "a" to RawValue.Text("mine"),
                    "b" to RawValue.Null,
                ),
            )
        val latest =
            fields(
                map(
                    "unknown" to RawValue.Bytes(StudioBytes(byteArrayOf(-1))),
                    "a" to RawValue.Text("old"),
                    "c" to RawValue.Text("new"),
                ),
            )
        val touched =
            StudioList(listOf(FieldAddress(key, FieldPart.MapEntry("a")), FieldAddress(key, FieldPart.MapEntry("b"))))
        val merged = StudioConflictPolicy.merge(base, mine, latest, touched)
        assertTrue(merged.conflicts.isEmpty())
        val entries =
            (
                merged.fields.records
                    .single()
                    .value as RawValue.OrderedMap
            ).entries
        assertEquals(listOf("unknown", "a", "c", "b"), entries.map { it.key })
        assertEquals(RawValue.Text("mine"), entries[1].value)
        assertEquals(RawValue.Null, entries.last().value)
        assertEquals((base.records.single().value as RawValue.OrderedMap).entries.first(), entries.first())
    }

    @Test
    fun `remove is distinct from null and concurrent map key edits require review`() {
        val base = fields(map("a" to RawValue.Text("old")))
        val mine = fields(map())
        val latest = fields(map("a" to RawValue.Null))
        val touches = StudioList(listOf(FieldAddress(key, FieldPart.MapEntry("a"))))
        val result = StudioConflictPolicy.merge(base, mine, latest, touches)
        val choice = FieldResolution(result.conflicts.single(), ConflictChoice.USE_MINE)
        val resolved = StudioConflictPolicy.merge(base, mine, latest, touches, StudioList(listOf(choice)))
        assertEquals(mine, resolved.fields)
        assertTrue(resolved.conflicts.isEmpty())
    }

    @Test
    fun `concurrent ordering changes are explicit and an old order choice never authorizes new order`() {
        val base = fields(map("a" to RawValue.Text("1"), "b" to RawValue.Text("2")))
        val mine = fields(map("a" to RawValue.Text("mine"), "b" to RawValue.Text("2")))
        val latest = fields(map("b" to RawValue.Text("2"), "a" to RawValue.Text("1")))
        val touches = StudioList(listOf(FieldAddress(key, FieldPart.MapEntry("a"))))
        val result = StudioConflictPolicy.merge(base, mine, latest, touches)
        val conflict = result.conflicts.single()
        assertEquals(FieldPart.MapOrder, conflict.address.part)
        val choices = StudioList(listOf(FieldResolution(conflict, ConflictChoice.KEEP_LATEST)))
        val resolved = StudioConflictPolicy.merge(base, mine, latest, touches, choices)
        assertTrue(resolved.conflicts.isEmpty())
        assertEquals(
            listOf("b", "a"),
            (
                resolved.fields.records
                    .single()
                    .value as RawValue.OrderedMap
            ).entries.map { it.key },
        )
        assertEquals(
            RawValue.Text("mine"),
            (
                resolved.fields.records
                    .single()
                    .value as RawValue.OrderedMap
            ).entries.last().value,
        )
        val reordered = fields(map("external" to RawValue.Null, "b" to RawValue.Text("2"), "a" to RawValue.Text("1")))
        val stale = StudioConflictPolicy.merge(base, mine, reordered, touches, choices)
        val newConflict = stale.conflicts.single()
        assertEquals(FieldPart.MapOrder, newConflict.address.part)
        assertEquals(
            FieldValue.Present(
                RawValue.OrderedSet(StudioList(listOf("external", "b", "a"))),
            ),
            newConflict.latest,
        )
    }

    private fun fields(value: RawValue): StudioFields =
        StudioFields(StudioList(listOf(StudioField(key, value, FieldOrigin(SourcePresence.VALUE)))))

    private fun map(vararg entries: Pair<String, RawValue>): RawValue.OrderedMap =
        RawValue.OrderedMap(StudioList(entries.map { RawValue.Entry(it.first, it.second) }))
}
