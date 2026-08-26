package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import io.mockk.mockk
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

            val failure = assertIs<SyntaxTransactionResult.Failed>(result)
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

        val failure = assertIs<SyntaxTransactionResult.Failed>(result)
        assertEquals("publish", failure.cause.message)
        assertEquals(listOf("Dark", "Mirage"), writer.rolledBack)
    }

    @Test
    fun `rollback failures are reported without abandoning later checkpoints`() {
        val first = change("Mirage", "JAVA_KEYWORD")
        val second = change("Dark", "KOTLIN_KEYWORD")
        val writer = RecordingWriter(failOn = "Dark", rollbackFailureOn = "Dark")

        val result = SyntaxSchemeTransaction(writer) {}.apply(listOf(first, second))

        val failure = assertIs<SyntaxTransactionResult.Failed>(result)
        assertEquals(listOf("rollback Dark"), failure.rollbackFailures.mapNotNull { it.message })
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
    fun `journal restores successful transactions in reverse order and publishes once`() {
        val writer = RecordingWriter()
        val journal = SyntaxSchemeJournal()
        var publishes = 0
        val transaction = SyntaxSchemeTransaction(writer) { publishes++ }

        transaction.apply(listOf(change("Mirage first", "JAVA_KEYWORD")), journal)
        transaction.apply(listOf(change("Mirage second", "KOTLIN_KEYWORD")), journal)
        publishes = 0

        val result = journal.restore(writer) { publishes++ }

        assertIs<SyntaxTransactionResult.Applied>(result)
        assertEquals(listOf("Mirage second", "Mirage first"), writer.rolledBack)
        assertEquals(1, publishes)
        assertEquals(emptyList(), writer.released)
    }

    @Test
    fun `advancing journal releases retained checkpoints without restoring`() {
        val writer = RecordingWriter()
        val journal = SyntaxSchemeJournal()

        SyntaxSchemeTransaction(writer) {}.apply(
            listOf(
                change("Mirage", "JAVA_KEYWORD"),
                change("Dark", "KOTLIN_KEYWORD"),
            ),
            journal,
        )
        journal.advance(writer)

        assertEquals(listOf("Mirage", "Dark"), writer.released)
        assertEquals(emptyList(), writer.rolledBack)
    }

    @Test
    fun `failed transaction rolls back its checkpoints without adding them to journal`() {
        val writer = RecordingWriter(failOn = "Dark")
        val journal = SyntaxSchemeJournal()
        val transaction = SyntaxSchemeTransaction(writer) {}
        transaction.apply(listOf(change("Mirage", "JAVA_KEYWORD")), journal)
        writer.failOn = "Dark"

        transaction.apply(listOf(change("Dark", "KOTLIN_KEYWORD")), journal)
        writer.rolledBack.clear()
        journal.restore(writer) {}

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
        private val rollbackFailureOn: String? = null,
        private val relinquishedByLabel: Map<String, Set<String>> = emptyMap(),
    ) : SyntaxSchemeWriter {
        val rolledBack = mutableListOf<String>()
        val released = mutableListOf<String>()

        override fun checkpoint(change: SyntaxSchemeChange): SyntaxSchemeCheckpoint =
            SyntaxSchemeCheckpoint(change.label)

        override fun write(change: SyntaxSchemeChange): Set<String> {
            check(change.label != failOn) { "write ${change.label}" }
            return relinquishedByLabel[change.label].orEmpty()
        }

        override fun rollback(checkpoint: SyntaxSchemeCheckpoint): List<RuntimeException> {
            rolledBack += checkpoint.label
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
