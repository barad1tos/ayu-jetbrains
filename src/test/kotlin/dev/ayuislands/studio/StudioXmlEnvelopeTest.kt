package dev.ayuislands.studio

import dev.ayuislands.studio.persistence.JournalDecodeResult
import dev.ayuislands.studio.persistence.StudioJournalCodec
import dev.ayuislands.studio.persistence.StudioXml
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioXmlEnvelopeTest {
    @Test
    fun `payload decoding preserves bytes across XML text boundaries`() {
        val payload = byteArrayOf(0, 1, 2, 3, 127, -1, -2)
        val encoded = StudioXml.encodeBytes(payload)
        for (split in 1 until encoded.length) {
            val text = encoded.take(split) + "<![CDATA[${encoded.drop(split)}]]>"
            val input = envelope(text, StudioXml.hashBytes(payload))
            val parsed = StudioXml.parseEnvelope(input, "journal")
            assertEquals("journal", parsed.node.name)
            assertEquals("1", parsed.node.attribute("schema"))
            assertContentEquals(payload, parsed.payload)
            assertContentEquals(
                payload,
                assertNotNull(StudioXml.readCheckedPayload(parsed.node, parsed.payload)).bytes,
            )
        }
    }

    @Test
    fun `streamed payload retains strict padding and unpadded tail semantics`() {
        for (invalid in listOf("AQ==<![CDATA[AA==]]>", "AQ== ", "A", "AQ=", "AQ==A")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                StudioXml.parseEnvelope(envelope(invalid, "unused"), "journal")
            }
        }
        for (valid in listOf("AQ", "AQI")) {
            val expected = StudioXml.decodeBytes(valid)
            val parsed = StudioXml.parseEnvelope(envelope(valid, StudioXml.hashBytes(expected)), "journal")
            assertContentEquals(expected, parsed.payload)
        }
    }

    @Test
    fun `decoded payload still requires its original checksum and exact envelope shape`() {
        val parsed = StudioXml.parseEnvelope(envelope("AQ==", "wrong"), "journal")
        assertNull(StudioXml.readCheckedPayload(parsed.node, parsed.payload))
        val extra = envelope("AQ==", "wrong").decodeToString().replace("</payload>", "<extra/></payload>")
        val malformed = StudioXml.parseEnvelope(extra.encodeToByteArray(), "journal")
        assertFailsWith<IllegalArgumentException> {
            StudioXml.readCheckedPayload(malformed.node, malformed.payload)
        }
    }

    @Test
    fun `supported schema spellings decode the same payload`() {
        for (schema in listOf("1", "01", "+1")) {
            val input =
                envelope("AQ==", StudioXml.hashBytes(byteArrayOf(1)))
                    .decodeToString()
                    .replace("schema=\"1\"", "schema=\"$schema\"")
                    .encodeToByteArray()
            val parsed = StudioXml.parseEnvelope(input, "journal")
            assertContentEquals(byteArrayOf(1), parsed.payload)
        }
    }

    @Test
    fun `unknown journal schema does not reinterpret its payload encoding`() {
        assertIs<JournalDecodeResult.FutureSchema>(
            StudioJournalCodec.decode(
                "<journal schema=\"3\"><payload>not-base64</payload></journal>".encodeToByteArray(),
            ),
        )
    }

    private fun envelope(
        text: String,
        checksum: String,
    ): ByteArray =
        (
            "<journal schema=\"1\" revision=\"0\" transaction=\"dGVzdA==\">" +
                "<payload sha256=\"$checksum\">$text</payload></journal>"
        ).encodeToByteArray()
}
