package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import java.io.InputStream

/** Composes the persisted transaction, recovery plan and ordered receipts without file authority. */
internal object StudioJournalXml {
    private const val BASE_REVISION = "base-revision"
    private const val BASE_DIGEST = "base-digest"
    private const val TARGET_REVISION = "target-revision"

    fun write(journal: PendingJournal): StudioXmlNode =
        StudioXmlNode(
            "prepared",
            mapOf(
                "transaction" to StudioXml.encodeText(journal.token.transactionId),
                BASE_REVISION to journal.token.baseRevision.toString(),
                BASE_DIGEST to (journal.token.baseDigest ?: ""),
                TARGET_REVISION to journal.token.targetRevision.toString(),
            ),
            listOf(
                StudioXmlNode(
                    "base",
                    mapOf("presence" to if (journal.base == null) "absent" else "value"),
                    text = journal.base?.let { StudioXml.encodeBytes(it.toByteArray()) } ?: "",
                ),
                StudioXmlNode("candidate", text = StudioXml.encodeBytes(StudioStateCodec.encode(journal.candidate))),
                StudioRecoveryXml.write(journal.recovery),
                StudioXmlNode("receipts"),
            ),
        )

    /** The caller may stage decoded receipts, but must discard them if the whole journal is invalid. */
    fun read(
        input: InputStream,
        consume: (PortableReceipt) -> Unit,
    ): PendingJournal {
        val ids = mutableSetOf<String>()
        val owners = mutableSetOf<EffectOwner>()
        val node =
            StudioXml.parseChildren(input, listOf("prepared", "receipts")) {
                val receipt = StudioReceiptXml.read(it)
                require(ids.add(receipt.id)) { "Duplicate receipt identity" }
                owners.add(receipt.owner)
                consume(receipt)
            }
        val journal = readPrepared(node)
        require(owners.all { it in journal.recovery.owners }) { "Receipt owner is not enrolled" }
        validateGenerations(journal)
        return journal
    }

    private fun readPrepared(node: StudioXmlNode): PendingJournal {
        require(node.name == "prepared")
        node.requireShape(
            setOf("transaction", BASE_REVISION, BASE_DIGEST, TARGET_REVISION),
            setOf("base", "candidate", "recovery", "receipts"),
        )
        val token =
            PreparedToken(
                StudioXml.decodeText(node.attribute("transaction")),
                node.attribute(BASE_REVISION).toLong(),
                node.attribute(BASE_DIGEST).ifEmpty { null },
                node.attribute(TARGET_REVISION).toLong(),
            )
        val base = readBase(node.child("base"))
        val candidate = node.child("candidate").requireShape(allowsText = true)
        val decoded = StudioStateCodec.decode(StudioXml.decodeBytes(candidate.text))
        require(decoded is DecodeResult.Ready) { "Invalid journal candidate" }
        node.child("receipts").requireShape()
        val recovery = StudioRecoveryXml.read(node.child("recovery"))
        require(recovery.purpose != RecoveryPurpose.Metadata || base != null) { "Metadata requires a base" }
        return PendingJournal(token, base, decoded.document, recovery, StudioList(emptyList()))
    }

    private fun readBase(node: StudioXmlNode): StudioBytes? {
        node.requireShape(setOf("presence"), allowsText = true)
        return when (node.attribute("presence")) {
            "absent" -> {
                require(node.text.isEmpty()) { "Absent base contains bytes" }
                null
            }

            "value" -> StudioBytes(StudioXml.decodeBytes(node.text))
            else -> throw IllegalArgumentException("Invalid base presence")
        }
    }

    private fun validateGenerations(journal: PendingJournal) {
        val token = journal.token
        val header = journal.candidate.header
        require(header.transactionId == token.transactionId && header.revision == token.targetRevision) {
            "Candidate does not match the prepared token"
        }
        if (journal.base == null) {
            require(token.baseRevision == -1L) { "Missing prior generation" }
            return
        }
        val base = StudioStateCodec.decode(journal.base.toByteArray())
        require(base is DecodeResult.Ready && base.digest == token.baseDigest) { "Base digest does not match" }
        val previous = base.document.header
        require(previous.revision == token.baseRevision && previous.transactionId != token.transactionId) {
            "Base does not match the prepared token"
        }
        require(previous.migration == header.migration) { "Migration archive changed across the transaction" }
    }
}
