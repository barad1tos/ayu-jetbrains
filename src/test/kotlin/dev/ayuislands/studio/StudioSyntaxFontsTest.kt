package dev.ayuislands.studio

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioNativeEvidence
import dev.ayuislands.studio.session.StudioNativeTokens
import dev.ayuislands.studio.session.StudioSyntaxFonts
import dev.ayuislands.syntax.SyntaxFontState
import java.awt.Font
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSyntaxFontsTest {
    private val process = StudioProcess(42, Instant.EPOCH)
    private val state = SyntaxFontState()

    @Test
    fun `capture preserves raw semantic styles and distinguishes equal snapshot identities`() {
        val styles = linkedMapOf("Kotlin" to linkedMapOf("KEYWORD" to Font.BOLD, "FUTURE" to 37))
        state.replace(styles)
        val original = state.current
        val operation = capture(styles)
        val receipt = operation.receipts.single()

        assertSame(original, state.current)
        assertEquals(EffectOwner.SYNTAX, receipt.owner)
        assertEquals("attempt", receipt.identity.ownerToken)
        assertEquals(RestoreStatus.PENDING, receipt.status)
        assertEquals(process, StudioNativeEvidence.readProcess(receipt.before))
        assertEquals(process, StudioNativeEvidence.readProcess(receipt.lastWritten))
        val before = payload(receipt.before)
        val planned = payload(receipt.lastWritten)
        assertNotEquals(before.getValue("reference"), planned.getValue("reference"))
        assertEquals(before.getValue("styles"), planned.getValue("styles"))
        val languages = StudioNativeEvidence.fields(planned.getValue("styles"))
        val categories = StudioNativeEvidence.fields(languages.getValue("Kotlin"))
        assertEquals(listOf("KEYWORD", "FUTURE"), categories.keys.toList())
        assertEquals(RawValue.Integer(37), categories.getValue("FUTURE"))
    }

    @Test
    fun `apply and rollback change only receipt progress and restore the exact original styles`() {
        state.replace(fonts(Font.BOLD))
        val original = state.current
        val operation = capture(fonts(Font.ITALIC))
        val captured = operation.receipts.single()

        assertTrue(operation.apply())
        assertEquals(fonts(Font.ITALIC), state.current.styles)
        assertEquals(captured, operation.receipts.single().copy(status = RestoreStatus.PENDING))
        assertEquals(RestoreStatus.OWNED, operation.receipts.single().status)
        assertTrue(operation.restore(StudioList(emptyList())))
        assertSame(original, state.current)
        assertEquals(RestoreStatus.RESTORED, operation.receipts.single().status)
        assertEquals(captured, operation.receipts.single().copy(status = RestoreStatus.PENDING))
    }

    @Test
    fun `a newer unresolved write blocks rollback by field regardless of owner or token`() {
        val operation = capture(fonts(Font.BOLD))
        assertTrue(operation.apply())
        val applied = state.current
        val captured = operation.receipts.single()
        val newer =
            captured.copy(
                id = "newer",
                owner = EffectOwner.FONT,
                identity = captured.identity.copy(ownerToken = "newer-attempt"),
            )

        assertFalse(operation.restore(StudioList(listOf(newer))))
        assertSame(applied, state.current)
        assertEquals(RestoreStatus.OWNED, operation.receipts.single().status)
        assertTrue(operation.restore(StudioList(listOf(newer.copy(status = RestoreStatus.FOREIGN)))))
        assertEquals(RestoreStatus.RESTORED, operation.receipts.single().status)
    }

    @Test
    fun `foreign styles settle as preserved and a duplicate rollback never replays`() {
        val operation = capture(fonts(Font.BOLD))
        assertTrue(operation.apply())
        state.replace(fonts(Font.BOLD))
        val foreign = state.current

        assertTrue(operation.restore(StudioList(emptyList())))
        assertSame(foreign, state.current)
        assertEquals(RestoreStatus.FOREIGN, operation.receipts.single().status)
        state.replace(fonts(Font.ITALIC))
        val later = state.current
        assertTrue(operation.restore(StudioList(emptyList())))
        assertSame(later, state.current)
    }

    @Test
    fun `accept retains accepted styles and cannot authorize a later rollback`() {
        val operation = capture(fonts(Font.BOLD))
        assertTrue(operation.apply())
        val accepted = state.current

        operation.accept()
        operation.accept()

        assertSame(accepted, state.current)
        assertEquals(RestoreStatus.OWNED, operation.receipts.single().status)
        assertFailsWith<IllegalStateException> { operation.restore(StudioList(emptyList())) }
        assertSame(accepted, state.current)
    }

    @Test
    fun `lost semantic handles settle only after their process has ended`() {
        val operation = capture(fonts(Font.BOLD))
        assertTrue(operation.apply())
        val receipt = operation.receipts.single()
        val retained = state.current

        for (current in listOf(process, null, StudioProcess(process.pid, null))) {
            assertEquals(
                receipt.copy(status = RestoreStatus.UNAVAILABLE),
                StudioSyntaxFonts.recoverLost(listOf(receipt), current).single(),
            )
        }
        for (current in listOf(
            StudioProcess(43, Instant.EPOCH),
            process.copy(startedAt = Instant.EPOCH.plusSeconds(1)),
        )) {
            assertEquals(
                receipt.copy(status = RestoreStatus.RESTORED),
                StudioSyntaxFonts.recoverLost(listOf(receipt), current).single(),
            )
        }
        assertSame(retained, state.current)
    }

    @Test
    fun `lost semantic recovery preserves terminal receipts and rejects persistent syntax fields`() {
        val receipt = capture(fonts(Font.BOLD)).receipts.single()
        val terminal = listOf(RestoreStatus.RESTORED, RestoreStatus.FOREIGN).map { receipt.copy(status = it) }

        assertEquals(terminal, StudioSyntaxFonts.recoverLost(terminal, null).toList())
        assertFailsWith<IllegalArgumentException> {
            StudioSyntaxFonts.recoverLost(
                listOf(receipt.copy(identity = receipt.identity.copy(field = "attributes/KEYWORD/VALUE"))),
                StudioProcess(43, Instant.EPOCH),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StudioSyntaxFonts.recoverLost(listOf(receipt.copy(owner = EffectOwner.VCS)), process)
        }
    }

    private fun capture(styles: Map<String, Map<String, Int>>): StudioSyntaxFonts =
        StudioSyntaxFonts(state.prepare(styles), "attempt", process, StudioNativeTokens("native"))

    private fun fonts(style: Int): Map<String, Map<String, Int>> = mapOf("Kotlin" to mapOf("KEYWORD" to style))

    private fun payload(value: NativeValue): Map<String, RawValue> {
        val envelope = StudioNativeEvidence.fields((value as NativeValue.Present).value)
        return StudioNativeEvidence.fields(envelope.getValue("value"))
    }
}
