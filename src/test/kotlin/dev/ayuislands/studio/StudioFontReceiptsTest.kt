package dev.ayuislands.studio

import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontFamily
import dev.ayuislands.font.FontPreferenceField
import dev.ayuislands.font.FontRestoreOrigin
import dev.ayuislands.font.FontRestoreState
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import dev.ayuislands.font.FontWritePhase
import dev.ayuislands.font.FontWriteRecord
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.FontReceiptIdentity
import dev.ayuislands.studio.session.StudioFontReceipts
import dev.ayuislands.studio.session.StudioSchemeIdentityReceipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudioFontReceiptsTest {
    private val identity = FontReceiptIdentity("canonical-scheme", FontSurface.EDITOR, "operation")

    @Test
    fun `newer identity write blocks both font surfaces until terminal`() {
        val base = StudioFontReceipts.encode(identity.copy(ownerToken = "newer"), record()).first()
        val metadata = base.copy(identity = base.identity.copy(field = StudioSchemeIdentityReceipt.FIELD))
        for (surface in FontSurface.entries) {
            assertEquals(
                FontPreferenceField.entries.toSet(),
                StudioFontReceipts.blockedFields(identity.copy(surface = surface), listOf(metadata)),
            )
            assertTrue(
                StudioFontReceipts
                    .blockedFields(
                        identity.copy(surface = surface),
                        listOf(metadata.copy(status = RestoreStatus.RESTORED)),
                    ).isEmpty(),
            )
        }
    }

    @Test
    fun `newer unresolved mode blocks every group regardless of owner token`() {
        val newer = identity.copy(ownerToken = "newer")
        val receipts = StudioFontReceipts.encode(newer, record().copy(target = FontSnapshot.Inherited))

        assertEquals(FontPreferenceField.entries.toSet(), StudioFontReceipts.blockedFields(identity, receipts))
        assertTrue(StudioFontReceipts.blockedFields(identity.copy(surface = FontSurface.CONSOLE), receipts).isEmpty())
        assertTrue(StudioFontReceipts.blockedFields(identity.copy(resource = "another"), receipts).isEmpty())
    }

    @Test
    fun `independent groups and terminal receipts never block another native field`() {
        val receipt = StudioFontReceipts.encode(identity.copy(ownerToken = "newer"), record())[2]

        assertEquals(setOf(FontPreferenceField.SPACING), StudioFontReceipts.blockedFields(identity, listOf(receipt)))
        for (status in listOf(RestoreStatus.RESTORED, RestoreStatus.FOREIGN)) {
            assertTrue(StudioFontReceipts.blockedFields(identity, listOf(receipt.copy(status = status))).isEmpty())
        }
        val unknown = receipt.copy(identity = receipt.identity.copy(field = "font/EDITOR/FUTURE"))
        assertEquals(FontPreferenceField.entries.toSet(), StudioFontReceipts.blockedFields(identity, listOf(unknown)))
    }

    @Test
    fun `restoration appends stable evidence without rewriting original before or planned values`() {
        val initial = record()
        val prepared = StudioFontReceipts.encode(identity, initial)
        val applied = initial.copy(applyPhase = FontWritePhase.SETTLED)
        val origin =
            FontRestoreOrigin.Field(
                FontPreferenceField.FAMILIES,
                (initial.target as FontSnapshot.Explicit).preferences,
            )
        val armed = StudioFontReceipts.encode(identity, applied.copy(origins = listOf(origin)))

        assertEquals(5, prepared.size)
        assertEquals(6, armed.size)
        for (index in prepared.indices) {
            assertEquals(prepared[index], armed[index].copy(status = prepared[index].status))
        }
        assertEquals(prepared.first().identity, armed.last().identity)
        assertEquals(RestoreStatus.PENDING, armed.last().status)
        assertEquals(armed, StudioFontReceipts.encode(identity, applied.copy(origins = listOf(origin))))
        val recovered = StudioFontReceipts.decode(identity, armed)
        assertEquals(FontWritePhase.SETTLED, recovered.applyPhase)
        assertEquals(FontRestoreState.WRITING, recovered.fields[FontPreferenceField.FAMILIES])
        assertEquals(listOf(origin), recovered.origins)
        assertEquals(initial.before, recovered.before)
        assertEquals(initial.target, recovered.target)
    }

    @Test
    fun `unacknowledged forward writes recover conservatively and terminal foreign groups stay terminal`() {
        val initial = record()
        val pending = StudioFontReceipts.decode(identity, StudioFontReceipts.encode(identity, initial))
        assertEquals(FontWritePhase.WRITING, pending.applyPhase)
        val foreign =
            initial.copy(
                applyPhase = FontWritePhase.SETTLED,
                fields = mapOf(FontPreferenceField.SPACING to FontRestoreState.FOREIGN),
            )
        val recovered = StudioFontReceipts.decode(identity, StudioFontReceipts.encode(identity, foreign))

        assertEquals(FontRestoreState.FOREIGN, recovered.fields[FontPreferenceField.SPACING])
        assertEquals(FontWritePhase.SETTLED, recovered.applyPhase)
        assertTrue(recovered.origins.isEmpty())
    }

    @Test
    fun `mode restoration retains its inherited source independently from the explicit baseline`() {
        val initial = record().copy(target = FontSnapshot.Inherited, applyPhase = FontWritePhase.SETTLED)
        val effective = (record().target as FontSnapshot.Explicit).preferences
        val origin = FontRestoreOrigin.Mode(FontSnapshot.Inherited, effective)
        val encoded = StudioFontReceipts.encode(identity, initial.copy(origins = listOf(origin)))

        assertEquals(2, encoded.size)
        assertEquals(encoded.first().identity, encoded.last().identity)
        val recovered = StudioFontReceipts.decode(identity, encoded)
        assertEquals(FontRestoreState.WRITING, recovered.inheritanceState)
        assertEquals(initial.before, recovered.before)
        assertEquals(listOf(origin), recovered.origins)
    }

    @Test
    fun `foreign identity duplicate missing and reordered receipts never produce guessed recovery`() {
        val receipts = StudioFontReceipts.encode(identity, record())
        val invalid =
            listOf(
                receipts.dropLast(1),
                receipts.reversed(),
                receipts + receipts.first(),
                receipts.map { it.copy(identity = it.identity.copy(ownerToken = "other-operation")) },
                receipts.map { it.copy(identity = it.identity.copy(resource = "other-scheme")) },
            )

        for (value in invalid) assertFailsWith<IllegalArgumentException> { StudioFontReceipts.decode(identity, value) }
    }

    private fun record(): FontWriteRecord {
        val before =
            FontData(
                listOf("Dialog", "Unknown"),
                listOf(FontFamily("Dialog", 17.5f), FontFamily("Unknown", null)),
                19.25f,
                1.3f,
                false,
                null,
                "",
            )
        return FontWriteRecord(
            FontSnapshot.Explicit(before),
            FontSnapshot.Explicit(
                before.copy(
                    effectiveFamilies = listOf("Monospaced"),
                    families = listOf(FontFamily("Monospaced", 14.5f)),
                    templateSize = 14.5f,
                    lineSpacing = 1.1f,
                    ligatures = true,
                ),
            ),
            before,
            FontWritePhase.PREPARED,
            FontRestoreState.PENDING,
            emptyMap(),
            emptyList(),
        )
    }
}
