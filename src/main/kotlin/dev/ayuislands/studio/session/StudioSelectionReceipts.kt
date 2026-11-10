package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontSchemeLookup
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus

internal data class StudioSelectionEvidence(
    val process: StudioProcess,
    val beforeId: String?,
    val plannedId: String?,
)

/** Selection never adopts identities; only an already established unique identity can survive restart. */
internal object StudioSelectionReceipts {
    const val RESOURCE = "ide/editor-scheme"
    private const val FIELD = "selection"

    fun capture(
        input: StudioEffectInput,
        process: StudioProcess,
        census: FontSchemeCensus,
        before: EditorColorsScheme,
        planned: EditorColorsScheme,
    ): PortableReceipt {
        val token = input.candidate.header.transactionId
        val tokens = StudioNativeTokens("$token/editor-scheme")
        return PortableReceipt(
            "$token/$RESOURCE/$FIELD",
            EffectOwner.SYSTEM,
            NativeFieldIdentity(RESOURCE, FIELD, token),
            value(process, before, census, tokens),
            value(process, planned, census, tokens),
            RestoreStatus.PENDING,
        )
    }

    fun decode(receipt: PortableReceipt): StudioSelectionEvidence {
        require(receipt.owner == EffectOwner.SYSTEM)
        require(receipt.identity.resource == RESOURCE && receipt.identity.field == FIELD)
        require(receipt.id == "${receipt.identity.ownerToken}/$RESOURCE/$FIELD")
        val process = StudioNativeEvidence.readProcess(receipt.before)
        require(process == StudioNativeEvidence.readProcess(receipt.lastWritten))
        return StudioSelectionEvidence(process, identity(receipt.before), identity(receipt.lastWritten))
    }

    fun blockedBy(receipts: List<PortableReceipt>): Boolean =
        receipts.any {
            it.identity.resource == RESOURCE &&
                it.identity.field == FIELD &&
                !StudioNativeEvidence.isTerminal(it.status)
        }

    private fun value(
        process: StudioProcess,
        scheme: EditorColorsScheme,
        census: FontSchemeCensus,
        tokens: StudioNativeTokens,
    ): NativeValue {
        val candidate = FontIdentityRules.canonicalId(scheme.metaProperties[FontIdentityRules.METADATA_KEY])
        val id = candidate?.takeIf { (census.lookup(it) as? FontSchemeLookup.Unique)?.scheme === scheme }
        return StudioNativeEvidence.envelope(
            process,
            StudioNativeEvidence.record(
                "id" to (id?.let(RawValue::Text) ?: RawValue.Null),
                "reference" to RawValue.Text(tokens.token(scheme)),
            ),
        )
    }

    private fun identity(native: NativeValue): String? {
        val envelope = StudioNativeEvidence.fields(requireNotNull(native as? NativeValue.Present).value)
        val fields = StudioNativeEvidence.fields(envelope.getValue("value"))
        require(fields.keys == setOf("id", "reference"))
        require(requireNotNull(fields["reference"] as? RawValue.Text).value.isNotBlank())
        val raw = fields.getValue("id")
        if (raw == RawValue.Null) return null
        val id = requireNotNull(raw as? RawValue.Text).value
        require(FontIdentityRules.canonicalId(id) == id)
        return id
    }
}
