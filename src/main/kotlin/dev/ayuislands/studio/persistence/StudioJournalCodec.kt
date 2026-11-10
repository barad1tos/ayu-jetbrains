package dev.ayuislands.studio.persistence

import dev.ayuislands.rotation.RotationHandoffKind
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioThemeChange
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.xml.stream.XMLStreamException

internal sealed interface JournalDecodeResult {
    data class Ready(
        val journal: PendingJournal,
    ) : JournalDecodeResult

    data class FutureSchema(
        val schema: Int,
    ) : JournalDecodeResult

    data class Invalid(
        val issue: DecodeIssue,
    ) : JournalDecodeResult
}

internal object StudioJournalCodec {
    private const val BASE_REVISION = "base-revision"
    private const val BASE_DIGEST = "base-digest"
    private const val TARGET_REVISION = "target-revision"
    private const val BLOCKS_EFFECT = "blocks-effect"
    private const val ROTATION_STEP = "rotation-step"
    private const val RUNTIME_PURPOSE = "runtime-purpose"
    private const val METADATA_PURPOSE = "metadata-purpose"
    private const val EXPLICIT_THEME = "explicit-theme"
    private const val OWNER_TOKEN = "owner-token"
    private const val LAST_WRITTEN = "last-written"

    private const val SCHEMA = 2
    private const val ENCODING = "gzip-base64"

