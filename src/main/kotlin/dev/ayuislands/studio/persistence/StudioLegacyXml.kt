package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.StudioBytes
import org.jdom.Element

/** Validated semantics and original spelling are independent migration evidence. */
internal data class LegacyXmlElement(
    val node: StudioXmlNode,
    val lexeme: StudioBytes,
    val children: List<LegacyXmlElement>,
) {
    fun toElement(): Element = node.toElement()
}

private fun StudioXmlNode.toElement(): Element =
    Element(name).also { element ->
        attributes.forEach { (key, value) -> element.setAttribute(key, value) }
        for (item in content) {
            when (item) {
                is StudioXmlContent.Text -> element.addContent(item.value)
                is StudioXmlContent.Child -> element.addContent(item.node.toElement())
            }
        }
    }

/** Security validation precedes lexical slicing; this reader never interprets XML values. */
internal object StudioLegacyXml {
    fun component(
        bytes: ByteArray,
        name: String,
    ): LegacyXmlElement {
        val root = StudioXml.parse(bytes)
        require(root.name == "application") { "Missing native application envelope" }
        val source = bytes.decodeToString(throwOnInvalidSequence = true)
        val ranges = LexemeReader(source).read()
        val document = combine(root, ranges, source)
        return requireNotNull(
            document.children.singleOrNull {
                it.node.name == "component" && it.node.attributes["name"] == name
            },
        ) { "Missing or duplicated legacy component" }
    }

    private fun combine(
        node: StudioXmlNode,
        range: LexemeRange,
        source: String,
    ): LegacyXmlElement {
        require(node.children.size == range.children.size) { "Mismatched XML evidence" }
        return LegacyXmlElement(
            node,
            StudioBytes(source.substring(range.start, range.end).encodeToByteArray()),
            node.children.zip(range.children).map { (child, childRange) -> combine(child, childRange, source) },
        )
    }
}

private data class LexemeRange(
    val start: Int,
    val end: Int,
    val children: List<LexemeRange>,
)

private class LexemeReader(
    private val source: String,
) {
    private data class Frame(
        val start: Int,
        val children: MutableList<LexemeRange> = mutableListOf(),
    )

    private val frames = ArrayDeque<Frame>()
    private var root: LexemeRange? = null

    fun read(): LexemeRange {
        var offset = 0
        while (offset < source.length) {
            val start = source.indexOf('<', offset)
            if (start < 0) break
            offset =
                when {
                    source.startsWith("<!--", start) -> skip(start, "-->")
                    source.startsWith("<![CDATA[", start) -> skip(start, "]]>")
                    source.startsWith("<?", start) -> skip(start, "?>")
                    else -> readTag(start)
                }
        }
        require(frames.isEmpty()) { "Unclosed XML evidence" }
        return requireNotNull(root) { "Missing XML evidence" }
    }

    private fun skip(
        start: Int,
        terminator: String,
    ): Int {
        val end = source.indexOf(terminator, start)
        require(end >= 0) { "Unclosed XML markup" }
        return end + terminator.length
    }

    private fun readTag(start: Int): Int {
        val end = tagEnd(start)
        when {
            source[start + 1] == '/' -> {
                val frame = frames.removeLast()
                append(LexemeRange(frame.start, end + 1, frame.children.toList()))
            }
            source[end - 1] == '/' -> append(LexemeRange(start, end + 1, emptyList()))
            else -> frames.addLast(Frame(start))
        }
        return end + 1
    }

    private fun tagEnd(start: Int): Int {
        var quote: Char? = null
        for (index in start + 1 until source.length) {
            val character = source[index]
            if (quote != null) {
                if (character == quote) quote = null
            } else {
                when (character) {
                    '\'', '"' -> quote = character
                    '>' -> return index
                }
            }
        }
        error("Unclosed validated XML tag")
    }

    private fun append(range: LexemeRange) {
        if (frames.isEmpty()) {
            require(root == null) { "Multiple XML roots" }
            root = range
        } else {
            frames.last().children.add(range)
        }
    }
}
