package dev.ayuislands.studio.persistence

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamWriter

/** Private transport tree. Values never escape the codec as preference authority. */
internal data class StudioXmlNode(
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
    val children: List<StudioXmlNode> = emptyList(),
    val text: String = "",
) {
    fun attribute(key: String): String = requireNotNull(attributes[key]) { "Missing attribute" }

    fun child(key: String): StudioXmlNode = children.single { it.name == key }

    fun optionalChild(key: String): StudioXmlNode? {
        val matches = children.filter { it.name == key }
        require(matches.size <= 1) { "Duplicate optional child" }
        return matches.singleOrNull()
    }

    fun requireShape(
        attributeNames: Set<String> = emptySet(),
        childNames: Set<String> = emptySet(),
        allowsText: Boolean = false,
    ): StudioXmlNode {
        require(attributes.keys == attributeNames) { "Unexpected attributes" }
        require(children.all { it.name in childNames }) { "Unexpected children" }
        require(allowsText || text.isBlank()) { "Unexpected text" }
        return this
    }
}

internal class CheckedXmlPayload(
    val bytes: ByteArray,
    val digest: String,
)

internal object StudioXml {
    private const val MAX_DEPTH = 128

    fun parse(bytes: ByteArray): StudioXmlNode = parseChildren(bytes, emptyList()) {}

    /** Reads envelope text incrementally; callers still validate schema, shape, and checksum. */
    fun parseEnvelope(
        bytes: ByteArray,
        name: String,
        maxSchema: Int = 1,
    ): StudioXmlEnvelope {
        val payload = StudioXmlPayload(name, bytes.size / BASE64_QUARTET * BASE64_BYTES, maxSchema)
        val node = parseContent(ByteArrayInputStream(bytes), emptyList(), {}, payload)
        return StudioXmlEnvelope(node, payload.finish())
    }

    /** Consumes direct children at one exact path while retaining their parent in the returned tree. */
    fun parseChildren(
        bytes: ByteArray,
        parentPath: List<String>,
        consume: (StudioXmlNode) -> Unit,
    ): StudioXmlNode = parseChildren(ByteArrayInputStream(bytes), parentPath, consume)

    /** The caller owns the input; only one consumed child is retained at a time. */
    fun parseChildren(
        input: InputStream,
        parentPath: List<String>,
        consume: (StudioXmlNode) -> Unit,
    ): StudioXmlNode = parseContent(input, parentPath, consume, null)

