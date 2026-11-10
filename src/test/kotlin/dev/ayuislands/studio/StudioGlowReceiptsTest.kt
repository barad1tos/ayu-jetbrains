package dev.ayuislands.studio

import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.glow.GlowFieldState
import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowPaneRecord
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.GlowReceiptIdentity
import dev.ayuislands.studio.session.StudioGlowReceipts
import java.awt.Color
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StudioGlowReceiptsTest {
    private val process = StudioProcess(42, Instant.parse("2026-11-06T12:00:00.000000123Z"))
    private val identity = GlowReceiptIdentity("glow/runtime-a/pane-a", "operation-a", process)
    private val before =
        GlowPaneAppearance(
            OpaqueReceiptColor(),
            GlowStyle.SOFT,
            71,
            11,
            GlowPlacement.ISLAND,
            GlowShape.WAVEFORM,
            WaveformConfig(loopSeconds = Float.fromBits(0x7fc01234)),
            isVisible = false,
        )

    @Test
    fun `pane evidence uses exact changed fields opaque colors and raw float bits`() {
        val planned = before.copy(color = OpaqueReceiptColor(), waveform = before.waveform.copy(loopSeconds = -0f))
        val record =
            GlowPaneRecord(
                before,
                planned,
                GlowCheckpointPhase.PREPARED,
                linkedMapOf(
                    GlowPaneField.LOOP_SECONDS to GlowFieldState.PREPARED,
                    GlowPaneField.COLOR to GlowFieldState.PREPARED,
                ),
            )

        val receipts = StudioGlowReceipts.encodePane(identity, record, "color-before", "color-after")

        assertEquals(listOf("glow/pane/COLOR", "glow/pane/LOOP_SECONDS"), receipts.map { it.identity.field })
        assertEquals(RawValue.Text("color-before"), value(receipts.first().before))
        assertEquals(RawValue.Text("color-after"), value(receipts.first().lastWritten))
        assertNotEquals(receipts.first().before, receipts.first().lastWritten)
        assertEquals(RawValue.FloatBits(0x7fc01234), value(receipts.last().before))
        assertEquals(RawValue.FloatBits((-0f).toRawBits()), value(receipts.last().lastWritten))
        assertTrue(receipts.all { it.status == RestoreStatus.PENDING })
    }

    @Test
    fun `phase and terminal progress never rewrite original payloads or identities`() {
        val initial = record()
        val originals = StudioGlowReceipts.encodePane(identity, initial, "same-color", "same-color")
        for ((phase, state, expected) in listOf(
            Triple(GlowCheckpointPhase.APPLYING, GlowFieldState.WRITING, RestoreStatus.PENDING),
            Triple(GlowCheckpointPhase.APPLIED, GlowFieldState.WRITING, RestoreStatus.OWNED),
            Triple(GlowCheckpointPhase.ACCEPTED, GlowFieldState.WRITING, RestoreStatus.OWNED),
            Triple(GlowCheckpointPhase.RESTORING, GlowFieldState.RESTORED, RestoreStatus.RESTORED),
            Triple(GlowCheckpointPhase.RESTORED, GlowFieldState.FOREIGN, RestoreStatus.FOREIGN),
        )) {
            val changed = initial.copy(phase = phase, fields = mapOf(GlowPaneField.INTENSITY to state))
            val receipt = StudioGlowReceipts.refreshPane(originals, changed).single()
            assertEquals(expected, receipt.status)
            assertEquals(originals.single(), receipt.copy(status = RestoreStatus.PENDING))
        }
    }

    @Test
    fun `newer unresolved pane fields block by resource and field regardless of owner token`() {
        val newer = identity.copy(ownerToken = "newer")
        val receipt = StudioGlowReceipts.encodePane(newer, record(), "same-color", "same-color").single()
        for (status in RestoreStatus.entries) {
            val blocked = StudioGlowReceipts.blockedPaneFields(identity.resource, listOf(receipt.copy(status = status)))
            val expected =
                if (status in setOf(RestoreStatus.RESTORED, RestoreStatus.FOREIGN)) {
                    emptySet()
                } else {
                    setOf(GlowPaneField.INTENSITY)
                }
            assertEquals(expected, blocked)
        }
        assertTrue(StudioGlowReceipts.blockedPaneFields("another-pane", listOf(receipt)).isEmpty())
        val future = receipt.copy(identity = receipt.identity.copy(field = "glow/pane/FUTURE"))
        assertEquals(
            GlowPaneField.entries.toSet(),
            StudioGlowReceipts.blockedPaneFields(identity.resource, listOf(future)),
        )
    }

    @Test
    fun `lost native handles settle only after confirmed process replacement and preserve terminal receipts`() {
        val receipt = StudioGlowReceipts.encodePane(identity, record(), "same-color", "same-color").single()
        for (current in listOf(null, process, StudioProcess(process.pid, null))) {
            val result = StudioGlowReceipts.recoverLost(listOf(receipt), current).single()
            assertEquals(receipt.copy(status = RestoreStatus.UNAVAILABLE), result)
        }
        for (current in listOf(StudioProcess(43, null), process.copy(startedAt = process.startedAt!!.plusNanos(1)))) {
            for (status in RestoreStatus.entries) {
                val original = receipt.copy(status = status)
                val expected =
                    if (status in setOf(RestoreStatus.FOREIGN, RestoreStatus.RESTORED)) {
                        original
                    } else {
                        original.copy(status = RestoreStatus.RESTORED)
                    }
                assertEquals(expected, StudioGlowReceipts.recoverLost(listOf(original), current).single())
            }
        }
    }

    @Test
    fun `invalid provenance or blank color identity cannot claim safe recovery`() {
        val receipt = StudioGlowReceipts.encodePane(identity, record(), "same-color", "same-color").single()
        val corrupted = receipt.copy(lastWritten = NativeValue.Present(RawValue.Text("unknown")))
        assertFailsWith<IllegalArgumentException> {
            StudioGlowReceipts.recoverLost(listOf(corrupted), StudioProcess(43, null))
        }
        val color = record().copy(fields = mapOf(GlowPaneField.COLOR to GlowFieldState.PREPARED))
        assertFailsWith<IllegalArgumentException> { StudioGlowReceipts.encodePane(identity, color, "", "after") }
    }

    @Test
    fun `all native pane fields retain scalar values without applying runtime defaults`() {
        val snapshot = record().copy(fields = GlowPaneField.entries.associateWith { GlowFieldState.PREPARED })
        val receipts = StudioGlowReceipts.encodePane(identity, snapshot, "opaque-before", "opaque-planned")
        val expected =
            listOf(
                RawValue.Text("opaque-before"),
                RawValue.Text("SOFT"),
                RawValue.Integer(71),
                RawValue.Integer(11),
                RawValue.Text("ISLAND"),
                RawValue.Text("WAVEFORM"),
                RawValue.Text("CLOCKWISE"),
                RawValue.Text("CENTERED"),
                RawValue.Integer(24),
                RawValue.Integer(100),
                RawValue.FloatBits(0x7fc01234),
                RawValue.Integer(1),
                RawValue.Integer(199),
                RawValue.BooleanValue(false),
            )
        assertEquals(GlowPaneField.entries.size, receipts.size)
        assertEquals(expected, receipts.map { value(it.before) })
    }

    @Test
    fun `terminal receipts stay terminal during refresh and missing scopes cannot be invented`() {
        val snapshot = record()
        val receipt = StudioGlowReceipts.encodePane(identity, snapshot, "color", "color").single()
        val writing = snapshot.copy(phase = GlowCheckpointPhase.APPLIED)
        for (status in listOf(RestoreStatus.FOREIGN, RestoreStatus.RESTORED)) {
            val terminal = receipt.copy(status = status)
            assertEquals(terminal, StudioGlowReceipts.refreshPane(listOf(terminal), writing).single())
        }
        assertFailsWith<IllegalArgumentException> { StudioGlowReceipts.refreshPane(emptyList(), writing) }
        assertFailsWith<IllegalArgumentException> { StudioGlowReceipts.refreshPane(listOf(receipt, receipt), writing) }
    }

    @Test
    fun `different process before and planned evidence cannot be mistaken for a completed restart`() {
        val original = StudioGlowReceipts.encodePane(identity, record(), "color", "color").single()
        val other = identity.copy(process = process.copy(pid = 43))
        val planned = StudioGlowReceipts.encodePane(other, record(), "color", "color").single()
        val corrupt = original.copy(lastWritten = planned.lastWritten)
        assertFailsWith<IllegalArgumentException> {
            StudioGlowReceipts.recoverLost(listOf(corrupt), StudioProcess(44, null))
        }
    }

    @Test
    fun `unknown recorded start stays unknown and only a different pid proves replacement`() {
        val unknown = identity.copy(process = process.copy(startedAt = null))
        val receipt = StudioGlowReceipts.encodePane(unknown, record(), "color", "color").single()
        assertEquals(
            receipt.copy(status = RestoreStatus.UNAVAILABLE),
            StudioGlowReceipts.recoverLost(listOf(receipt), process).single(),
        )
        assertEquals(
            receipt.copy(status = RestoreStatus.RESTORED),
            StudioGlowReceipts.recoverLost(listOf(receipt), StudioProcess(43, null)).single(),
        )
    }

    private fun record(): GlowPaneRecord =
        GlowPaneRecord(
            before,
            before.copy(intensity = 83),
            GlowCheckpointPhase.PREPARED,
            mapOf(GlowPaneField.INTENSITY to GlowFieldState.PREPARED),
        )

    private fun value(native: NativeValue): RawValue =
        ((native as NativeValue.Present).value as RawValue.OrderedMap).entries.single { it.key == "value" }.value
}

private class OpaqueReceiptColor : Color(0x123456) {
    override fun getRGB(): Int = error("Opaque color must not be evaluated")

    override fun equals(other: Any?): Boolean = error("Opaque color must not be compared")

    override fun hashCode(): Int = error("Opaque color must not be hashed")
}
