package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProcessCanceledException
import io.mockk.mockk
import java.awt.Color
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertSame

class SyntaxSchemeTransactionTest {
    @Test
    fun `first middle and last write failures roll back every checkpoint and publish nothing`() {
        val first = change("Mirage", "JAVA_KEYWORD")
        val second = change("Dark", "KOTLIN_KEYWORD")
        val third = change("Light", "SWIFT.KEYWORD")
        listOf("Mirage", "Dark", "Light").forEach { failedLabel ->
            val writer = RecordingWriter(failOn = failedLabel)
            var publishes = 0

            val result = SyntaxSchemeTransaction(writer) { publishes++ }.apply(listOf(first, second, third))

            val failure = assertIs<SyntaxTransactionResult.RolledBack>(result)
            assertEquals("write $failedLabel", failure.cause.message)
            assertEquals(listOf("Light", "Dark", "Mirage"), writer.rolledBack)
            assertEquals(0, publishes)
        }
    }

    @Test
    fun `publisher failure rolls back every checkpoint`() {
        val first = change("Mirage", "JAVA_KEYWORD")
        val second = change("Dark", "KOTLIN_KEYWORD")
        val writer = RecordingWriter()

        val result =
            SyntaxSchemeTransaction(writer) {
                throw IllegalStateException("publish")
            }.apply(listOf(first, second))

        val failure = assertIs<SyntaxTransactionResult.RolledBack>(result)
        assertEquals("publish", failure.cause.message)
        assertEquals(listOf("Dark", "Mirage"), writer.rolledBack)
    }

    @Test
    fun `publisher failure remains recoverable after every checkpoint rolls back`() {
        val writer = RecordingWriter()
        val ledger = SyntaxRecoveryLedger()
        val failure = IllegalStateException("publish")
        var publishes = 0

        val failed =
            SyntaxSchemeTransaction(writer) {
                publishes++
                throw failure
            }.apply(listOf(change("Mirage", "JAVA_KEYWORD")), ledger)
        val recovered = ledger.restore(writer) { publishes++ }

        assertSame(failure, assertIs<SyntaxTransactionResult.RolledBack>(failed).cause)
        assertIs<SyntaxTransactionResult.Applied>(recovered)
        assertEquals(2, publishes)
        assertEquals(listOf("Mirage"), writer.rolledBack)
    }

    @Test
    fun `rollback failures are reported without abandoning later checkpoints`() {
        val first = change("Mirage", "JAVA_KEYWORD")
        val second = change("Dark", "KOTLIN_KEYWORD")
        val writer = RecordingWriter(failOn = "Dark", rollbackFailureOn = "Dark")

        val result = SyntaxSchemeTransaction(writer) {}.apply(listOf(first, second))

        val failure = assertIs<SyntaxTransactionResult.RecoveryRequired>(result)
        assertEquals(listOf("rollback Dark"), failure.rollbackFailures.mapNotNull { it.message })
        assertEquals(listOf("Dark", "Mirage"), writer.rolledBack)
    }

    @Test
    fun `failed rollback checkpoint remains restorable through the ledger`() {
        val writer = RecordingWriter(failOn = "Dark", rollbackFailureOn = "Dark")
        val ledger = SyntaxRecoveryLedger()

        SyntaxSchemeTransaction(writer) {}.apply(listOf(change("Dark", "KOTLIN_KEYWORD")), ledger)
        writer.rollbackFailureOn = null
        writer.rolledBack.clear()

        ledger.restore(writer) {}

        assertEquals(listOf("Dark"), writer.rolledBack)
    }

    @Test
    fun `thrown rollback failure retains its checkpoint and continues restoration`() {
        val writer = RecordingWriter(failOn = "Light", rollbackExceptionOn = "Dark")
        val ledger = SyntaxRecoveryLedger()

        val result =
            SyntaxSchemeTransaction(writer) {}.apply(
                listOf(
                    change("Mirage", "JAVA_KEYWORD"),
                    change("Dark", "KOTLIN_KEYWORD"),
                    change("Light", "SWIFT.KEYWORD"),
                ),
                ledger,
            )

        val failure = assertIs<SyntaxTransactionResult.RecoveryRequired>(result)
        assertEquals(listOf("rollback Dark"), failure.rollbackFailures.mapNotNull { it.message })
        assertEquals(listOf("Light", "Dark", "Mirage"), writer.rolledBack)

        writer.rollbackExceptionOn = null
        writer.rolledBack.clear()
        ledger.restore(writer) {}

        assertEquals(listOf("Dark"), writer.rolledBack)
    }

    @Test
    fun `cancellation rolls back every checkpoint before rethrowing the original instance`() {
        listOf(
            ProcessCanceledException(),
            CancellationException("cancelled"),
        ).forEach { cancellation ->
            val first = change("Mirage", "JAVA_KEYWORD")
            val second = change("Dark", "KOTLIN_KEYWORD")
            val writer = RecordingWriter()

            val thrown =
                assertFails {
                    SyntaxSchemeTransaction(writer) { throw cancellation }.apply(listOf(first, second))
                }

            assertSame(cancellation, thrown)
            assertEquals(listOf("Dark", "Mirage"), writer.rolledBack)
        }
    }

    @Test
    fun `ledger cancellation finishes rollback before rethrowing the original instance`() {
        val writer = RecordingWriter()
        val ledger = SyntaxRecoveryLedger()
        val transaction = SyntaxSchemeTransaction(writer) {}
        transaction.apply(listOf(change("Mirage", "JAVA_KEYWORD")), ledger)
        transaction.apply(listOf(change("Dark", "KOTLIN_KEYWORD")), ledger)
        val cancellation = ProcessCanceledException()
        writer.rollbackCancellationOn = "Dark"
        writer.rollbackCancellation = cancellation
        writer.rolledBack.clear()

        val thrown = assertFails { ledger.restore(writer) {} }

        assertSame(cancellation, thrown)
        assertEquals(listOf("Dark", "Mirage"), writer.rolledBack)
    }

