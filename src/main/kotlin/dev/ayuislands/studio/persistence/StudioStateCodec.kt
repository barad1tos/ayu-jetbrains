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
    fun encode(document: StudioDocument): ByteArray =
        StudioXml.write(
            StudioXml.envelope(
                "ayu-studio",
                StudioDocumentXml.headerAttributes(document.header),
                StudioDocumentXml.writeDocument(document),
            ),
        )

    fun decode(bytes: ByteArray): DecodeResult =
        try {
            decodeDocument(StudioXml.parse(bytes))
        } catch (_: XMLStreamException) {
            DecodeResult.Invalid(DecodeIssue.MALFORMED)
        } catch (_: IllegalArgumentException) {
            DecodeResult.Invalid(DecodeIssue.INVALID_STRUCTURE)
        } catch (_: NoSuchElementException) {
            DecodeResult.Invalid(DecodeIssue.INVALID_STRUCTURE)
        } catch (_: CharacterCodingException) {
            DecodeResult.Invalid(DecodeIssue.MALFORMED)
        }

    private fun decodeDocument(envelope: StudioXmlNode): DecodeResult {
        require(envelope.name == "ayu-studio")
        val schema = envelope.attribute("schema").toInt()
        if (schema > 1) return DecodeResult.FutureSchema(schema)
        require(schema == 1)
        val payload = StudioXml.readCheckedPayload(envelope) ?: return DecodeResult.Invalid(DecodeIssue.CHECKSUM)
        val document = StudioDocumentXml.readDocument(StudioXml.parse(payload.bytes))
        require(envelope.attributes == StudioDocumentXml.headerAttributes(document.header))
        StudioDocumentValidation.validate(document)
        return DecodeResult.Ready(document, payload.digest)
    }
}
