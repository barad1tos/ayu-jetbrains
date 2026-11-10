package dev.ayuislands.studio

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.ChromeCacheSlot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.AccentClaimTokens
import dev.ayuislands.studio.session.AccentReceiptIdentity
import dev.ayuislands.studio.session.AccentValueTokens
import dev.ayuislands.studio.session.StudioAccentReceipts
import dev.ayuislands.studio.session.StudioNativeEvidence
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioAccentReceiptsTest {
    private val process = StudioProcess(41, Instant.EPOCH)
    private val identity = AccentReceiptIdentity("native/defaults", "attempt", process)

    @Test
    fun `default evidence preserves raw absence reference tokens and the first baseline`() {
        val receipt =
            StudioAccentReceipts.encodeDefault(
                identity,
                "Button.background",
                AccentValueTokens(null, "red"),
                "lazy",
            )
        val before = StudioNativeEvidence.fields(payload(receipt.before))
        val planned = StudioNativeEvidence.fields(payload(receipt.lastWritten))
        assertEquals(RawValue.Null, before.getValue("raw"))
        assertEquals(RawValue.Text("red"), planned.getValue("raw"))
        assertEquals(RawValue.Text("lazy"), before.getValue("baseline"))
        assertEquals(before.getValue("baseline"), planned.getValue("baseline"))
        assertEquals(process, StudioNativeEvidence.readProcess(receipt.before))
        assertEquals(RestoreStatus.PENDING, receipt.status)
        assertFailsWith<IllegalArgumentException> {
            StudioAccentReceipts.encodePeer(identity, AccentValueTokens("", null))
        }
    }

    @Test
    fun `claim evidence distinguishes missing provenance release and actual null writes`() {
        val observation =
            StudioAccentReceipts.encodeClaim(
                identity,
                "Button.background",
                AccentClaimTokens.Missing,
                AccentClaimTokens.Unowned,
                false,
            )
        val explicit =
            StudioAccentReceipts.encodeClaim(
                identity,
                "Button.background",
                AccentClaimTokens.Owned(null, "red"),
                AccentClaimTokens.Unowned,
                true,
            )
        assertNotEquals(payload(observation.before), payload(explicit.before))
        assertEquals(
            RawValue.BooleanValue(false),
            StudioNativeEvidence.fields(payload(observation.lastWritten)).getValue("writes-default"),
        )
        assertEquals(
            RawValue.BooleanValue(true),
            StudioNativeEvidence.fields(payload(explicit.lastWritten)).getValue("writes-default"),
        )
        assertNotEquals(observation.before, observation.lastWritten)
    }

    @Test
    fun `cache slots and peer inheritance keep separate physical fields`() {
        val values = AccentValueTokens(null, "color-reference")
        val cache =
            ChromeCacheSlot.entries.map {
                StudioAccentReceipts.encodeCache(identity, it, "StatusBar.background", 9, values)
            }
        assertEquals(3, cache.map { it.identity.field }.toSet().size)
        cache.forEach {
            assertEquals(RawValue.LongValue(9), StudioNativeEvidence.fields(payload(it.before)).getValue("generation"))
        }
        val peer = StudioAccentReceipts.encodePeer(identity, values)
        assertEquals(RawValue.Null, payload(peer.before))
        assertEquals(RawValue.Text("color-reference"), payload(peer.lastWritten))
    }

    @Test
    fun `external membership is ephemeral but an unknown ownership field is not retired`() {
        val membership =
            StudioAccentReceipts.encodeMembership(
                identity,
                AccentElementId.STATUS_BAR,
                AccentValueTokens(null, "claim"),
            )
        assertEquals(RawValue.Null, payload(membership.before))
        assertEquals(RawValue.Text("claim"), payload(membership.lastWritten))
        assertEquals(
            RestoreStatus.UNAVAILABLE,
            StudioAccentReceipts.recoverLost(listOf(membership), process).single().status,
        )
        val unknown = membership.copy(identity = membership.identity.copy(field = "accent/chrome/membership/FUTURE"))
        val recovered = StudioAccentReceipts.recoverLost(listOf(membership, unknown), process.copy(pid = 42))
        assertEquals(listOf(RestoreStatus.RESTORED, RestoreStatus.UNAVAILABLE), recovered.map { it.status })
        assertFailsWith<IllegalArgumentException> {
            StudioAccentReceipts.encodeMembership(identity, AccentElementId.LINKS, AccentValueTokens(null, "claim"))
        }
    }

    @Test
    fun `surface dependency blockers treat a raw default and its provenance as the same field`() {
        val raw = StudioAccentReceipts.encodeDefault(identity, "Panel.background", AccentValueTokens(null, "color"))
        val claim =
            StudioAccentReceipts.encodeClaim(
                identity.copy(ownerToken = "newer"),
                "Panel.background",
                AccentClaimTokens.Unowned,
                AccentClaimTokens.Relinquished,
                false,
            )
        assertTrue(StudioAccentReceipts.isBlocked(identity.resource, raw.identity.field, listOf(claim)))
        assertTrue(StudioAccentReceipts.isBlocked(identity.resource, claim.identity.field, listOf(raw)))
        assertFalse(StudioAccentReceipts.isBlocked("different", raw.identity.field, listOf(claim)))
    }

    @Test
    fun `status updates never replace original evidence or resurrect a terminal receipt`() {
        val prepared = StudioAccentReceipts.encodePeer(identity, AccentValueTokens("before", "after"))
        val interrupted = StudioAccentReceipts.refresh(prepared, AccentWriteState.OWNED, false)
        assertEquals(RestoreStatus.PENDING, interrupted.status)
        val applied = StudioAccentReceipts.refresh(interrupted, AccentWriteState.OWNED, true)
        assertEquals(RestoreStatus.OWNED, applied.status)
        val foreign = StudioAccentReceipts.refresh(applied, AccentWriteState.RELINQUISHED, true)
        assertEquals(RestoreStatus.FOREIGN, foreign.status)
        assertSame(prepared.before, foreign.before)
        assertSame(prepared.lastWritten, foreign.lastWritten)
        assertSame(foreign, StudioAccentReceipts.refresh(foreign, AccentWriteState.OWNED, true))
        val restored = StudioAccentReceipts.refresh(applied, AccentWriteState.RESTORED, true)
        assertEquals(RestoreStatus.RESTORED, restored.status)
    }

    @Test
    fun `lost handles settle only known ephemeral fields in a confirmed different process`() {
        val peer = StudioAccentReceipts.encodePeer(identity, AccentValueTokens("before", "after"))
        for (current in listOf(null, process, process.copy(startedAt = null))) {
            assertEquals(
                RestoreStatus.UNAVAILABLE,
                StudioAccentReceipts.recoverLost(listOf(peer), current).single().status,
            )
        }
        val replacement = process.copy(startedAt = Instant.EPOCH.plusSeconds(1))
        assertEquals(
            RestoreStatus.RESTORED,
            StudioAccentReceipts.recoverLost(listOf(peer), replacement).single().status,
        )
        val persistent = peer.copy(identity = peer.identity.copy(field = "editor/scheme/attributes"))
        assertEquals(
            RestoreStatus.UNAVAILABLE,
            StudioAccentReceipts.recoverLost(listOf(persistent), replacement).single().status,
        )
        val other =
            StudioAccentReceipts.encodePeer(
                identity.copy(process = replacement),
                AccentValueTokens("before", "after"),
            )
        assertFailsWith<IllegalArgumentException> {
            StudioAccentReceipts.recoverLost(listOf(peer.copy(lastWritten = other.lastWritten)), replacement)
        }
    }

    @Test
    fun `persistent evidence with its own payload cannot interrupt ephemeral recovery`() {
        val peer = StudioAccentReceipts.encodePeer(identity, AccentValueTokens(null, "after"))
        val persistent =
            peer.copy(
                identity = peer.identity.copy(field = "editor/scheme/attributes"),
                before = NativeValue.Absent,
                lastWritten = NativeValue.Present(RawValue.Text("domain-owned payload")),
            )
        val restored = StudioAccentReceipts.recoverLost(listOf(persistent, peer), process.copy(pid = 42))
        assertEquals(listOf(RestoreStatus.UNAVAILABLE, RestoreStatus.RESTORED), restored.map { it.status })
        assertSame(persistent.before, restored.first().before)
        assertSame(persistent.lastWritten, restored.first().lastWritten)
    }

    @Test
    fun `unresolved journal blockers overlap fields regardless of their owner token`() {
        val newer = identity.copy(ownerToken = "newer")
        val claim =
            StudioAccentReceipts.encodeClaim(
                newer,
                "Button.background",
                AccentClaimTokens.Unowned,
                AccentClaimTokens.Relinquished,
                false,
            )
        val keys = setOf("Button.background", "Panel.background")
        assertEquals(
            setOf("Button.background"),
            StudioAccentReceipts.blockedDefaults(identity.resource, keys, listOf(claim)),
        )
        assertTrue(
            StudioAccentReceipts
                .blockedDefaults(
                    identity.resource,
                    keys,
                    listOf(claim.copy(status = RestoreStatus.FOREIGN)),
                ).isEmpty(),
        )
        assertTrue(StudioAccentReceipts.blockedDefaults("unrelated", keys, listOf(claim)).isEmpty())
        val unknown = claim.copy(identity = claim.identity.copy(field = "future/native-field"))
        assertEquals(keys, StudioAccentReceipts.blockedDefaults(identity.resource, keys, listOf(unknown)))
        val peer = StudioAccentReceipts.encodePeer(newer, AccentValueTokens("before", "after"))
        assertTrue(StudioAccentReceipts.isBlocked(identity.resource, peer.identity.field, listOf(peer)))
        assertFalse(
            StudioAccentReceipts.isBlocked(
                identity.resource,
                peer.identity.field,
                listOf(peer.copy(status = RestoreStatus.RESTORED)),
            ),
        )
    }

    private fun payload(value: NativeValue): RawValue =
        StudioNativeEvidence.fields((value as NativeValue.Present).value).getValue("value")
}
