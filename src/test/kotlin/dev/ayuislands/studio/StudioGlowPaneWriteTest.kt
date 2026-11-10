package dev.ayuislands.studio

import com.intellij.testFramework.junit5.RunInEdt
import dev.ayuislands.glow.GlowFailureCheckpoint
import dev.ayuislands.glow.GlowGlassPane
import dev.ayuislands.glow.GlowPaneCheckpoint
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.RouteOverlay
import dev.ayuislands.glow.RoutePlayback
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioGlowPaneWrite
import dev.ayuislands.studio.session.StudioGlowReferences
import io.mockk.every
import io.mockk.spyk
import io.mockk.unmockkAll
import java.awt.Color
import java.time.Instant
import javax.swing.JLayeredPane
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunInEdt
class StudioGlowPaneWriteTest {
    private val references = StudioGlowReferences("transaction", StudioProcess(42, Instant.EPOCH))

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `waveform healing keeps prepared evidence and restores the exact original failure`() {
        val pane = newPane()
        pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig())
        pane.failRouteWaveform(IllegalStateException("Original failure"))
        val failure = assertNotNull(pane.waveformFailure)
        val before = pane.captureAppearance()
        val changed = before.copy(waveform = before.waveform.copy(amplitude = before.waveform.amplitude + 1))
        val write = binding(pane, GlowPaneCheckpoint(pane, changed) { true })
        val original = write.receipts
        assertNull(write.playbackTarget.failure)
        assertSame(failure, pane.waveformFailure)
        try {
            pane.stopAnimation()
            write.apply()
            assertNull(pane.waveformFailure)
            assertSame(write.preparedEngine, pane.currentWaveformEngine)
            assertNotNull(write.preparedEngine)
            write.prepareRestore()
            val prepared = write.receipts
            assertEquals(original.size + 1, prepared.size)
            assertEvidence(original, prepared)
            assertNull(pane.waveformFailure)
            write.restore(emptyList())
            assertSame(failure, pane.waveformFailure)
            assertEquals(before, pane.captureAppearance())
            assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
            assertEvidence(prepared, write.receipts)
            write.prepareRestore()
            write.restore(emptyList())
            assertEquals(prepared.size, write.receipts.size)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `interrupted appearance write restores owned fields and preserves foreign fields on retry`() {
        val pane = spyk(newPane())
        val before = pane.captureAppearance()
        val write = binding(pane, GlowPaneCheckpoint(pane, before.copy(color = Color.ORANGE, intensity = 71)) { true })
        every { pane.repaint() } throws IllegalStateException("After native mutation")
        assertFailsWith<IllegalStateException> { write.apply() }
        assertSame(Color.ORANGE, pane.glowColor)
        pane.glowColor = Color.MAGENTA
        write.prepareRestore()
        write.restore(emptyList())
        val settled = write.receipts
        assertSame(Color.MAGENTA, pane.glowColor)
        assertEquals(before.intensity, pane.glowIntensity)
        assertEquals(RestoreStatus.FOREIGN, settled.single { it.identity.field == "glow/pane/COLOR" }.status)
        assertTrue(settled.all { it.status == RestoreStatus.RESTORED || it.status == RestoreStatus.FOREIGN })
        pane.glowColor = Color.ORANGE
        write.prepareRestore()
        write.restore(emptyList())
        assertSame(Color.ORANGE, pane.glowColor)
        assertEquals(settled, write.receipts)
    }

    @Test
    fun `newer color ownership blocks only its field while other writes settle`() {
        val pane = newPane()
        val before = pane.captureAppearance()
        val write = binding(pane, GlowPaneCheckpoint(pane, before.copy(color = Color.ORANGE, intensity = 71)) { true })
        write.apply()
        write.prepareRestore()
        val color = write.receipts.single { it.identity.field == "glow/pane/COLOR" }
        val newer = color.copy(id = "newer", identity = color.identity.copy(ownerToken = "newer"))
        assertFailsWith<IllegalStateException> { write.restore(listOf(newer)) }
        assertSame(Color.ORANGE, pane.glowColor)
        assertEquals(before.intensity, pane.glowIntensity)
        assertEquals(
            RestoreStatus.RESTORED,
            write.receipts.single { it.identity.field == "glow/pane/INTENSITY" }.status,
        )
        write.prepareRestore()
        write.restore(emptyList())
        assertSame(before.color, pane.glowColor)
        assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
    }

    @Test
    fun `shared references preserve native identity and route producer ownership`() {
        val pane = newPane()
        pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig())
        val write = binding(pane, null)
        val target = write.playbackTarget.overlay
        val route = RoutePlayback(pane.captureAppearance().waveform, listOf(target))
        val samePane = binding(pane, null)
        val replacement = binding(newPane(), null)
        val originalReceipt = write.receipts.single()
        val sharedReceipt = samePane.receipts.single()
        val replacementReceipt = replacement.receipts.single()
        assertEquals(originalReceipt.identity.resource, sharedReceipt.identity.resource)
        assertNotEquals(originalReceipt.id, sharedReceipt.id)
        assertEquals(originalReceipt.identity.field, sharedReceipt.identity.field)
        assertEquals(originalReceipt, write.receipts.single())
        assertTrue(originalReceipt.identity.resource != replacementReceipt.identity.resource)
        val firstColor = Color(10, 20, 30)
        val equalColor = Color(10, 20, 30)
        assertEquals(references.token(firstColor), references.token(firstColor))
        assertTrue(references.token(firstColor) != references.token(equalColor))
        try {
            write.recordRoute(route)
            pane.failRouteWaveform(IllegalStateException("Owned failure"), route)
            write.prepareRestore()
            assertFailsWith<IllegalStateException> { write.restore(listOf(sharedReceipt)) }
            assertNotNull(pane.waveformFailure)
            write.restore(emptyList())
            assertNull(pane.waveformFailure)
            assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
        } finally {
            pane.stopAnimation()
        }
    }

    private fun binding(
        pane: GlowGlassPane,
        change: GlowPaneCheckpoint?,
    ): StudioGlowPaneWrite =
        StudioGlowPaneWrite(
            references,
            RouteOverlay("Editor", pane, JPanel(), JLayeredPane()),
            change,
            GlowFailureCheckpoint(pane) { true },
        )

    private fun newPane(): GlowGlassPane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)

    private fun assertEvidence(
        before: List<PortableReceipt>,
        after: List<PortableReceipt>,
    ) {
        for (receipt in before) {
            assertEquals(receipt, after.single { it.id == receipt.id }.copy(status = receipt.status))
        }
    }
}
