package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioNativeEvidence.envelope
import dev.ayuislands.studio.session.StudioNativeEvidence.fields
import dev.ayuislands.studio.session.StudioNativeEvidence.isTerminal
import dev.ayuislands.studio.session.StudioNativeEvidence.readProcess
import dev.ayuislands.studio.session.StudioNativeEvidence.record
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.SchemeAdmissionFacts
import dev.ayuislands.theme.SchemeCellFacts
import dev.ayuislands.theme.SchemeColorFacts
import dev.ayuislands.theme.SchemeColorMarker
import dev.ayuislands.theme.SchemeComponent
import dev.ayuislands.theme.SchemeDeletionState
import dev.ayuislands.theme.SchemeEntryRecord
import dev.ayuislands.theme.SchemePersistenceContext
import dev.ayuislands.theme.SchemePersistenceResult
import dev.ayuislands.theme.SchemeSeedObservation
import dev.ayuislands.theme.SchemeSeedRecord
import dev.ayuislands.theme.SchemeSeedState
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeStoredCell
import dev.ayuislands.theme.SchemeWritePhase
import dev.ayuislands.theme.SchemeWriteProgress

/** The caller supplies the existing shared scheme identity; native names never replace it. */
internal data class SchemeReceiptIdentity(
    val owner: EffectOwner,
    val resource: String,
    val ownerToken: String,
    val process: StudioProcess,
)

/**
 * Encodes immutable native records and separately acknowledged restoration evidence without native reads or writes.
 * Captured-base projections are not recovery authority; restart binding and write proof belong to the native adapter.
 * Exact live handles remain with the existing batch. Tokens never resolve or reconstruct those handles.
 */
internal object StudioSchemeReceipts {
    private val owners = setOf(EffectOwner.ACCENT, EffectOwner.SYNTAX, EffectOwner.VCS)
    private val unqualified = record("kind" to RawValue.Text("unqualified"))

    fun isOriginal(receipt: PortableReceipt): Boolean = receipt.id == receiptId(receipt.owner, receipt.identity)

    fun encode(
        identity: SchemeReceiptIdentity,
        entryRecord: SchemeEntryRecord,
        persistence: SchemePersistenceResult.Ready,
        tokens: StudioNativeTokens,
    ): StudioList<PortableReceipt> {
        require(identity.owner in owners && identity.resource.isNotBlank() && identity.ownerToken.isNotBlank())
        require(persistence.record === entryRecord) { "Persistence capture belongs to another native record" }
        val path = entryPath(entryRecord.entry)
        return StudioList(
            SchemeComponent.entries.map { component ->
                val native = NativeFieldIdentity(identity.resource, "$path/${component.name}", identity.ownerToken)
                PortableReceipt(
                    receiptId(identity.owner, native),
                    identity.owner,
                    native,
                    envelope(identity.process, originalPayload(component, entryRecord, persistence, true, tokens)),
                    envelope(identity.process, originalPayload(component, entryRecord, persistence, false, tokens)),
                    RestoreStatus.PENDING,
                )
            },
        )
    }

    /** Only exported component progress changes status; aggregate entry state cannot settle another component. */
    fun refresh(
        receipts: List<PortableReceipt>,
        progress: Map<SchemeComponent, SchemeWriteProgress>,
    ): StudioList<PortableReceipt> {
        require(receipts.isNotEmpty() && receipts.map { it.id }.distinct().size == receipts.size)
        require(progress.keys == SchemeComponent.entries.toSet()) { "Native component progress is incomplete" }
        val first = receipts.first()
        val entry = first.identity.field.substringBeforeLast('/')
        require(
            receipts.all {
                it.owner == first.owner &&
                    it.identity.resource == first.identity.resource &&
                    it.identity.ownerToken == first.identity.ownerToken &&
                    it.identity.field.substringBeforeLast('/') == entry
            },
        ) { "Editor receipts belong to different native attempts" }
        return StudioList(
            receipts.map { receipt ->
                val component = component(receipt)
                if (isTerminal(receipt.status)) {
                    receipt
                } else {
                    val nativeStatus = status(progress.getValue(component).phase)
                    val isOriginal = receipt.id == receiptId(receipt.owner, receipt.identity)
                    receipt.copy(
                        status =
                            if (isOriginal ||
                                isTerminal(nativeStatus)
                            ) {
                                nativeStatus
                            } else {
                                RestoreStatus.PENDING
                            },
                    )
                }
            },
        )
    }

