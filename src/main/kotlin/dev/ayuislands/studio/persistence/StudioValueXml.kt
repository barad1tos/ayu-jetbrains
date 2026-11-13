package dev.ayuislands.studio.persistence

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
import dev.ayuislands.studio.model.requireKnownType

internal object StudioValueXml {
    private const val ORDERED_MAP = "ordered-map"
    private const val ORDERED_SET = "ordered-set"
    private const val ORDERED_LIST = "ordered-list"

    fun writeValue(value: RawValue): StudioXmlNode =
        when (value) {
            RawValue.Null -> scalar("null", "")
            is RawValue.Text -> scalar("text", StudioXml.encodeText(value.value))
            is RawValue.BooleanValue -> scalar("boolean", value.value.toString())
            is RawValue.Integer -> scalar("int", value.value.toString())
            is RawValue.LongValue -> scalar("long", value.value.toString())
            is RawValue.FloatBits -> scalar("float-bits", value.bits.toString())
            is RawValue.Bytes -> scalar("bytes", StudioXml.encodeBytes(value.value.toByteArray()))
            is RawValue.OrderedMap ->
                StudioXmlNode(
                    "value",
                    mapOf("type" to ORDERED_MAP),
                    value.entries.map { entry ->
                        StudioXmlNode(
                            "entry",
                            mapOf("key" to StudioXml.encodeText(entry.key)),
                            listOf(writeValue(entry.value)),
                        )
                    },
                )
            is RawValue.OrderedSet ->
                StudioXmlNode(
                    "value",
                    mapOf("type" to ORDERED_SET),
                    value.values.map { item ->
                        StudioXmlNode("item", text = StudioXml.encodeText(item))
                    },
                )
            is RawValue.OrderedList ->
                StudioXmlNode("value", mapOf("type" to ORDERED_LIST), value.values.map(::writeValue))
        }

    fun readValue(node: StudioXmlNode): RawValue {
        require(node.name == "value")
        val type = node.attribute("type")
        validateShape(node, type)
        return when (type) {
            "null" -> {
                require(node.text.isEmpty())
                RawValue.Null
            }
            "text" -> RawValue.Text(StudioXml.decodeText(node.text))
            "boolean" -> RawValue.BooleanValue(node.text.toBooleanStrict())
            "int" -> RawValue.Integer(node.text.toInt())
            "long" -> RawValue.LongValue(node.text.toLong())
            "float-bits" -> RawValue.FloatBits(node.text.toInt())
            "bytes" -> RawValue.Bytes(StudioBytes(StudioXml.decodeBytes(node.text)))
            ORDERED_MAP -> readMap(node)
            ORDERED_SET -> readSet(node)
            ORDERED_LIST -> RawValue.OrderedList(StudioList(node.children.map(::readValue)))
            else -> throw IllegalArgumentException("Unknown value type")
        }
    }

    fun writeFields(
        name: String,
        fields: StudioFields,
    ): StudioXmlNode =
        StudioXmlNode(name, children = fields.records.map(::writeField) + writeExtensions(fields.extensions))

    fun readFields(node: StudioXmlNode): StudioFields {
        node.requireShape(childNames = setOf("field", "extensions"))
        return StudioFields(
            StudioList(node.children.filter { it.name == "field" }.map(::readField)),
            readExtensions(node.child("extensions")),
        )
    }

    fun writeField(field: StudioField): StudioXmlNode =
        StudioXmlNode(
            "field",
            mapOf(
                "store" to field.key.source.name,
                "key" to StudioXml.encodeText(field.key.name),
                "presence" to
                    field.origin.presence.name
                        .lowercase(),
            ),
            listOf(writeValue(field.value)) +
                listOfNotNull(
                    field.origin.lexeme?.let { source ->
                        StudioXmlNode("lexeme", text = StudioXml.encodeBytes(source.toByteArray()))
                    },
                ),
        )

    fun readField(node: StudioXmlNode): StudioField {
        require(node.name == "field") { "Expected field element" }
        node.requireShape(setOf("store", "key", "presence"), setOf("value", "lexeme"))
        val lexeme =
            node
                .optionalChild("lexeme")
                ?.requireShape(allowsText = true)
                ?.text
                ?.let(StudioXml::decodeBytes)
        return StudioField(
            LegacyFieldKey(LegacyStore.valueOf(node.attribute("store")), StudioXml.decodeText(node.attribute("key"))),
            readValue(node.child("value")),
            FieldOrigin(SourcePresence.valueOf(node.attribute("presence").uppercase()), lexeme?.let(::StudioBytes)),
        ).also(StudioField::requireKnownType)
    }

    fun writeExtensions(records: StudioList<OpaqueRecord>): StudioXmlNode =
        StudioXmlNode(
            "extensions",
            children =
                records.map { (key, bytes) ->
                    StudioXmlNode(
                        "extension",
                        mapOf("key" to StudioXml.encodeText(key)),
                        text = StudioXml.encodeBytes(bytes.toByteArray()),
                    )
                },
        )

    fun readExtensions(node: StudioXmlNode): StudioList<OpaqueRecord> {
        require(node.name == "extensions") { "Expected extensions element" }
        node.requireShape(childNames = setOf("extension"))
        val records =
            node.children.map { child ->
                child.requireShape(setOf("key"), allowsText = true)
                OpaqueRecord(
                    StudioXml.decodeText(child.attribute("key")),
                    StudioBytes(StudioXml.decodeBytes(child.text)),
                )
            }
        require(records.map { it.key }.distinct().size == records.size) { "Duplicate extension identity" }
        return StudioList(records)
    }

    private fun validateShape(
        node: StudioXmlNode,
        type: String,
    ) {
        val childName =
            when (type) {
                ORDERED_MAP -> "entry"
                ORDERED_SET -> "item"
                ORDERED_LIST -> "value"
                else -> null
            }
        node.requireShape(setOf("type"), setOfNotNull(childName), allowsText = childName == null)
    }

    private fun scalar(
        type: String,
        text: String,
    ): StudioXmlNode = StudioXmlNode("value", mapOf("type" to type), text = text)

    private fun readMap(node: StudioXmlNode): RawValue.OrderedMap {
        val entries =
            node.children.map { child ->
                child.requireShape(setOf("key"), setOf("value"))
                RawValue.Entry(StudioXml.decodeText(child.attribute("key")), readValue(child.child("value")))
            }
        require(entries.map { it.key }.distinct().size == entries.size) { "Duplicate map key" }
        return RawValue.OrderedMap(StudioList(entries))
    }

    private fun readSet(node: StudioXmlNode): RawValue.OrderedSet {
        val items = node.children.map { child -> StudioXml.decodeText(child.requireShape(allowsText = true).text) }
        require(items.distinct().size == items.size) { "Duplicate set member" }
        return RawValue.OrderedSet(StudioList(items))
    }
}
