package dev.ayuislands.studio

import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.glow.GlowFailureOrigin
import dev.ayuislands.glow.GlowFailureRecord
import dev.ayuislands.glow.GlowFailureRestore
import dev.ayuislands.glow.GlowFieldState
import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowPaneFields
import dev.ayuislands.glow.GlowPaneRecord
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.GlowWaveformFailure
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.GlowFailureTokens
import dev.ayuislands.studio.session.GlowHealthTokens
import dev.ayuislands.studio.session.GlowReceiptIdentity
import dev.ayuislands.studio.session.StudioGlowHealthReceipts
import java.awt.Color
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioGlowHealthReceiptsTest {
    private val identity = GlowReceiptIdentity("glow/runtime/pane", "attempt", StudioProcess(42, Instant.EPOCH))
    private val appearance =
        GlowPaneAppearance(
            Color.CYAN,
            GlowStyle.SOFT,
            70,
            4,
            GlowPlacement.ISLAND,
            GlowShape.WAVEFORM,
            WaveformConfig(),
            true,
        )
    private val original = GlowWaveformFailure(GlowFailureOrigin.Route(null), null)
    private val token = GlowFailureTokens("failure-a", null)

    @Test
    fun `only a native waveform retry plans to clear the captured failure`() {
        val snapshot = record()
        val changed = appearance.copy(waveform = appearance.waveform.copy(amplitude = 30))
        val healing = StudioGlowHealthReceipts.encode(identity, snapshot, change(appearance, changed), token)
        assertEquals(RawValue.Null, value(healing.lastWritten))
        val transitions =
            listOf(
                null,
                change(appearance, appearance.copy(color = Color.ORANGE)),
                change(appearance, changed.copy(shape = GlowShape.SOLID)),
            )
        for (transition in transitions) {
            val preserved = StudioGlowHealthReceipts.encode(identity, snapshot, transition, token)
            assertEquals(preserved.before, preserved.lastWritten)
            assertEquals(RestoreStatus.PENDING, preserved.status)
        }
        val solid = appearance.copy(shape = GlowShape.SOLID)
        val enabled = StudioGlowHealthReceipts.encode(identity, snapshot, change(solid, appearance), token)
        assertEquals(RawValue.Null, value(enabled.lastWritten))
    }

    @Test
    fun `NaN payload changes follow native config equality and retain the failure`() {
        val before = appearance.copy(waveform = appearance.waveform.copy(loopSeconds = Float.fromBits(0x7fc00001)))
        val after = appearance.copy(waveform = appearance.waveform.copy(loopSeconds = Float.fromBits(0x7fc00002)))
        val receipt = StudioGlowHealthReceipts.encode(identity, record(), change(before, after), token)
        assertEquals(receipt.before, receipt.lastWritten)
    }

    @Test
    fun `restore preparations retain occurrence identity and remain pending until native settlement`() {
        val snapshot = record().copy(phase = GlowCheckpointPhase.APPLYING, state = GlowFieldState.WRITING)
        val initial = StudioGlowHealthReceipts.encode(identity, snapshot, null, token)
        val first =
            StudioGlowHealthReceipts.encodeRestore(
                identity,
                0,
                snapshot.copy(restoration = GlowFailureRestore(null, GlowFieldState.RESTORED)),
                GlowHealthTokens(token, null),
            )
        val foreign = GlowWaveformFailure(GlowFailureOrigin.Route(null), null)
        val second =
            StudioGlowHealthReceipts.encodeRestore(
                identity,
                1,
                snapshot.copy(restoration = GlowFailureRestore(foreign, GlowFieldState.FOREIGN)),
                GlowHealthTokens(token, GlowFailureTokens("failure-b", null)),
            )
        val receipts = listOf(initial, first, second)
        assertTrue(StudioGlowHealthReceipts.refresh(receipts, snapshot).all { it.status == RestoreStatus.PENDING })
        assertEquals(initial.before, first.lastWritten)
        assertEquals(initial.before, second.lastWritten)
        assertTrue(first.before != second.before)
        val settled =
            StudioGlowHealthReceipts.refresh(
                receipts,
                snapshot.copy(phase = GlowCheckpointPhase.RESTORED, state = GlowFieldState.FOREIGN),
            )
        assertTrue(settled.all { it.status == RestoreStatus.FOREIGN })
        receipts.zip(settled).forEach { (before, after) -> assertEquals(before, after.copy(status = before.status)) }
        assertEquals(
            settled,
            StudioGlowHealthReceipts.refresh(settled, snapshot.copy(state = GlowFieldState.RESTORED)),
        )
        assertTrue(StudioGlowHealthReceipts.isBlocked(identity.resource, receipts))
        assertFalse(StudioGlowHealthReceipts.isBlocked(identity.resource, settled))
    }

    @Test
    fun `failure descriptors never inspect exception messages or invent missing producers`() {
        val cause =
            object : RuntimeException() {
                override val message: String
                    get() = error("Exception details are not portable state")

                override fun toString(): String = error("Exception details are not portable state")
            }
        val failure = GlowWaveformFailure(GlowFailureOrigin.Local(null), cause)
        val receipt = StudioGlowHealthReceipts.encode(identity, record().copy(before = failure), null, token)
        val fields = (value(receipt.before) as RawValue.OrderedMap).entries.associate { it.key to it.value }
        assertEquals(setOf("occurrence", "origin", "producer"), fields.keys)
        assertEquals(RawValue.Text("failure-a"), fields["occurrence"])
        assertEquals(RawValue.Text("LOCAL"), fields["origin"])
        assertEquals(RawValue.Null, fields["producer"])
    }

    private fun record(): GlowFailureRecord =
        GlowFailureRecord(original, GlowCheckpointPhase.PREPARED, GlowFieldState.PREPARED, null, null)

    private fun change(
        before: GlowPaneAppearance,
        after: GlowPaneAppearance,
    ): GlowPaneRecord =
        GlowPaneRecord(
            before,
            after,
            GlowCheckpointPhase.PREPARED,
            GlowPaneField.entries
                .filterNot { GlowPaneFields.matches(it, before, after) }
                .associateWith { GlowFieldState.PREPARED },
        )

    private fun value(native: NativeValue): RawValue =
        ((native as NativeValue.Present).value as RawValue.OrderedMap).entries.single { it.key == "value" }.value
}