    /** Keeps one rollback attempt's native token namespace and process provenance together. */
    class Restoration(
        private val process: StudioProcess,
        private val tokens: StudioNativeTokens,
    ) {
        /** A preparation adds a new receipt; its captured native phase never rewrites the original payload. */
        fun encodeRestore(
            original: PortableReceipt,
            sequence: Int,
            progress: SchemeWriteProgress,
            persisted: Pair<SchemeStoredCell, SchemeStoredCell>? = null,
        ): PortableReceipt {
            require(sequence >= 0)
            val component = component(original)
            require(
                original.id == receiptId(original.owner, original.identity),
            ) { "Restoration requires an original receipt" }
            require(readProcess(original.before) == readProcess(original.lastWritten)) {
                "Editor receipt process provenance differs"
            }
            return PortableReceipt(
                "${original.id}/restore/$sequence",
                original.owner,
                original.identity,
                envelope(process, restorePayload(component, original, progress, true, persisted?.first)),
                envelope(process, restorePayload(component, original, progress, false, persisted?.second)),
                RestoreStatus.PENDING,
            )
        }

        /** A seed is intermediate native evidence; SETTLED does not mean the original entry has been restored. */
        fun encodeSeed(
            original: PortableReceipt,
            sequence: Int,
            seed: SchemeSeedRecord,
            observation: SchemeSeedObservation,
            persisted: Pair<SchemeStoredCell, SchemeStoredCell>? = null,
        ): PortableReceipt {
            require(sequence >= 0 && component(original) == SchemeComponent.VALUE)
            require(original.id == receiptId(original.owner, original.identity))
            require(original.identity.field == "${entryPath(seed.entry)}/VALUE")
            require(readProcess(original.before) == readProcess(original.lastWritten))
            val payloads =
                listOf(seed.before, seed.planned).mapIndexed { index, cell ->
                    envelope(
                        process,
                        record(
                            "role" to RawValue.Text("seed"),
                            "source" to RawValue.Text(original.id),
                            "component" to RawValue.Text(SchemeComponent.VALUE.name),
                            "phase" to RawValue.Text(observation.state.name),
                            "expected" to cellState(observation.expected, tokens),
                            "returned" to RawValue.BooleanValue(observation.hasReturnedNormally),
                            "state" to cellState(cell, tokens),
                            "provenance" to payload(original.before).getValue("provenance"),
                            "persisted" to storedPayload(if (index == 0) persisted?.first else persisted?.second),
                        ),
                    )
                }
            return PortableReceipt(
                "${original.id}/seed/$sequence",
                original.owner,
                original.identity,
                payloads[0],
                payloads[1],
                if (observation.state == SchemeSeedState.FOREIGN) RestoreStatus.FOREIGN else RestoreStatus.PENDING,
            )
        }

        /** Native absence comes from the qualified batch destination, never from a persisted omission alone. */
        fun encodeDeletion(
            original: PortableReceipt,
            cells: Pair<SchemeCellFacts, SchemeCellFacts>,
            state: SchemeDeletionState,
            persisted: Pair<SchemeStoredCell, SchemeStoredCell>? = null,
            sequence: Int = 0,
        ): PortableReceipt {
            require(component(original) == SchemeComponent.VALUE)
            require(original.id == receiptId(original.owner, original.identity))
            val absent = cellState(cells.second, tokens)
            require(fields(absent)["kind"] in setOf(RawValue.Text("absent-color"), RawValue.Text("absent-attributes")))
            require(readProcess(original.before) == readProcess(original.lastWritten))
            if (StudioProcessPolicy.compare(readProcess(original.before), process) != StudioProcessRelation.DIFFERENT) {
                require(absent == payload(original.before).getValue("state"))
            }
            val payloads =
                listOf(cellState(cells.first, tokens), absent).mapIndexed { index, cell ->
                    envelope(
                        process,
                        record(
                            "role" to RawValue.Text("deletion"),
                            "source" to RawValue.Text(original.id),
                            "component" to RawValue.Text(SchemeComponent.VALUE.name),
                            "phase" to RawValue.Text(state.name),
                            "state" to cell,
                            "provenance" to payload(original.before).getValue("provenance"),
                            "persisted" to storedPayload(if (index == 0) persisted?.first else persisted?.second),
                        ),
                    )
                }
            return PortableReceipt(
                "${original.id}/deletion" + if (sequence == 0) "" else "/$sequence",
                original.owner,
                original.identity,
                payloads[0],
                payloads[1],
                when (state) {
                    SchemeDeletionState.PENDING -> RestoreStatus.PENDING
                    SchemeDeletionState.RESTORED -> RestoreStatus.RESTORED
                    SchemeDeletionState.FOREIGN -> RestoreStatus.FOREIGN
                },
            )
        }

        private fun restorePayload(
            component: SchemeComponent,
            original: PortableReceipt,
            progress: SchemeWriteProgress,
            isBefore: Boolean,
            persisted: SchemeStoredCell?,
        ): RawValue {
            val values =
                mutableListOf(
                    "role" to RawValue.Text("restore"),
                    "source" to RawValue.Text(original.id),
                    "component" to RawValue.Text(component.name),
                    "phase" to RawValue.Text(progress.phase.name),
                    "state" to
                        componentState(component, if (isBefore) progress.expected else progress.destination, tokens),
                    "provenance" to payload(original.before).getValue("provenance"),
                )
            if (component == SchemeComponent.VALUE) values += "persisted" to storedPayload(persisted)
            return record(values)
        }
    }