    @Test
    fun `successful transaction publishes once and reports changed and relinquished keys`() {
        val first = change("Mirage", "JAVA_KEYWORD")
        val second = change("Dark", "KOTLIN_KEYWORD")
        val writer = RecordingWriter(relinquishedByLabel = mapOf("Dark" to setOf("KOTLIN_KEYWORD")))
        var publishes = 0

        val result = SyntaxSchemeTransaction(writer) { publishes++ }.apply(listOf(first, second))

        val applied = assertIs<SyntaxTransactionResult.Applied>(result)
        assertEquals(setOf("JAVA_KEYWORD", "KOTLIN_KEYWORD"), applied.changedKeys)
        assertEquals(setOf("KOTLIN_KEYWORD"), applied.relinquishedKeys)
        assertEquals(1, publishes)
        assertEquals(emptyList(), writer.rolledBack)
    }

    @Test
    fun `ledger restores successful transactions in reverse order and publishes once`() {
        val writer = RecordingWriter()
        val ledger = SyntaxRecoveryLedger()
        var publishes = 0
        val transaction = SyntaxSchemeTransaction(writer) { publishes++ }

        transaction.apply(listOf(change("Mirage first", "JAVA_KEYWORD")), ledger)
        transaction.apply(listOf(change("Mirage second", "KOTLIN_KEYWORD")), ledger)
        publishes = 0

        val result = ledger.restore(writer) { publishes++ }

        assertIs<SyntaxTransactionResult.Applied>(result)
        assertEquals(listOf("Mirage second", "Mirage first"), writer.rolledBack)
        assertEquals(1, publishes)
        assertEquals(emptyList(), writer.released)
    }

    @Test
    fun `advancing ledger releases retained checkpoints without restoring`() {
        val writer = RecordingWriter()
        val ledger = SyntaxRecoveryLedger()

        SyntaxSchemeTransaction(writer) {}.apply(
            listOf(
                change("Mirage", "JAVA_KEYWORD"),
                change("Dark", "KOTLIN_KEYWORD"),
            ),
            ledger,
        )
        ledger.advance(writer)

        assertEquals(listOf("Mirage", "Dark"), writer.released)
        assertEquals(emptyList(), writer.rolledBack)
    }

    @Test
    fun `advancing ledger refuses to release an incomplete rollback checkpoint`() {
        val writer = RecordingWriter(failOn = "Dark", rollbackFailureOn = "Dark")
        val ledger = SyntaxRecoveryLedger()

        SyntaxSchemeTransaction(writer) {}.apply(
            listOf(change("Dark", "KOTLIN_KEYWORD")),
            ledger,
        )

        assertFails { ledger.advance(writer) }
        assertEquals(emptyList(), writer.released)
    }

    @Test
    fun `failed transaction rolls back its checkpoints without adding them to ledger`() {
        val writer = RecordingWriter(failOn = "Dark")
        val ledger = SyntaxRecoveryLedger()
        val transaction = SyntaxSchemeTransaction(writer) {}
        transaction.apply(listOf(change("Mirage", "JAVA_KEYWORD")), ledger)
        writer.failOn = "Dark"

        transaction.apply(listOf(change("Dark", "KOTLIN_KEYWORD")), ledger)
        writer.rolledBack.clear()
        ledger.restore(writer) {}

        assertEquals(listOf("Mirage"), writer.rolledBack)
    }

    private fun change(
        label: String,
        keyName: String,
    ): SyntaxSchemeChange {
        val key = TextAttributesKey.find(keyName)
        val attributes = TextAttributes().also { it.foregroundColor = Color.ORANGE }
        return SyntaxSchemeChange(
            scheme = mockk<EditorColorsScheme>(relaxed = true),
            label = label,
            attributes = mapOf(key to attributes),
            materializedKeys = emptySet(),
        )
    }

    private class RecordingWriter(
        var failOn: String? = null,
        var rollbackFailureOn: String? = null,
        var rollbackExceptionOn: String? = null,
        private val relinquishedByLabel: Map<String, Set<String>> = emptyMap(),
    ) : SyntaxSchemeWriter {
        val rolledBack = mutableListOf<String>()
        val released = mutableListOf<String>()
        var rollbackCancellationOn: String? = null
        var rollbackCancellation: RuntimeException? = null

        override fun checkpoint(change: SyntaxSchemeChange): SyntaxSchemeCheckpoint =
            SyntaxSchemeCheckpoint(change.label)

        override fun write(change: SyntaxSchemeChange): Set<String> {
            check(change.label != failOn) { "write ${change.label}" }
            return relinquishedByLabel[change.label].orEmpty()
        }

        override fun rollback(checkpoint: SyntaxSchemeCheckpoint): List<RuntimeException> {
            rolledBack += checkpoint.label
            if (checkpoint.label == rollbackCancellationOn) {
                throw checkNotNull(rollbackCancellation)
            }
            if (checkpoint.label == rollbackExceptionOn) {
                error("rollback ${checkpoint.label}")
            }
            return if (checkpoint.label == rollbackFailureOn) {
                listOf(RuntimeException("rollback ${checkpoint.label}"))
            } else {
                emptyList()
            }
        }

        override fun release(checkpoint: SyntaxSchemeCheckpoint) {
            released += checkpoint.label
        }
    }
}
