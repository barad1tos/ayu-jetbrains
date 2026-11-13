package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.StudioDocument
import java.nio.charset.CharacterCodingException
import javax.xml.stream.XMLStreamException

internal enum class DecodeIssue { MALFORMED, CHECKSUM, INVALID_STRUCTURE }

internal sealed interface DecodeResult {
    data class Ready(
        val document: StudioDocument,
        val digest: String,
    ) : DecodeResult

    data class FutureSchema(
        val schema: Int,
    ) : DecodeResult

    data class Invalid(
        val issue: DecodeIssue,
    ) : DecodeResult
}

internal object StudioStateCodec {
    private const val ROOT = "ayu-studio"
    private const val SCHEMA = 1

    fun encode(document: StudioDocument): ByteArray {
        StudioDocumentValidation.validate(document)
        return StudioXml.write(
            StudioXml.envelope(
                ROOT,
                StudioDocumentXml.headerAttributes(document.header),
                StudioDocumentXml.writeDocument(document),
            ),
        )
    }

    fun decode(bytes: ByteArray): DecodeResult =
        try {
            decodeDocument(StudioXml.parseEnvelope(bytes, ROOT, SCHEMA))
        } catch (_: XMLStreamException) {
            DecodeResult.Invalid(DecodeIssue.MALFORMED)
        } catch (_: IllegalArgumentException) {
            DecodeResult.Invalid(DecodeIssue.INVALID_STRUCTURE)
        } catch (_: NoSuchElementException) {
            DecodeResult.Invalid(DecodeIssue.INVALID_STRUCTURE)
        } catch (_: CharacterCodingException) {
            DecodeResult.Invalid(DecodeIssue.MALFORMED)
        }

    private fun decodeDocument(envelope: StudioXmlEnvelope): DecodeResult {
        require(envelope.name == ROOT)
        val schema = envelope.attribute("schema").toInt()
        if (schema > SCHEMA) return DecodeResult.FutureSchema(schema)
        require(schema == SCHEMA)
        val payload = envelope.checkedPayload() ?: return DecodeResult.Invalid(DecodeIssue.CHECKSUM)
        val document = StudioDocumentXml.readDocument(StudioXml.parse(payload.bytes))
        val header = StudioDocumentXml.headerAttributes(document.header)
        require(header.all { (name, value) -> envelope.attribute(name) == value }) {
            "Document header does not match envelope"
        }
        StudioDocumentValidation.validate(document)
        return DecodeResult.Ready(document, payload.digest)
    }
}
