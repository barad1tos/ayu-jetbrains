package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.StudioList
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.xml.stream.XMLStreamException

internal sealed interface JournalDecodeResult {
    data class Ready(
        val journal: PendingJournal,
    ) : JournalDecodeResult

    data class FutureSchema(
        val schema: Int,
    ) : JournalDecodeResult

    data class Invalid(
        val issue: DecodeIssue,
    ) : JournalDecodeResult
}

internal object StudioJournalCodec {
    private const val ROOT = "journal"
    private const val SCHEMA = 2
    private const val ENCODING = "gzip-base64"

    fun encode(journal: PendingJournal): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).buffered().use { compressed ->
            StudioXml.write(StudioJournalXml.write(journal), compressed) { node ->
                if (node.name == "receipts") {
                    journal.receipts.asSequence().map(StudioReceiptXml::write)
                } else {
                    emptySequence()
                }
            }
        }
        val bytes = output.toByteArray()
        return StudioXml.write(
            StudioXmlNode(
                ROOT,
                mapOf(
                    "schema" to SCHEMA.toString(),
                    "revision" to journal.token.targetRevision.toString(),
                    "transaction" to StudioXml.encodeText(journal.token.transactionId),
                ),
                listOf(
                    StudioXmlNode(
                        "payload",
                        mapOf("sha256" to StudioXml.hashBytes(bytes), "encoding" to ENCODING),
                        text = StudioXml.encodeBytes(bytes),
                    ),
                ),
            ),
        )
    }

    fun decode(bytes: ByteArray): JournalDecodeResult {
        val receipts = mutableListOf<PortableReceipt>()
        return when (val result = read(bytes, receipts::add)) {
            is JournalDecodeResult.Ready -> result.copy(journal = result.journal.copy(receipts = StudioList(receipts)))
            else -> result
        }
    }

    /** Validate staged bytes without retaining a second copy of all receipt values. */
    fun isValid(bytes: ByteArray): Boolean = read(bytes) {} is JournalDecodeResult.Ready

    private fun read(
        bytes: ByteArray,
        consume: (PortableReceipt) -> Unit,
    ): JournalDecodeResult =
        try {
            readEnvelope(StudioXml.parseEnvelope(bytes, ROOT, SCHEMA), consume)
        } catch (_: XMLStreamException) {
            JournalDecodeResult.Invalid(DecodeIssue.MALFORMED)
        } catch (_: IllegalArgumentException) {
            JournalDecodeResult.Invalid(DecodeIssue.INVALID_STRUCTURE)
        } catch (_: NoSuchElementException) {
            JournalDecodeResult.Invalid(DecodeIssue.INVALID_STRUCTURE)
        } catch (_: IOException) {
            JournalDecodeResult.Invalid(DecodeIssue.MALFORMED)
        }

    private fun readEnvelope(
        envelope: StudioXmlEnvelope,
        consume: (PortableReceipt) -> Unit,
    ): JournalDecodeResult {
        require(envelope.name == ROOT)
        val schema = envelope.attribute("schema").toInt()
        if (schema > SCHEMA) return JournalDecodeResult.FutureSchema(schema)
        require(schema in 1..SCHEMA)
        val encoding = ENCODING.takeIf { schema == SCHEMA }
        val payload = envelope.checkedPayload(encoding) ?: return JournalDecodeResult.Invalid(DecodeIssue.CHECKSUM)
        val input = payload.bytes.inputStream()
        val journal =
            (if (encoding == null) input else GZIPInputStream(input)).use { stream ->
                val decoded = StudioJournalXml.read(stream, consume)
                require(stream.read() == -1) { "Payload was not fully consumed" }
                decoded
            }
        require(envelope.attribute("revision").toLong() == journal.token.targetRevision)
        require(StudioXml.decodeText(envelope.attribute("transaction")) == journal.token.transactionId)
        return JournalDecodeResult.Ready(journal)
    }
}
