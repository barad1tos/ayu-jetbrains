package dev.ayuislands.studio

import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.GlowMemberTokens
import dev.ayuislands.studio.session.GlowReceiptIdentity
import dev.ayuislands.studio.session.StudioGlowMembershipReceipts
import dev.ayuislands.studio.session.StudioGlowReceipts
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioGlowMembershipReceiptsTest {
    private val process = StudioProcess(42, Instant.EPOCH)
    private val identity = GlowReceiptIdentity("glow/runtime/pane", "attempt", process)
    private val overlay = GlowMemberTokens.Overlay("entry", "pane", "host", "layer", "resize", null, true)
    private val focus = GlowMemberTokens.Focus("listener", "component", null, null)

    @Test
    fun `prepared memberships encode exact nullable identities inside process evidence`() {
        val pane = StudioGlowMembershipReceipts.encodeAttachment(identity, overlay)
        val ring = StudioGlowMembershipReceipts.encodeAttachment(identity.copy(resource = "listener"), focus)
        assertEquals(RawValue.Null, value(pane.before))
        assertEquals(RawValue.Null, value(ring.before))
        assertEquals("glow/overlay/membership", pane.identity.field)
        assertEquals("glow/focus/membership", ring.identity.field)
        val paneFields = (value(pane.lastWritten) as RawValue.OrderedMap).entries.associate { it.key to it.value }
        assertEquals(RawValue.Text("entry"), paneFields["entry"])
        assertEquals(RawValue.Text("resize"), paneFields["componentListener"])
        assertEquals(RawValue.Null, paneFields["boundsListener"])
        assertEquals(RawValue.BooleanValue(true), paneFields["editor"])
        val ringFields = (value(ring.lastWritten) as RawValue.OrderedMap).entries.associate { it.key to it.value }
        assertEquals(RawValue.Text("component"), ringFields["component"])
        assertEquals(RawValue.Null, ringFields["parent"])
        assertEquals(RawValue.Null, ringFields["window"])
    }

    @Test
    fun `partial attachment and cleanup retain the original member tuple until native completion`() {
        val receipt = StudioGlowMembershipReceipts.encodeAttachment(identity, overlay)
        val pending = listOf(GlowCheckpointPhase.PREPARED, GlowCheckpointPhase.APPLYING, GlowCheckpointPhase.RESTORING)
        for (phase in pending) {
            assertEquals(RestoreStatus.PENDING, StudioGlowMembershipReceipts.refreshAttachment(receipt, phase).status)
        }
        val applied = StudioGlowMembershipReceipts.refreshAttachment(receipt, GlowCheckpointPhase.APPLIED)
        assertEquals(RestoreStatus.OWNED, applied.status)
        assertEquals(receipt, applied.copy(status = receipt.status))
        val restored = StudioGlowMembershipReceipts.refreshAttachment(applied, GlowCheckpointPhase.RESTORED)
        assertEquals(RestoreStatus.RESTORED, restored.status)
        assertEquals(restored, StudioGlowMembershipReceipts.refreshAttachment(restored, GlowCheckpointPhase.APPLIED))
    }

    @Test
    fun `retirement and process loss never turn logical absence into missing provenance`() {
        val removal = StudioGlowMembershipReceipts.encodeRemoval(identity, overlay)
        assertEquals(RawValue.Null, value(removal.lastWritten))
        assertEquals(RestoreStatus.PENDING, StudioGlowMembershipReceipts.refreshRemoval(removal, false).status)
        val complete = StudioGlowMembershipReceipts.refreshRemoval(removal, true)
        assertEquals(RestoreStatus.RESTORED, complete.status)
        assertEquals(removal, complete.copy(status = removal.status))
        val addition = StudioGlowMembershipReceipts.encodeAttachment(identity, overlay)
        for (receipt in listOf(addition, removal)) {
            val uncertain = StudioGlowReceipts.recoverLost(listOf(receipt), process).single()
            assertEquals(RestoreStatus.UNAVAILABLE, uncertain.status)
            val gone = StudioGlowReceipts.recoverLost(listOf(receipt), StudioProcess(43, Instant.EPOCH)).single()
            assertEquals(RestoreStatus.RESTORED, gone.status)
            assertEquals(receipt, gone.copy(status = receipt.status))
        }
    }

    @Test
    fun `newer dependent fields block member cleanup regardless of owner or field kind`() {
        val receipt = StudioGlowMembershipReceipts.encodeAttachment(identity, overlay)
        val newer = receipt.copy(identity = receipt.identity.copy(field = "glow/pane/COLOR", ownerToken = "newer"))
        assertTrue(StudioGlowMembershipReceipts.isBlocked(setOf(identity.resource), listOf(newer)))
        assertFalse(StudioGlowMembershipReceipts.isBlocked(setOf("different-pane"), listOf(newer)))
        val componentBorder =
            receipt.copy(identity = receipt.identity.copy(resource = "component", field = "glow/focus/BORDER"))
        assertTrue(StudioGlowMembershipReceipts.isBlocked(setOf("listener", "component"), listOf(componentBorder)))
        assertFalse(
            StudioGlowMembershipReceipts.isBlocked(
                setOf(identity.resource),
                listOf(newer.copy(status = RestoreStatus.FOREIGN)),
            ),
        )
    }

    private fun value(native: NativeValue): RawValue =
        ((native as NativeValue.Present).value as RawValue.OrderedMap).entries.single { it.key == "value" }.value
}
