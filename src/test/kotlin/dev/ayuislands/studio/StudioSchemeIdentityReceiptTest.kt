package dev.ayuislands.studio

import dev.ayuislands.font.FontIdentityRecord
import dev.ayuislands.font.FontRestoreState
import dev.ayuislands.font.FontWritePhase
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioSchemeIdentityReceipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioSchemeIdentityReceiptTest {
    @Test
    fun `each editor owner uses the same identity field without adopting another owners receipt`() {
        val record = FontIdentityRecord(ID, FontWritePhase.SETTLED, FontRestoreState.PENDING)
        val font = StudioSchemeIdentityReceipt.encode(EffectOwner.FONT, TOKEN, record)
        for (owner in listOf(EffectOwner.ACCENT, EffectOwner.FONT, EffectOwner.SYNTAX, EffectOwner.VCS)) {
            val receipt = StudioSchemeIdentityReceipt.encode(owner, TOKEN, record)
            assertEquals(font.copy(owner = owner), receipt)
            assertEquals(record, StudioSchemeIdentityReceipt.decode(owner, TOKEN, receipt))
            val foreign = if (owner == EffectOwner.FONT) EffectOwner.ACCENT else EffectOwner.FONT
            assertFailsWith<IllegalArgumentException> {
                StudioSchemeIdentityReceipt.decode(foreign, TOKEN, receipt)
            }
        }
    }

    @Test
    fun `pending identity may have been written and terminal ownership remains terminal after decode`() {
        val prepared = FontIdentityRecord(ID, FontWritePhase.PREPARED, FontRestoreState.PENDING)
        val receipt = StudioSchemeIdentityReceipt.encode(EffectOwner.FONT, TOKEN, prepared)
        assertEquals(NativeValue.Absent, receipt.before)
        assertEquals(NativeValue.Present(RawValue.Text(ID)), receipt.lastWritten)
        val recovered = StudioSchemeIdentityReceipt.decode(EffectOwner.FONT, TOKEN, receipt)
        assertEquals(ID, recovered.id)
        assertEquals(FontWritePhase.WRITING, recovered.applyPhase)
        assertEquals(FontRestoreState.PENDING, recovered.restoreState)
        val applied = prepared.copy(applyPhase = FontWritePhase.SETTLED)
        val owned = StudioSchemeIdentityReceipt.encode(EffectOwner.FONT, TOKEN, applied)
        assertEquals(receipt.copy(status = RestoreStatus.OWNED), owned)
        assertEquals(applied, StudioSchemeIdentityReceipt.decode(EffectOwner.FONT, TOKEN, owned))
        for (state in listOf(FontRestoreState.FOREIGN, FontRestoreState.RESTORED)) {
            val terminal = applied.copy(restoreState = state)
            assertEquals(
                terminal,
                StudioSchemeIdentityReceipt.decode(
                    EffectOwner.FONT,
                    TOKEN,
                    StudioSchemeIdentityReceipt.encode(EffectOwner.FONT, TOKEN, terminal),
                ),
            )
        }
    }

    @Test
    fun `UUID receipt rejects an unrelated before value identity or owner`() {
        val record = FontIdentityRecord(ID, FontWritePhase.PREPARED, FontRestoreState.PENDING)
        val receipt = StudioSchemeIdentityReceipt.encode(EffectOwner.FONT, TOKEN, record)
        val invalid =
            listOf(
                receipt.copy(before = NativeValue.Present(RawValue.Text("previous user value"))),
                receipt.copy(lastWritten = NativeValue.Absent),
                receipt.copy(lastWritten = NativeValue.Present(RawValue.Text("different"))),
                receipt.copy(identity = receipt.identity.copy(resource = "not-canonical")),
                receipt.copy(identity = receipt.identity.copy(ownerToken = "different")),
                receipt.copy(identity = receipt.identity.copy(field = "unrelated")),
                receipt.copy(id = "unrelated"),
                receipt.copy(owner = EffectOwner.GLOW),
            )
        for (item in invalid) {
            assertFailsWith<IllegalArgumentException> {
                StudioSchemeIdentityReceipt.decode(
                    EffectOwner.FONT,
                    TOKEN,
                    item,
                )
            }
        }
    }

    private companion object {
        const val ID = "11800832-b0dc-44ab-a989-d8d333013fbe"
        const val TOKEN = "operation"
    }
}
