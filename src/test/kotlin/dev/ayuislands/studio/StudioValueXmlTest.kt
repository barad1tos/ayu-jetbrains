package dev.ayuislands.studio

import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.StudioValueXml
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.StudioXmlNode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioValueXmlTest {
    @Test
    fun `raw value variants retain order exact bits and literal strings`() {
        val entries = listOf(RawValue.Entry("z", RawValue.Null), RawValue.Entry("a", RawValue.Text("")))
        val values =
            listOf(
                RawValue.Null,
                RawValue.Text(""),
                RawValue.Text($$"  ā \n $USER_HOME$  "),
                RawValue.BooleanValue(false),
                RawValue.Integer(Int.MIN_VALUE),
                RawValue.LongValue(Long.MAX_VALUE),
                RawValue.FloatBits((-0.0f).toRawBits()),
                RawValue.FloatBits(0x7fc01234),
                RawValue.Bytes(StudioBytes(byteArrayOf(0, -1, 32))),
                RawValue.OrderedMap(StudioList(entries)),
                RawValue.OrderedSet(StudioList(listOf("z", "a"))),
            )
        val original = RawValue.OrderedList(StudioList(values))

        val encoded = StudioXml.write(StudioValueXml.writeValue(original))
        val decoded = StudioValueXml.readValue(StudioXml.parse(encoded))

        assertEquals(original, decoded)
        assertContentEquals(encoded, StudioXml.write(StudioValueXml.writeValue(decoded)))
    }

    @Test
    fun `ordered fields preserve absent null explicit and opaque source data`() {
        val lexeme = StudioBytes(byteArrayOf(32, 9, 10))
        val records =
            listOf(
                field("lightAccent", RawValue.Text("#AABBCC"), FieldOrigin(SourcePresence.ABSENT)),
                field("darkAccent", RawValue.Null, FieldOrigin(SourcePresence.NULL, lexeme)),
                field("mirageAccent", RawValue.Text(""), FieldOrigin(SourcePresence.VALUE, lexeme)),
            )
        val opaque = listOf(OpaqueRecord("z", lexeme), OpaqueRecord("a", StudioBytes(byteArrayOf(-1))))
        val original = StudioFields(StudioList(records), StudioList(opaque))

        val encoded = StudioXml.write(StudioValueXml.writeFields("accent", original))

        assertEquals(original, StudioValueXml.readFields(StudioXml.parse(encoded)))
    }

    @Test
    fun `field codec rejects wrong known value shapes and unknown identities`() {
        val origin = FieldOrigin(SourcePresence.ABSENT)
        val wrong = field("lightAccent", RawValue.Integer(1), origin)
        val unknown = field("unknown", RawValue.Null, origin)
        val valid = StudioValueXml.writeField(field("lightAccent", RawValue.Null, origin))
        for (record in listOf(wrong, unknown)) {
            assertFailsWith<IllegalArgumentException> { StudioValueXml.writeField(record) }
            val encoded =
                StudioXmlNode(
                    "field",
                    valid.attributes + ("key" to StudioXml.encodeText(record.key.name)),
                    listOf(StudioValueXml.writeValue(record.value)),
                )
            assertFailsWith<IllegalArgumentException> { StudioValueXml.readField(encoded) }
        }
    }

    @Test
    fun `malformed raw shapes and duplicate identities fail instead of dropping entries`() {
        val key = StudioXml.encodeText("duplicate")
        val entry = "<entry key='$key'><value type='null'/></entry>"
        val documents =
            listOf(
                "<value type='null'>not-null</value>",
                "<value type='future'/>",
                "<value type='text'><item/></value>",
                "<value type='ordered-map'>$entry$entry</value>",
                "<value type='ordered-set'><item>$key</item><item>$key</item></value>",
            )
        for (source in documents) {
            val node = StudioXml.parse(source.encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { StudioValueXml.readValue(node) }
        }
        val extension = OpaqueRecord("duplicate", StudioBytes(byteArrayOf(1)))
        val child = StudioValueXml.writeExtensions(StudioList(listOf(extension))).child("extension")
        val duplicate = StudioXmlNode("extensions", children = listOf(child, child))
        assertFailsWith<IllegalArgumentException> { StudioValueXml.readExtensions(duplicate) }
    }

    @Test
    fun `writer rejects duplicate map set and extension identities before serialization`() {
        val entry = RawValue.Entry("duplicate", RawValue.Text("value"))
        val map = RawValue.OrderedMap(StudioList(listOf(entry, entry)))
        val set = RawValue.OrderedSet(StudioList(listOf("duplicate", "duplicate")))
        val extension = OpaqueRecord("duplicate", StudioBytes(byteArrayOf(1)))

        assertFailsWith<IllegalArgumentException> { StudioValueXml.writeValue(map) }
        assertFailsWith<IllegalArgumentException> { StudioValueXml.writeValue(set) }
        assertFailsWith<IllegalArgumentException> {
            StudioValueXml.writeExtensions(StudioList(listOf(extension, extension)))
        }
    }

    @Test
    fun `field codecs reject records with the wrong element identity`() {
        val record = field("lightAccent", RawValue.Null, FieldOrigin(SourcePresence.ABSENT))
        val fieldNode = StudioValueXml.writeField(record).copy(name = "wrong")
        val extensions = StudioValueXml.writeExtensions(StudioList(emptyList())).copy(name = "wrong")

        assertFailsWith<IllegalArgumentException> { StudioValueXml.readField(fieldNode) }
        assertFailsWith<IllegalArgumentException> { StudioValueXml.readExtensions(extensions) }
    }

    private fun field(
        name: String,
        value: RawValue,
        origin: FieldOrigin,
    ): StudioField = StudioField(LegacyFieldKey(LegacyStore.SETTINGS, name), value, origin)
}
