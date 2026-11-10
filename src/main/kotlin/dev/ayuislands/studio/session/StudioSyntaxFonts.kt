package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.syntax.SyntaxFontPhase
import dev.ayuislands.syntax.SyntaxFontSnapshot
import dev.ayuislands.syntax.SyntaxFontWrite

/** Retains the live semantic write; its portable evidence never reconstructs a lost process-local handle. */
internal class StudioSyntaxFonts(
    private val write: SyntaxFontWrite,
    ownerToken: String,
    process: StudioProcess,
    tokens: StudioNativeTokens,
) {
    init {
        require(ownerToken.isNotBlank()) { "Semantic style ownership identity is unavailable" }
        require(write.phase == SyntaxFontPhase.PREPARED) { "Semantic styles must be captured before application" }
    }

    private val identity = NativeFieldIdentity(RESOURCE, FIELD, ownerToken)
    private val captured =
        PortableReceipt(
            "$ownerToken/SYNTAX/${identity.resource}/${identity.field}",
            EffectOwner.SYNTAX,
            identity,
            StudioNativeEvidence.envelope(process, encode(write.before, tokens)),
            StudioNativeEvidence.envelope(process, encode(write.planned, tokens)),
            RestoreStatus.PENDING,
        )

    val receipts: StudioList<PortableReceipt>
        get() = StudioList(listOf(captured.copy(status = status())))

    fun apply(): Boolean = write.apply()

    /** A foreign value is terminal preservation; an unresolved newer writer leaves this undo pending. */
    fun restore(blockedBy: StudioList<PortableReceipt>): Boolean {
        val isBlocked =
            blockedBy.any {
                it.identity.resource == identity.resource &&
                    it.identity.field == identity.field &&
                    !StudioNativeEvidence.isTerminal(it.status)
            }
        if (write.phase == SyntaxFontPhase.APPLIED && isBlocked) return false
        write.restore()
        return true
    }

    fun accept() = write.accept()

    private fun status(): RestoreStatus =
        when (write.phase) {
            SyntaxFontPhase.PREPARED -> RestoreStatus.PENDING
            SyntaxFontPhase.APPLIED, SyntaxFontPhase.ACCEPTED -> RestoreStatus.OWNED
            SyntaxFontPhase.RESTORED -> RestoreStatus.RESTORED
            SyntaxFontPhase.FOREIGN -> RestoreStatus.FOREIGN
        }

    private fun encode(
        snapshot: SyntaxFontSnapshot,
        tokens: StudioNativeTokens,
    ): RawValue =
        StudioNativeEvidence.record(
            "reference" to StudioNativeEvidence.reference(tokens.token(snapshot)),
            "styles" to
                StudioNativeEvidence.record(
                    snapshot.styles.map { (language, categories) ->
                        language to
                            StudioNativeEvidence.record(
                                categories.map { (category, style) -> category to RawValue.Integer(style) },
                            )
                    },
                ),
        )

    companion object {
        private const val RESOURCE = "syntax/runtime"
        private const val FIELD = "semantic-fonts"

        /** Only a confirmed process change settles a lost semantic handle; persistent scheme fields are separate. */
        fun recoverLost(
            receipts: List<PortableReceipt>,
            currentProcess: StudioProcess?,
        ): StudioList<PortableReceipt> =
            StudioList(
                receipts.map { receipt ->
                    require(receipt.owner == EffectOwner.SYNTAX)
                    require(receipt.identity.resource == RESOURCE && receipt.identity.field == FIELD)
                    if (StudioNativeEvidence.isTerminal(receipt.status)) {
                        receipt
                    } else {
                        val origin = StudioNativeEvidence.readProcess(receipt.before)
                        require(origin == StudioNativeEvidence.readProcess(receipt.lastWritten)) {
                            "Semantic receipt process provenance differs"
                        }
                        val canSettle =
                            StudioProcessPolicy.compare(origin, currentProcess) == StudioProcessRelation.DIFFERENT
                        receipt.copy(status = if (canSettle) RestoreStatus.RESTORED else RestoreStatus.UNAVAILABLE)
                    }
                },
            )
    }
}
