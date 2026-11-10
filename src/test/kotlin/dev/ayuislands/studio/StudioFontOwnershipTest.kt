package dev.ayuislands.studio

import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontFamily
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontOwnershipCodec
import dev.ayuislands.font.FontOwnershipRecord
import dev.ayuislands.font.FontOwnershipStatus
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.fields.StudioRuntimeFields
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.persistence.StudioFontOwnership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioFontOwnershipTest {
    private val snapshot =
        FontSnapshot.Explicit(
            FontData(listOf("Missing"), listOf(FontFamily("Missing", 13.25f)), 13.25f, 1.3f, false, null, ""),
        )
    private val managed = FontOwnershipRecord(FontOwnershipStatus.OWNED, snapshot, snapshot)
    private val editor = FontIdentityRules.key(ID, FontSurface.EDITOR)
    private val console = FontIdentityRules.key(ID, FontSurface.CONSOLE)

    @Test
    fun `unchanged valid XML remains byte identical and status edits preserve unknown entries and order`() {
        val raw = "\n" + FontOwnershipCodec.encode(managed) + "\n"
        val initial = runtime(2, linkedMapOf("future" to "<opaque a='  '/>", editor to raw, "last" to "untouched"))
        val ownership = StudioFontOwnership(initial)
        assertEquals(managed, ownership.read(editor))
        assertTrue(ownership.changes(mapOf(editor to managed), hasWrites = false).isEmpty())
        val suspended = managed.copy(status = FontOwnershipStatus.SUSPENDED)
        val changes = ownership.changes(linkedMapOf(console to managed, editor to suspended), hasWrites = true)
        assertEquals(listOf(editor, console), changes.map { (it.address.part as FieldPart.MapEntry).key })
        assertEquals(FieldValue.Present(RawValue.Text(raw)), changes.first().before)
        val updated = StudioRuntimeFields.merge(initial, changes)
        assertEquals(entries(initial).keys.toList() + console, entries(updated).keys.toList())
        assertEquals("<opaque a='  '/>", entries(updated)["future"])
        assertEquals("untouched", entries(updated)["last"])
        assertEquals(suspended, StudioFontOwnership(updated).read(editor))
        assertEquals(raw, entries(initial)[editor])
    }

    @Test
    fun `unknown stored records remain present and cannot be replaced or removed`() {
        val ownership = StudioFontOwnership(runtime(2, mapOf(editor to "<font-ownership version='future'/>")))
        assertTrue(editor in ownership.keys)
        assertNull(ownership.read(editor))
        assertNull(ownership.read(console))
        assertTrue(ownership.changes(emptyMap(), hasWrites = false).isEmpty())
        assertFailsWith<IllegalStateException> { ownership.changes(mapOf(editor to managed), hasWrites = true) }
        assertFailsWith<IllegalStateException> { ownership.changes(mapOf(editor to null), hasWrites = false) }
    }

    @Test
    fun `only an explicit successful write advances supported aggregate versions`() {
        for (version in listOf(-1, 0, 1, 2, 99)) {
            val initial = runtime(version, mapOf(editor to FontOwnershipCodec.encode(managed)))
            val ownership = StudioFontOwnership(initial)
            assertEquals(version, ownership.version)
            assertTrue(ownership.changes(emptyMap(), hasWrites = false).isEmpty())
            if (version !in 0..2) {
                assertFailsWith<IllegalStateException> {
                    ownership.changes(mapOf(editor to managed), hasWrites = true)
                }
            } else {
                assertTrue(ownership.changes(mapOf(editor to managed), hasWrites = false).isEmpty())
                val changes = ownership.changes(mapOf(editor to managed), hasWrites = true)
                assertEquals(if (version == 2) 0 else 1, changes.size)
                assertEquals(2, StudioFontOwnership(StudioRuntimeFields.merge(initial, changes)).version)
            }
        }
    }

    @Test
    fun `unrepresentable managed baseline is rejected while exact fractional and nullable data survives`() {
        val initial = runtime(0, emptyMap())
        val ownership = StudioFontOwnership(initial)
        val changed = ownership.changes(mapOf(editor to managed), hasWrites = true)
        assertEquals(managed, StudioFontOwnership(StudioRuntimeFields.merge(initial, changed)).read(editor))
        for (size in listOf(0f, -0f, -2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val invalid = managed.copy(baseline = FontSnapshot.Explicit(snapshot.preferences.copy(templateSize = size)))
            assertFailsWith<IllegalStateException> { ownership.changes(mapOf(editor to invalid), hasWrites = true) }
        }
        val removal =
            StudioFontOwnership(runtime(2, mapOf(editor to FontOwnershipCodec.encode(managed))))
                .changes(mapOf(editor to null), hasWrites = true)
        assertEquals(FieldValue.Absent, removal.single().after)
    }

    private fun runtime(
        version: Int,
        records: Map<String, String>,
    ): StudioRuntimeState {
        val state =
            AyuIslandsState().apply {
                fontOwnershipVersion = version
                fontOwnershipSnapshots.putAll(records)
            }
        return assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture(state)))
            .document.runtime
    }

    private fun entries(runtime: StudioRuntimeState): Map<String, String> =
        (
            runtime.legacy.records
                .single { it.key.name == "fontOwnershipSnapshots" }
                .value as RawValue.OrderedMap
        ).entries
            .associate { it.key to (it.value as RawValue.Text).value }

    private companion object {
        const val ID = "11800832-b0dc-44ab-a989-d8d333013fbe"
    }
}