    private fun originalPayload(
        component: SchemeComponent,
        entryRecord: SchemeEntryRecord,
        persistence: SchemePersistenceResult.Ready,
        isBefore: Boolean,
        tokens: StudioNativeTokens,
    ): RawValue {
        val state = if (isBefore) entryRecord.before else entryRecord.planned
        val values =
            mutableListOf(
                "role" to RawValue.Text("apply"),
                "component" to RawValue.Text(component.name),
                "state" to componentState(component, state, tokens),
                "restored" to componentState(component, entryRecord.restored, tokens),
                "released" to (entryRecord.released?.let { componentState(component, it, tokens) } ?: RawValue.Null),
                "provenance" to provenance(persistence.dependencies, tokens),
            )
        if (component == SchemeComponent.VALUE) {
            val cell = if (isBefore) persistence.before else persistence.plannedWithCapturedBase
            values += "persisted" to StudioSchemeCellCodec.encode(cell)
        }
        return record(values)
    }

    private fun storedPayload(cell: SchemeStoredCell?): RawValue =
        cell?.let(StudioSchemeCellCodec::encode) ?: unqualified

    private fun componentState(
        component: SchemeComponent,
        facts: SchemeAdmissionFacts,
        tokens: StudioNativeTokens,
    ): RawValue =
        when (component) {
            SchemeComponent.VALUE -> cellState(facts.cell, tokens)
            SchemeComponent.OWNERSHIP -> token(facts.owner, tokens)
            SchemeComponent.METADATA -> metadataState(facts, tokens)
        }

    private fun cellState(
        cell: SchemeCellFacts,
        tokens: StudioNativeTokens,
    ): RawValue =
        when (cell) {
            is SchemeCellFacts.Color -> {
                colorState(cell.value, tokens)
            }

            is SchemeCellFacts.Attributes -> {
                val value = cell.value
                if (value == null) {
                    record("kind" to RawValue.Text("absent-attributes"))
                } else {
                    record(
                        "kind" to RawValue.Text("attributes"),
                        "reference" to token(value.identity, tokens),
                        "flyweight" to token(value.flyweight, tokens),
                    )
                }
            }

            SchemeCellFacts.InheritedAttributes -> {
                record("kind" to RawValue.Text("inherited-attributes"))
            }

            SchemeCellFacts.Unavailable -> {
                throw IllegalArgumentException("Native editor cell is unavailable")
            }
        }

    private fun colorState(
        color: SchemeColorFacts?,
        tokens: StudioNativeTokens,
    ): RawValue {
        if (color == null) return record("kind" to RawValue.Text("absent-color"))
        val kind =
            when (color) {
                is SchemeColorFacts.Plain -> {
                    "color"
                }

                is SchemeColorFacts.Opaque -> {
                    "opaque-color"
                }

                is SchemeColorFacts.Marker -> {
                    when (color.kind) {
                        SchemeColorMarker.NULL -> "null-color"
                        SchemeColorMarker.INHERITED -> "inherited-color"
                    }
                }
            }
        return record("kind" to RawValue.Text(kind), "reference" to token(color.identity, tokens))
    }