    fun encode(journal: PendingJournal): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).buffered().use { compressed ->
            StudioXml.write(writePayload(journal), compressed) { node ->
                if (node.name == "receipts") journal.receipts.asSequence().map(::writeReceipt) else emptySequence()
            }
        }
        val bytes = output.toByteArray()
        return StudioXml.write(
            StudioXmlNode(
                "journal",
                mapOf(
                    "schema" to SCHEMA.toString(),
                    "revision" to journal.token.targetRevision.toString(),
                    "transaction" to StudioXml.encodeText(journal.token.transactionId),
                ),
                listOf(
                    StudioXmlNode(
                        "payload",
                        mapOf("sha256" to StudioXml.hashBytes(bytes), "encoding" to ENCODING),
                        text = StudioXml.encodeBytes(bytes),
                    ),
                ),
            ),
        )
    }

    fun decode(bytes: ByteArray): JournalDecodeResult {
        val receipts = mutableListOf<PortableReceipt>()
        return when (val result = read(bytes, receipts::add)) {
            is JournalDecodeResult.Ready -> result.copy(journal = result.journal.copy(receipts = StudioList(receipts)))
            else -> result
        }
    }

    /** Uses the same decoder and invariants, releasing each receipt after validation. */
    fun isValid(bytes: ByteArray): Boolean = read(bytes) {} is JournalDecodeResult.Ready

    private fun read(
        bytes: ByteArray,
        consume: (PortableReceipt) -> Unit,
    ): JournalDecodeResult =
        try {
            readEnvelope(StudioXml.parseEnvelope(bytes, "journal", SCHEMA), consume)
        } catch (_: XMLStreamException) {
            JournalDecodeResult.Invalid(DecodeIssue.MALFORMED)
        } catch (_: IllegalArgumentException) {
            JournalDecodeResult.Invalid(DecodeIssue.INVALID_STRUCTURE)
        } catch (_: NoSuchElementException) {
            JournalDecodeResult.Invalid(DecodeIssue.INVALID_STRUCTURE)
        } catch (_: IOException) {
            JournalDecodeResult.Invalid(DecodeIssue.MALFORMED)
        }

    /** Receipts go to the caller; the returned header never retains a second collection of them. */
    private fun readEnvelope(
        envelope: StudioXmlEnvelope,
        consume: (PortableReceipt) -> Unit,
    ): JournalDecodeResult {
        val node = envelope.node
        require(node.name == "journal")
        val schema = node.attribute("schema").toInt()
        if (schema > SCHEMA) return JournalDecodeResult.FutureSchema(schema)
        require(schema in 1..SCHEMA)
        val payload =
            StudioXml.readCheckedPayload(node, envelope.payload, ENCODING.takeIf { schema == SCHEMA })
                ?: return JournalDecodeResult.Invalid(DecodeIssue.CHECKSUM)
        val ids = mutableSetOf<String>()
        val owners = mutableSetOf<EffectOwner>()
        val input = payload.bytes.inputStream()
        val prepared =
            (if (schema == SCHEMA) GZIPInputStream(input) else input).use { stream ->
                val tree =
                    StudioXml.parseChildren(stream, listOf("prepared", "receipts")) {
                        require(it.name == "receipt")
                        val receipt = readReceipt(it)
                        validateReceipt(receipt)
                        require(ids.add(receipt.id)) { "Duplicate receipt identity" }
                        owners.add(receipt.owner)
                        consume(receipt)
                    }
                require(stream.read() == -1) { "Payload was not fully consumed" }
                tree
            }
        val journal = readPayload(prepared)
        require(node.attribute("revision").toLong() == journal.token.targetRevision)
        require(StudioXml.decodeText(node.attribute("transaction")) == journal.token.transactionId)
        validateJournal(journal)
        require(owners.all { it in journal.recovery.owners })
        return JournalDecodeResult.Ready(journal)
    }

    private fun writePayload(journal: PendingJournal): StudioXmlNode =
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
                StudioXmlNode(
                    "recovery",
                    children =
                        buildList {
                            add(StudioAppearanceXml.writeTarget(journal.recovery.target))
                            for (owner in journal.recovery.owners) {
                                add(StudioXmlNode("owner", mapOf("kind" to owner.name)))
                            }
                            for ((limitedOwner, reason, blocksEffect) in journal.recovery.limitations) {
                                val attributes = linkedMapOf("owner" to limitedOwner.name, "reason" to reason.name)
                                if (!blocksEffect) attributes[BLOCKS_EFFECT] = "false"
                                add(
                                    StudioXmlNode(
                                        "limitation",
                                        attributes,
                                    ),
                                )
                            }
                            writePurpose(journal.recovery.purpose)?.let(::add)
                            journal.recovery.rotationStep?.let {
                                add(
                                    StudioRotationXml.writeCandidate(it, ROTATION_STEP),
                                )
                            }
                        },
                ),
                StudioXmlNode("receipts"),
            ),
        )

    private fun readPayload(node: StudioXmlNode): PendingJournal {
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
        val baseNode = node.child("base").requireShape(setOf("presence"), allowsText = true)
        val base =
            when (baseNode.attribute("presence")) {
                "absent" -> {
                    require(baseNode.text.isEmpty())
                    null
                }

                "value" -> {
                    StudioBytes(StudioXml.decodeBytes(baseNode.text))
                }

                else -> {
                    throw IllegalArgumentException("Invalid base presence")
                }
            }
        val candidate =
            StudioStateCodec.decode(
                StudioXml.decodeBytes(
                    node
                        .child("candidate")
                        .requireShape(allowsText = true)
                        .text,
                ),
            )
        require(candidate is DecodeResult.Ready)
        node.child("receipts").requireShape()
        return PendingJournal(
            token,
            base,
            candidate.document,
            readRecovery(node.child("recovery")),
            StudioList(emptyList()),
        )
    }

    private fun readRecovery(node: StudioXmlNode): RecoveryPlan {
        node.requireShape(
            childNames = setOf("target", "owner", "limitation", ROTATION_STEP, RUNTIME_PURPOSE, METADATA_PURPOSE),
        )
        val owners =
            node.children.filter { it.name == "owner" }.map { child ->
                EffectOwner.valueOf(child.requireShape(setOf("kind")).attribute("kind"))
            }
        val limitations =
            node.children.filter { it.name == "limitation" }.map { child ->
                val attributes = setOf("owner", "reason", BLOCKS_EFFECT).intersect(child.attributes.keys)
                child.requireShape(attributes)
                EffectLimitation(
                    EffectOwner.valueOf(child.attribute("owner")),
                    LimitationReason.valueOf(child.attribute("reason")),
                    child.attributes[BLOCKS_EFFECT]?.toBooleanStrict() ?: true,
                )
            }
        require(limitations.distinct().size == limitations.size)
        require(limitations.none { it.blocksEffect && it.owner in owners })
        return RecoveryPlan(
            StudioAppearanceXml.readTarget(node.child("target")),
            StudioList(owners),
            StudioList(limitations),
            node.optionalChild(ROTATION_STEP)?.let(StudioRotationXml::readCandidate),
            readRecoveryPurpose(node),
        )
    }

    private fun readRecoveryPurpose(node: StudioXmlNode): RecoveryPurpose {
        val metadata = node.optionalChild(METADATA_PURPOSE)
        val runtime = node.optionalChild(RUNTIME_PURPOSE)
        if (metadata == null) return readPurpose(runtime)
        metadata.requireShape()
        require(runtime == null) { "Metadata cannot own an automatic runtime operation" }
        return RecoveryPurpose.Metadata
    }

    private fun writePurpose(purpose: RecoveryPurpose): StudioXmlNode? {
        if (purpose is RecoveryPurpose.NativeChange) return writeNativePurpose(purpose)
        val (kind, operationId) =
            when (purpose) {
                RecoveryPurpose.UserAcceptance -> return null
                RecoveryPurpose.Metadata -> return StudioXmlNode(METADATA_PURPOSE)
                is RecoveryPurpose.NativeChange -> error("Native purpose was already written")
                is RecoveryPurpose.RotationStep -> "step" to purpose.operationId
                is RecoveryPurpose.RotationSchedule -> "schedule" to purpose.operationId
                is RecoveryPurpose.ContextHandoff ->
                    when (purpose.kind) {
                        RotationHandoffKind.FOCUS -> "focus" to purpose.operationId
                        RotationHandoffKind.CONTINUITY -> "continuity" to purpose.operationId
                    }
            }
        return StudioXmlNode(RUNTIME_PURPOSE, mapOf("kind" to kind, "operation" to operationId.toString()))
    }

    private fun readPurpose(node: StudioXmlNode?): RecoveryPurpose {
        if (node == null) return RecoveryPurpose.UserAcceptance
        if (node.attribute("kind") == "native") return readNativePurpose(node)
        node.requireShape(setOf("kind", "operation"))
        val operationId = UUID.fromString(node.attribute("operation"))
        return when (node.attribute("kind")) {
            "step" -> RecoveryPurpose.RotationStep(operationId)
            "schedule" -> RecoveryPurpose.RotationSchedule(operationId)
            "focus" -> RecoveryPurpose.ContextHandoff(operationId, RotationHandoffKind.FOCUS)
            "continuity" -> RecoveryPurpose.ContextHandoff(operationId, RotationHandoffKind.CONTINUITY)
            else -> throw IllegalArgumentException("Invalid runtime purpose")
        }
    }

    private fun writeNativePurpose(purpose: RecoveryPurpose.NativeChange): StudioXmlNode {
        val attributes = linkedMapOf("kind" to "native", "operation" to purpose.operationId.toString())
        attributes["change"] =
            when (val change = purpose.change) {
                StudioNativeChange.Appearance -> "appearance"
                StudioNativeChange.AccentContext -> "accent"
                is StudioNativeChange.Theme ->
                    when (val selection = change.selection) {
                        StudioThemeChange.FollowSystem -> "follow-system"
                        is StudioThemeChange.Explicit -> {
                            attributes["theme"] = StudioXml.encodeText(selection.name)
                            EXPLICIT_THEME
                        }
                    }
            }
        return StudioXmlNode(RUNTIME_PURPOSE, attributes)
    }

    private fun readNativePurpose(node: StudioXmlNode): RecoveryPurpose.NativeChange {
        val kind = node.attribute("change")
        node.requireShape(
            setOf("kind", "operation", "change") + if (kind == EXPLICIT_THEME) setOf("theme") else emptySet(),
        )
        val change =
            when (kind) {
                "appearance" -> StudioNativeChange.Appearance
                "accent" -> StudioNativeChange.AccentContext
                "follow-system" -> StudioNativeChange.Theme(StudioThemeChange.FollowSystem)
                EXPLICIT_THEME ->
                    StudioNativeChange.Theme(
                        StudioThemeChange.Explicit(StudioXml.decodeText(node.attribute("theme"))),
                    )
                else -> throw IllegalArgumentException("Invalid native change")
            }
        return RecoveryPurpose.NativeChange(UUID.fromString(node.attribute("operation")), change)
    }

    private fun writeReceipt(receipt: PortableReceipt): StudioXmlNode =
        StudioXmlNode(
            "receipt",
            mapOf(
                "id" to StudioXml.encodeText(receipt.id),
                "owner" to receipt.owner.name,
                "status" to receipt.status.name,
            ),
            listOf(
                StudioXmlNode(
                    "identity",
                    mapOf(
                        "resource" to StudioXml.encodeText(receipt.identity.resource),
                        "field" to StudioXml.encodeText(receipt.identity.field),
                        OWNER_TOKEN to StudioXml.encodeText(receipt.identity.ownerToken),
                    ),
                ),
                writeNative("before", receipt.before),
                writeNative(LAST_WRITTEN, receipt.lastWritten),
            ),
        )

    private fun readReceipt(node: StudioXmlNode): PortableReceipt {
        node.requireShape(setOf("id", "owner", "status"), setOf("identity", "before", LAST_WRITTEN))
        val identity = node.child("identity").requireShape(setOf("resource", "field", OWNER_TOKEN))
        return PortableReceipt(
            StudioXml.decodeText(node.attribute("id")),
            EffectOwner.valueOf(node.attribute("owner")),
            NativeFieldIdentity(
                StudioXml.decodeText(identity.attribute("resource")),
                StudioXml.decodeText(identity.attribute("field")),
                StudioXml.decodeText(identity.attribute(OWNER_TOKEN)),
            ),
            readNative(node.child("before")),
            readNative(node.child(LAST_WRITTEN)),
            RestoreStatus.valueOf(node.attribute("status")),
        )
    }

    private fun writeNative(
        name: String,
        value: NativeValue,
    ): StudioXmlNode =
        when (value) {
            NativeValue.Absent -> {
                StudioXmlNode(name, mapOf("presence" to "absent"))
            }

            is NativeValue.Present -> {
                StudioXmlNode(
                    name,
                    mapOf("presence" to "value"),
                    listOf(StudioValueXml.writeValue(value.value)),
                )
            }
        }

    private fun readNative(node: StudioXmlNode): NativeValue =
        when (node.attribute("presence")) {
            "absent" -> {
                node.requireShape(setOf("presence"))
                NativeValue.Absent
            }

            "value" -> {
                node.requireShape(setOf("presence"), setOf("value"))
                NativeValue.Present(StudioValueXml.readValue(node.child("value")))
            }

            else -> {
                throw IllegalArgumentException("Invalid native value presence")
            }
        }

    private fun validateJournal(journal: PendingJournal) {
        if (journal.recovery.purpose == RecoveryPurpose.Metadata) {
            requireNotNull(journal.base) { "Failed requirement." }
            require(journal.recovery.owners.isEmpty())
            require(journal.recovery.limitations.isEmpty())
            require(journal.recovery.rotationStep == null)
            require(journal.receipts.isEmpty())
        }
        val token = journal.token
        require(token.transactionId.isNotBlank() && token.baseRevision >= -1 && token.targetRevision >= 0)
        require(token.targetRevision == token.baseRevision + 1)
        require(journal.candidate.header.transactionId == token.transactionId)
        require(journal.candidate.header.revision == token.targetRevision)
        if (journal.base == null) {
            require(token.baseRevision == -1L && token.baseDigest == null)
        } else {
            val base = StudioStateCodec.decode(journal.base.toByteArray())
            require(base is DecodeResult.Ready && base.digest == token.baseDigest)
            require(base.document.header.revision == token.baseRevision)
            require(base.document.header.transactionId != token.transactionId)
            require(base.document.header.migration == journal.candidate.header.migration)
        }
        require(
            journal.recovery.owners ==
                journal.recovery.owners
                    .distinct()
                    .sortedBy { it.ordinal },
        )
    }

    private fun validateReceipt(receipt: PortableReceipt) {
        require(receipt.id.isNotBlank())
        require(receipt.identity.resource.isNotBlank() && receipt.identity.field.isNotBlank())
        require(receipt.identity.ownerToken.isNotBlank())
        require(
            receipt.status != RestoreStatus.KEPT_AFTER_RESTART ||
                StudioReceiptPolicy.canRetain(receipt.owner, receipt.identity.field),
        ) {
            "Retained geometry receipt has an unsupported native owner"
        }
    }
}
