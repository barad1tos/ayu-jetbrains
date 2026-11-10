package dev.ayuislands.studio.session

import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus

internal data class StudioThemeEvidence(
    val process: StudioProcess,
    val beforeId: String,
    val plannedId: String,
)

/** Logical selection survives restart; its native reference tokens are valid only with retained handles. */
internal object StudioThemeReceipts {
    private const val RESOURCE = "ide/theme"
    private const val FIELD = "selection"

    fun capture(
        transactionId: String,
        process: StudioProcess,
        before: UIThemeLookAndFeelInfo,
        target: UIThemeLookAndFeelInfo,
    ): PortableReceipt {
        require(transactionId.isNotBlank())
        val tokens = StudioNativeTokens("$transactionId/theme")
        return PortableReceipt(
            "$transactionId/$RESOURCE/$FIELD",
            EffectOwner.SYSTEM,
            NativeFieldIdentity(RESOURCE, FIELD, transactionId),
            value(process, before, tokens),
            value(process, target, tokens),
            RestoreStatus.PENDING,
        )
    }

    fun decode(receipt: PortableReceipt): StudioThemeEvidence {
        require(receipt.owner == EffectOwner.SYSTEM)
        require(receipt.identity.resource == RESOURCE && receipt.identity.field == FIELD)
        require(receipt.id == "${receipt.identity.ownerToken}/$RESOURCE/$FIELD")
        val process = StudioNativeEvidence.readProcess(receipt.before)
        require(process == StudioNativeEvidence.readProcess(receipt.lastWritten))
        return StudioThemeEvidence(process, themeId(receipt.before), themeId(receipt.lastWritten))
    }

    fun blockedBy(receipts: List<PortableReceipt>): Boolean =
        receipts.any {
            it.identity.resource == RESOURCE &&
                it.identity.field == FIELD &&
                !StudioNativeEvidence.isTerminal(it.status)
        }

    private fun value(
        process: StudioProcess,
        theme: UIThemeLookAndFeelInfo,
        tokens: StudioNativeTokens,
    ): NativeValue {
        require(theme.id.isNotBlank()) { "Native theme has no logical identity" }
        return StudioNativeEvidence.envelope(
            process,
            StudioNativeEvidence.record(
                "id" to RawValue.Text(theme.id),
                "reference" to RawValue.Text(tokens.token(theme)),
            ),
        )
    }

    private fun themeId(native: NativeValue): String {
        val envelope = StudioNativeEvidence.fields(requireNotNull(native as? NativeValue.Present).value)
        val fields = StudioNativeEvidence.fields(envelope.getValue("value"))
        require(fields.keys == setOf("id", "reference"))
        require(requireNotNull(fields["reference"] as? RawValue.Text).value.isNotBlank())
        return requireNotNull(fields["id"] as? RawValue.Text).value.also { require(it.isNotBlank()) }
    }
}