    private fun metadataState(
        facts: SchemeAdmissionFacts,
        tokens: StudioNativeTokens,
    ): RawValue {
        require(if (facts.hasMetadata) facts.metadataValue is String else facts.metadataValue == null) {
            "Raw editor metadata has no qualified portable representation"
        }
        val raw = (facts.metadataValue as? String)?.let(RawValue::Text) ?: RawValue.Null
        return record(
            "table" to token(facts.metadataTable, tokens),
            "present" to RawValue.BooleanValue(facts.hasMetadata),
            "reference" to token(facts.metadataValue, tokens),
            "raw" to raw,
        )
    }

    private fun provenance(
        context: SchemePersistenceContext,
        tokens: StudioNativeTokens,
    ): RawValue =
        record(
            "target" to token(context.target.scheme, tokens),
            "source" to
                when (val source = context.target.source) {
                    SchemeSource.Active -> {
                        record("kind" to RawValue.Text("active"))
                    }

                    is SchemeSource.Named -> {
                        record("kind" to RawValue.Text("named"), "lookup" to RawValue.Text(source.lookupName))
                    }
                },
            "name" to RawValue.Text(context.target.name),
            "parent" to token(context.target.parent, tokens),
            "original" to token(context.original, tokens),
            "original-name" to (context.originalName?.let(RawValue::Text) ?: RawValue.Null),
            "base" to token(context.base, tokens),
            "key" to keyToken(context.key.entry, tokens),
            "fallback" to (context.key.fallback?.let { keyToken(it, tokens) } ?: RawValue.Null),
            "fallback-name" to (context.key.fallbackName?.let(RawValue::Text) ?: RawValue.Null),
            "dependencies" to
                RawValue.OrderedList(
                    StudioList(
                        context.links.map { link ->
                            record(
                                "scheme" to token(link.scheme, tokens),
                                "parent" to token(link.parent, tokens),
                                "cell" to cellState(link.cell, tokens),
                                "color-key" to token(link.colorKey, tokens),
                            )
                        },
                    ),
                ),
        )

    private fun entryPath(entry: SchemeEntry): String =
        when (entry) {
            is SchemeEntry.ColorEntry -> "editor/color/${entry.key.externalName}"
            is SchemeEntry.AttributesEntry -> "editor/attributes/${entry.key.externalName}"
        }

    private fun keyToken(
        entry: SchemeEntry,
        tokens: StudioNativeTokens,
    ): RawValue =
        when (entry) {
            is SchemeEntry.ColorEntry -> token(entry.key, tokens)
            is SchemeEntry.AttributesEntry -> token(entry.key, tokens)
        }

    private fun component(receipt: PortableReceipt): SchemeComponent {
        require(receipt.owner in owners)
        val field = receipt.identity.field
        require(field.startsWith("editor/color/") || field.startsWith("editor/attributes/"))
        val component = SchemeComponent.entries.singleOrNull { it.name == field.substringAfterLast('/') }
        requireNotNull(component) { "Unknown editor component" }
        require(payload(receipt.before)["component"] == RawValue.Text(component.name))
        require(payload(receipt.lastWritten)["component"] == RawValue.Text(component.name))
        return component
    }

    private fun receiptId(
        owner: EffectOwner,
        native: NativeFieldIdentity,
    ): String = "${native.ownerToken}/${owner.name}/${native.resource}/${native.field}"

    private fun payload(value: NativeValue): Map<String, RawValue> {
        readProcess(value)
        return fields(fields(requireNotNull(value as? NativeValue.Present).value).getValue("value"))
    }

    /** Native objects are opaque identity tokens; their equality and serialization are never invoked. */
    private fun token(
        value: Any?,
        tokens: StudioNativeTokens,
    ): RawValue = StudioNativeEvidence.reference(value?.let(tokens::token))

    private fun status(phase: SchemeWritePhase): RestoreStatus =
        when (phase) {
            SchemeWritePhase.PREPARED, SchemeWritePhase.WRITING, SchemeWritePhase.RESTORING -> RestoreStatus.PENDING
            SchemeWritePhase.APPLIED -> RestoreStatus.OWNED
            SchemeWritePhase.RESTORED -> RestoreStatus.RESTORED
            SchemeWritePhase.FOREIGN -> RestoreStatus.FOREIGN
        }
}
