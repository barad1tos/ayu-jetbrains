package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.theme.SchemeComponent
import dev.ayuislands.theme.SchemeStoredCell

/** Only persisted fields cross a native process boundary; reference tokens remain opaque. */
internal data class SchemeRecoveryValues(
    val cell: SchemeStoredCell,
    val metadata: String?,
)

internal class StudioSchemeRecoveryRecord(
    val path: String,
    val before: SchemeRecoveryValues,
    val planned: SchemeRecoveryValues,
    val intermediate: List<SchemeStoredCell>,
    val releasedMetadata: String?,
    val hasUnqualifiedProgress: Boolean,
) {
    companion object {
        fun decode(
            identity: SchemeReceiptIdentity,
            receipts: List<PortableReceipt>,
        ): List<StudioSchemeRecoveryRecord> {
            require(receipts.isNotEmpty() && receipts.map { it.id }.distinct().size == receipts.size)
            receipts.forEach { receipt ->
                require(receipt.owner == identity.owner && receipt.identity.resource == identity.resource)
                require(receipt.identity.ownerToken == identity.ownerToken)
                require(
                    StudioNativeEvidence.readProcess(receipt.before) ==
                        StudioNativeEvidence.readProcess(receipt.lastWritten),
                )
            }
            return receipts.groupBy { it.identity.field.substringBeforeLast('/') }.map { (path, fields) ->
                require(path.startsWith("editor/color/") || path.startsWith("editor/attributes/"))
                val originals = fields.filter(StudioSchemeReceipts::isOriginal)
                require(originals.size == SchemeComponent.entries.size)
                require(originals.map { component(it) }.toSet() == SchemeComponent.entries.toSet())
                originals.forEach { require(StudioNativeEvidence.readProcess(it.before) == identity.process) }
                fields.forEach { validateProgress(it, originals) }
                val valueProgress = fields.filter { component(it) == SchemeComponent.VALUE && it !in originals }
                val projections = valueProgress.flatMap { listOf(payload(it.before), payload(it.lastWritten)) }
                val qualified =
                    projections.mapNotNull { values ->
                        values["persisted"]?.takeUnless {
                            StudioNativeEvidence.fields(it)["kind"] ==
                                RawValue.Text("unqualified")
                        }
                    }
                val metadata = originals.single { component(it) == SchemeComponent.METADATA }
                val released = payload(metadata.before).getValue("released")
                StudioSchemeRecoveryRecord(
                    path,
                    values(originals, before = true),
                    values(originals, before = false),
                    qualified.map(StudioSchemeCellCodec::decode),
                    if (released == RawValue.Null) null else metadataState(released),
                    valueProgress.any { !StudioNativeEvidence.isTerminal(it.status) } &&
                        qualified.size != projections.size,
                )
            }
        }

        private fun validateProgress(
            receipt: PortableReceipt,
            originals: List<PortableReceipt>,
        ) {
            val component = component(receipt)
            val original = originals.single { component(it) == component }
            val before = payload(receipt.before)
            val after = payload(receipt.lastWritten)
            require(before["role"] == after["role"])
            if (receipt.id == original.id) {
                require(before["role"] == RawValue.Text("apply"))
            } else {
                require(
                    before["role"] in setOf(RawValue.Text("restore"), RawValue.Text("seed"), RawValue.Text("deletion")),
                )
                require(before["source"] == RawValue.Text(original.id) && after["source"] == before["source"])
                require(receipt.id.startsWith("${original.id}/"))
            }
        }

        private fun values(
            receipts: List<PortableReceipt>,
            before: Boolean,
        ): SchemeRecoveryValues {
            val value = receipts.single { component(it) == SchemeComponent.VALUE }
            val metadata = receipts.single { component(it) == SchemeComponent.METADATA }
            return SchemeRecoveryValues(
                StudioSchemeCellCodec.decode(
                    payload(if (before) value.before else value.lastWritten).getValue("persisted"),
                ),
                metadata(if (before) metadata.before else metadata.lastWritten),
            )
        }

        private fun metadata(value: NativeValue): String? = metadataState(payload(value).getValue("state"))

        private fun metadataState(value: RawValue): String? {
            val state = StudioNativeEvidence.fields(value)
            val present = requireNotNull(state["present"] as? RawValue.BooleanValue).value
            return if (present) {
                requireNotNull(state["raw"] as? RawValue.Text).value
            } else {
                require(state["raw"] == RawValue.Null)
                null
            }
        }

        private fun component(receipt: PortableReceipt): SchemeComponent {
            val component = SchemeComponent.valueOf(receipt.identity.field.substringAfterLast('/'))
            require(payload(receipt.before)["component"] == RawValue.Text(component.name))
            require(payload(receipt.lastWritten)["component"] == RawValue.Text(component.name))
            return component
        }

        private fun payload(value: NativeValue): Map<String, RawValue> =
            StudioNativeEvidence.fields(
                StudioNativeEvidence.fields(requireNotNull(value as? NativeValue.Present).value).getValue("value"),
            )
    }
}
