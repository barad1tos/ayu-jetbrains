package dev.ayuislands.studio

import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.StudioXmlNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `unknown envelope schema does not reinterpret its payload encoding`() {
        val input = "<journal schema=\"3\"><payload>not-base64</payload></journal>".encodeToByteArray()
        val parsed = StudioXml.parseEnvelope(input, "journal")
        assertEquals("3", parsed.node.attribute("schema"))
        assertNull(parsed.payload)
    }

    @Test
    fun `written envelope verifies exact UTF-8 payload bytes`() {
        val literal = $$"  ā & < $LITERAL$  "
        val payload = StudioXmlNode("settings", children = listOf(StudioXmlNode("text", text = literal)))
        val metadata = mapOf("schema" to "1", "revision" to "4", "transaction" to StudioXml.encodeText(literal))
        val envelope = StudioXml.envelope("ayu-studio", metadata, payload)

        val decoded = StudioXml.parseEnvelope(StudioXml.write(envelope), "ayu-studio")
        val checked = assertNotNull(StudioXml.readCheckedPayload(decoded.node, decoded.payload))

        assertEquals(literal, StudioXml.decodeText(decoded.node.attribute("transaction")))
        assertEquals(payload, StudioXml.parse(checked.bytes))
        assertEquals(StudioXml.hashBytes(checked.bytes), checked.digest)
        val changed = StudioXml.write(StudioXmlNode("settings", text = "changed"))
        assertNull(StudioXml.readCheckedPayload(decoded.node, changed))
    }

    @Test
    fun `streaming writes and reads retain encounter order without retaining consumed children`() {
        val header = StudioXmlNode("header")
        val list = StudioXmlNode("records")
        val entries = listOf(StudioXmlNode("record", text = "z"), StudioXmlNode("record", text = "a"))
        val root = StudioXmlNode("document", children = listOf(header, list))
        val bytes = ByteArrayOutputStream()
        StudioXml.write(root, bytes) { if (it == list) entries.asSequence() else emptySequence() }
        val records = mutableListOf<StudioXmlNode>()
        val input = ByteArrayInputStream(bytes.toByteArray())
        val parsed = StudioXml.parseChildren(input, listOf("document", "records"), records::add)

        assertEquals(entries, records)
        assertEquals(root, parsed)
        assertEquals(header, parsed.optionalChild("header"))
        assertNull(parsed.optionalChild("missing"))
    }

    @Test
    fun `checked payload cannot change through caller arrays`() {
        val original = byteArrayOf(0, 1, -1)
        val encoded = envelope(StudioXml.encodeBytes(original), StudioXml.hashBytes(original))
        val parsed = StudioXml.parseEnvelope(encoded, "journal")
        val supplied = assertNotNull(parsed.payload)
        val checked = assertNotNull(StudioXml.readCheckedPayload(parsed.node, supplied))

        supplied.fill(42)
        checked.bytes.fill(12)

        assertContentEquals(original, parsed.payload)
        assertContentEquals(original, checked.bytes)
        assertEquals(StudioXml.hashBytes(checked.bytes), checked.digest)
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
