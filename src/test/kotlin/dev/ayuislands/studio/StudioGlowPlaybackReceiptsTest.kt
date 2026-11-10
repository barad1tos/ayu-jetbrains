package dev.ayuislands.studio

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPlaybackRequest
import dev.ayuislands.glow.GlowPlaybackTarget
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.PlaybackEnvironment
import dev.ayuislands.glow.RouteOverlay
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.GlowPlaybackSurface
import dev.ayuislands.studio.session.GlowPlaybackTokens
import dev.ayuislands.studio.session.GlowReceiptIdentity
import dev.ayuislands.studio.session.StudioGlowPlaybackReceipts
import dev.ayuislands.studio.session.StudioGlowReceipts
import io.mockk.mockk
import java.awt.Color
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class StudioGlowPlaybackReceiptsTest {
    private val process = StudioProcess(42, Instant.parse("2026-11-06T12:00:00Z"))
    private val identity = GlowReceiptIdentity("manager-a", "operation-a", process)
    private val surfaces =
        listOf(
            GlowPlaybackSurface("Terminal", "pane-a", "host-a", "layer-a"),
            GlowPlaybackSurface("Editor", "pane-b", "host-b", "layer-a"),
        )
    private val waveform = WaveformConfig(loopSeconds = Float.fromBits(0x7fc01234))
    private val appearance =
        GlowPaneAppearance(
            Color.ORANGE,
            GlowStyle.SOFT,
            71,
            11,
            GlowPlacement.ISLAND,
            GlowShape.WAVEFORM,
            waveform,
            isVisible = false,
        )
    private val environment = PlaybackEnvironment("Editor", isApplicationActive = false, isPowerSaveEnabled = true)

    @Test
    fun `baseline preserves actual animation nullable route and ordered native surfaces`() {
        val plain = StudioGlowPlaybackReceipts.baseline(GlowAnimation.NONE, null, surfaces)
        val route = StudioGlowPlaybackReceipts.baseline(GlowAnimation.NONE, waveform, surfaces)
        assertEquals(RawValue.Null, field(plain, "route"))
        assertEquals(RawValue.FloatBits(0x7fc01234), field(field(route, "route"), "LOOP_SECONDS"))
        val captured = (field(route, "targets") as RawValue.OrderedList).values
        assertEquals(listOf(RawValue.Text("Terminal"), RawValue.Text("Editor")), captured.map { field(it, "id") })
        assertEquals(RawValue.Text("pane-a"), field(captured.first(), "pane"))
        assertEquals(RawValue.Text("layer-a"), field(captured.last(), "layer"))
        assertNotEquals(route, StudioGlowPlaybackReceipts.baseline(GlowAnimation.NONE, waveform, surfaces.reversed()))
        assertNotEquals(
            StudioGlowPlaybackReceipts.baseline(GlowAnimation.NONE, null, emptyList()),
            StudioGlowPlaybackReceipts.baseline(GlowAnimation.NONE, waveform, emptyList()),
        )
    }

    @Test
    fun `activation records all prepared values without reading native objects`() {
        val intent = intent()
        val tokens = surfaces.map { GlowPlaybackTokens(it, "color-${it.pane}", null) }
        val captured = StudioGlowPlaybackReceipts.activation(intent, tokens)
        val targets = (field(captured, "targets") as RawValue.OrderedList).values
        val pane = field(targets.first(), "appearance") as RawValue.OrderedMap
        assertEquals(GlowPaneField.entries.map { it.name }, pane.entries.map { it.key })
        assertEquals(RawValue.Text("color-pane-a"), field(pane, "COLOR"))
        assertEquals(RawValue.FloatBits(0x7fc01234), field(pane, "LOOP_SECONDS"))
        assertEquals(RawValue.BooleanValue(false), field(pane, "VISIBILITY"))
        assertEquals(RawValue.Null, field(targets.first(), "failure"))
        val capturedEnvironment = field(captured, "environment")
        assertEquals(RawValue.Text("Editor"), field(capturedEnvironment, "focused"))
        assertEquals(RawValue.BooleanValue(false), field(capturedEnvironment, "active"))
        assertEquals(RawValue.BooleanValue(true), field(capturedEnvironment, "powerSave"))
        assertFailsWith<IllegalArgumentException> { StudioGlowPlaybackReceipts.activation(intent, tokens.reversed()) }
        assertFailsWith<IllegalArgumentException> { StudioGlowPlaybackReceipts.activation(intent, tokens.take(1)) }
    }

    @Test
    fun `forward cleanup alone never claims the previous playback has resumed`() {
        val original = original()
        for (phase in GlowCheckpointPhase.entries) {
            val expected =
                if (phase in setOf(GlowCheckpointPhase.APPLIED, GlowCheckpointPhase.ACCEPTED)) {
                    RestoreStatus.OWNED
                } else {
                    RestoreStatus.PENDING
                }
            val refreshed = StudioGlowPlaybackReceipts.refreshOriginal(original, phase, isRestored = false)
            assertEquals(expected, refreshed.status)
            assertEquals(original, refreshed.copy(status = original.status))
            assertEquals(
                original.copy(status = RestoreStatus.RESTORED),
                StudioGlowPlaybackReceipts.refreshOriginal(original, phase, isRestored = true),
            )
        }
    }

    @Test
    fun `rollback attempts retain fresh intent and settle only their own cleanup or handoff`() {
        val original = original()
        val tokens = surfaces.map { GlowPlaybackTokens(it, "color-${it.pane}", null) }
        val changed = intent(environment.copy(isPowerSaveEnabled = false))
        val attempt =
            StudioGlowPlaybackReceipts.encodeRestore(
                identity,
                0,
                StudioGlowPlaybackReceipts.activation(changed, tokens),
            )
        assertEquals("${original.id}/restore/0", attempt.id)
        assertEquals(original.identity, attempt.identity)
        assertEquals(RawValue.Text(original.id), field(value(attempt.before), "source"))
        assertEquals(RawValue.BooleanValue(true), field(value(attempt.before), "paused"))
        assertNotEquals(original.lastWritten, attempt.lastWritten)
        for (phase in GlowCheckpointPhase.entries) {
            val expected =
                if (phase in setOf(GlowCheckpointPhase.RESTORED, GlowCheckpointPhase.ACCEPTED)) {
                    RestoreStatus.RESTORED
                } else {
                    RestoreStatus.PENDING
                }
            val refreshed = StudioGlowPlaybackReceipts.refreshRestore(attempt, phase)
            assertEquals(attempt.copy(status = expected), refreshed)
        }
        assertFailsWith<IllegalArgumentException> {
            StudioGlowPlaybackReceipts.encodeRestore(identity, -1, RawValue.Null)
        }
    }

    @Test
    fun `lost playback settles only on proven process replacement including restoration attempts`() {
        val original = original()
        val attempt = StudioGlowPlaybackReceipts.encodeRestore(identity, 0, RawValue.Null)
        val receipts = listOf(original, attempt)
        assertEquals(
            receipts.map { it.copy(status = RestoreStatus.UNAVAILABLE) },
            StudioGlowReceipts.recoverLost(receipts, process),
        )
        assertEquals(
            receipts.map { it.copy(status = RestoreStatus.RESTORED) },
            StudioGlowReceipts.recoverLost(receipts, process.copy(pid = 43)),
        )
    }

    @Test
    fun `terminal evidence is sticky and the two receipt roles cannot be interchanged`() {
        val original = original()
        val attempt = StudioGlowPlaybackReceipts.encodeRestore(identity, 0, RawValue.Null)
        for (status in listOf(RestoreStatus.RESTORED, RestoreStatus.FOREIGN)) {
            assertEquals(
                original.copy(status = status),
                StudioGlowPlaybackReceipts.refreshOriginal(original.copy(status = status), null, isRestored = false),
            )
            assertEquals(
                attempt.copy(status = status),
                StudioGlowPlaybackReceipts.refreshRestore(attempt.copy(status = status), GlowCheckpointPhase.PREPARED),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StudioGlowPlaybackReceipts.refreshOriginal(attempt, null, isRestored = false)
        }
        assertFailsWith<IllegalArgumentException> {
            StudioGlowPlaybackReceipts.refreshRestore(original, GlowCheckpointPhase.ACCEPTED)
        }
    }

    private fun original(): PortableReceipt {
        val tokens = surfaces.map { GlowPlaybackTokens(it, "color-${it.pane}", null) }
        return StudioGlowPlaybackReceipts.encode(
            identity,
            StudioGlowPlaybackReceipts.baseline(GlowAnimation.NONE, null, surfaces),
            StudioGlowPlaybackReceipts.activation(intent(), tokens),
        )
    }

    private fun intent(observed: PlaybackEnvironment = environment): GlowPlaybackRequest.Activate =
        GlowPlaybackRequest.Activate(
            GlowAnimation.NONE,
            waveform,
            surfaces.map { surface ->
                GlowPlaybackTarget(RouteOverlay(surface.id, mockk(), mockk(), mockk()), appearance, null)
            },
            observed,
        )

    private fun value(native: NativeValue): RawValue = field((native as NativeValue.Present).value, "value")

    private fun field(
        value: RawValue,
        name: String,
    ): RawValue = (value as RawValue.OrderedMap).entries.single { it.key == name }.value
}