    private fun parseContent(
        input: InputStream,
        parentPath: List<String>,
        consume: (StudioXmlNode) -> Unit,
        payload: StudioXmlPayload?,
    ): StudioXmlNode {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.xmlResolver =
            javax.xml.stream.XMLResolver { _, _, _, _ ->
                throw XMLStreamException("External entities are not supported")
            }
        val reader = factory.createXMLStreamReader(input, "UTF-8")
        val frames = ArrayDeque<Frame>()
        var root: StudioXmlNode? = null
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        require(frames.size < MAX_DEPTH) { "XML nesting limit" }
                        require(reader.namespaceURI.isNullOrEmpty() && reader.namespaceCount == 0)
                        require(frames.isNotEmpty() || root == null) { "Multiple roots" }
                        val attributes = linkedMapOf<String, String>()
                        for (index in 0 until reader.attributeCount) {
                            require(reader.getAttributeNamespace(index).isNullOrEmpty())
                            attributes[reader.getAttributeLocalName(index)] = reader.getAttributeValue(index)
                        }
                        val hasChildConsumer =
                            frames.size + 1 == parentPath.size &&
                                frames.map { it.name } + reader.localName == parentPath
                        val textConsumer = payload?.consumer(frames.map { it.name } + reader.localName, attributes)
                        frames.addLast(
                            Frame(reader.localName, attributes, consume.takeIf { hasChildConsumer }, textConsumer),
                        )
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        val completed = frames.removeLast().build()
                        if (frames.isEmpty()) root = completed else frames.last().appendChild(completed)
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (frames.isEmpty()) require(reader.isWhiteSpace) else frames.last().appendText(reader.text)
                    }
                    XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
                        throw XMLStreamException("Entity declarations are not supported")
                }
            }
            return requireNotNull(root) { "Missing document" }
        } finally {
            reader.close()
        }
    }

    fun write(node: StudioXmlNode): ByteArray {
        val output = ByteArrayOutputStream()
        write(node, output)
        return output.toByteArray()
    }

    /** Appends supplied children lazily, without constructing the whole transport tree. */
    fun write(
        node: StudioXmlNode,
        output: OutputStream,
        extraChildren: (StudioXmlNode) -> Sequence<StudioXmlNode> = { emptySequence() },
    ) {
        val writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8")
        try {
            writer.writeStartDocument("UTF-8", "1.0")
            writeNode(writer, node, extraChildren)
            writer.writeEndDocument()
            writer.flush()
        } finally {
            writer.close()
        }
    }

    fun envelope(
        name: String,
        attributes: Map<String, String>,
        payload: StudioXmlNode,
    ): StudioXmlNode {
        val bytes = write(payload)
        return StudioXmlNode(
            name,
            attributes,
            listOf(StudioXmlNode("payload", mapOf("sha256" to hashBytes(bytes)), text = encodeBytes(bytes))),
        )
    }

    /** Null identifies a checksum mismatch; malformed structure still fails validation. */
    fun readCheckedPayload(
        envelope: StudioXmlNode,
        decoded: ByteArray? = null,
        encoding: String? = null,
    ): CheckedXmlPayload? {
        envelope.requireShape(setOf("schema", "revision", "transaction"), setOf("payload"))
        val attributes = if (encoding == null) setOf("sha256") else setOf("sha256", "encoding")
        val content = envelope.child("payload").requireShape(attributes, allowsText = true)
        if (encoding != null) require(content.attribute("encoding") == encoding)
        val bytes = decoded ?: decodeBytes(content.text)
        val digest = hashBytes(bytes)
        return if (digest == content.attribute("sha256")) CheckedXmlPayload(bytes, digest) else null
    }

    fun encodeBytes(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    fun decodeBytes(value: String): ByteArray = Base64.getDecoder().decode(value)

    fun encodeText(value: String): String = encodeBytes(value.encodeToByteArray(throwOnInvalidSequence = true))

    fun decodeText(value: String): String =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decodeBytes(value)))
            .toString()

    fun hashBytes(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private fun writeNode(
        writer: XMLStreamWriter,
        node: StudioXmlNode,
        extraChildren: (StudioXmlNode) -> Sequence<StudioXmlNode>,
    ) {
        writer.writeStartElement(node.name)
        for ((key, value) in node.attributes) writer.writeAttribute(key, value)
        if (node.text.isNotEmpty()) writer.writeCharacters(node.text)
        for (child in node.children) writeNode(writer, child, extraChildren)
        for (child in extraChildren(node)) writeNode(writer, child, extraChildren)
        writer.writeEndElement()
    }

    private class Frame(
        val name: String,
        private val attributes: Map<String, String>,
        private val consume: ((StudioXmlNode) -> Unit)?,
        private val consumeText: ((String) -> Unit)?,
    ) {
        val children: MutableList<StudioXmlNode> = mutableListOf()
        private val text: StringBuilder = StringBuilder()

        fun appendText(value: String) {
            val receiver = consumeText
            if (receiver == null) text.append(value) else receiver(value)
        }

        fun appendChild(node: StudioXmlNode) {
            val receiver = consume
            if (receiver == null) children.add(node) else receiver(node)
        }

        fun build(): StudioXmlNode = StudioXmlNode(name, attributes, children.toList(), text.toString())
    }

    private const val BASE64_QUARTET = 4
    private const val BASE64_BYTES = 3
}
