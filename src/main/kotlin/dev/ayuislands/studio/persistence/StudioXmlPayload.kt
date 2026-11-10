package dev.ayuislands.studio.persistence

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Envelope metadata and decoded bytes; the existing checksum and shape checks still qualify them. */
internal class StudioXmlEnvelope(
    val node: StudioXmlNode,
    val payload: ByteArray?,
)

/** Consumes only the named envelope's direct payload, without retaining its Base64 text. */
internal class StudioXmlPayload(
    private val rootName: String,
    private val capacity: Int,
    private val maxSchema: Int,
) {
    private var isSupported = false
    private var decoder: Base64Payload? = null

    fun consumer(
        path: List<String>,
        attributes: Map<String, String>,
    ): ((String) -> Unit)? {
        if (path == listOf(rootName)) {
            isSupported = attributes["schema"]?.toIntOrNull() in 1..maxSchema
        }
        if (path != listOf(rootName, "payload")) return null
        if (!isSupported) return {}
        require(decoder == null) { "Duplicate payload" }
        val selected = Base64Payload(capacity)
        decoder = selected
        return selected::append
    }

    fun finish(): ByteArray? = decoder?.finish()
}

/** JDK Basic Base64 semantics, including strict padding, across arbitrary XML text boundaries. */
private class Base64Payload(
    capacity: Int,
) {
    private val output = ByteArrayOutputStream(capacity)
    private val decoder = Base64.getDecoder()
    private var remainder = ""
    private var isPadded = false

    fun append(text: String) {
        if (text.isEmpty()) return
        require(!isPadded) { "Data follows Base64 padding" }
        val combined = remainder + text
        val length = combined.length / QUARTET_LENGTH * QUARTET_LENGTH
        remainder = combined.substring(length)
        if (length == 0) return
        val block = combined.substring(0, length)
        output.write(decoder.decode(block))
        isPadded = '=' in block
        require(!isPadded || remainder.isEmpty()) { "Data follows Base64 padding" }
    }

    fun finish(): ByteArray {
        if (remainder.isNotEmpty()) output.write(decoder.decode(remainder))
        return output.toByteArray()
    }

    private companion object {
        const val QUARTET_LENGTH = 4
    }
}
