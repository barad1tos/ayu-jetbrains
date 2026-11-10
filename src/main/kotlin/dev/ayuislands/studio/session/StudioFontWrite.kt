package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.font.FontPreferencePolicy
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontSchemeLookup
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontWriteCheckpoint
import dev.ayuislands.font.FontWritePhase
import dev.ayuislands.font.FontWriteRecord
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus

/** Binds a journaled Font write to one native owner for the lifetime of an attempt. */
internal class StudioFontWrite private constructor(
    val identity: FontReceiptIdentity,
    private var boundScheme: EditorColorsScheme?,
    private val initial: FontWriteRecord,
    private var checkpoint: FontWriteCheckpoint?,
) {
    private var isUnavailable = false

    val record: FontWriteRecord get() = checkpoint?.record ?: initial

    val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                StudioFontReceipts.encode(identity, record).map { receipt ->
                    if (isUnavailable &&
                        !isTerminal(receipt)
                    ) {
                        receipt.copy(status = RestoreStatus.UNAVAILABLE)
                    } else {
                        receipt
                    }
                },
            )

    fun apply(census: FontSchemeCensus) {
        checkNotNull(resolve(census)) { "Font scheme identity is unavailable" }
        requireNotNull(checkpoint).apply()
        verifyTarget(census)
    }

    fun verifyTarget(census: FontSchemeCensus) {
        val scheme = checkNotNull(resolve(census)) { "Font scheme identity is unavailable" }
        check(FontPreferencePolicy.matches(identity.surface.captureExact(scheme), record.target)) {
            "Native font preferences changed during application"
        }
    }

    fun prepareRestore(
        census: FontSchemeCensus,
        blockedBy: List<PortableReceipt>,
    ) {
        if (receipts.all(::isTerminal) || record.applyPhase == FontWritePhase.PREPARED) return
        if (resolve(census) == null) return
        requireNotNull(checkpoint).prepareRestore(StudioFontReceipts.blockedFields(identity, blockedBy))
    }

    fun restore(
        census: FontSchemeCensus,
        blockedBy: List<PortableReceipt>,
    ): Boolean {
        if (receipts.all(::isTerminal)) return true
        if (record.applyPhase == FontWritePhase.PREPARED) return requireNotNull(checkpoint).restore()
        if (resolve(census) == null) return false
        return requireNotNull(checkpoint).restore(StudioFontReceipts.blockedFields(identity, blockedBy))
    }

    private fun resolve(census: FontSchemeCensus): EditorColorsScheme? {
        val found = (census.lookup(identity.resource) as? FontSchemeLookup.Unique)?.scheme
        val owner: EditorColorsScheme? = boundScheme ?: found
        if (owner == null || owner !== found || !identity.surface.isAvailable(owner)) {
            isUnavailable = true
            return null
        }
        if (checkpoint == null) checkpoint = FontWriteCheckpoint.recover(owner, identity.surface, initial)
        boundScheme = owner
        isUnavailable = false
        return owner
    }

    companion object {
        fun capture(
            identity: FontReceiptIdentity,
            scheme: EditorColorsScheme,
            target: FontSnapshot,
        ): StudioFontWrite {
            val captured = FontWriteCheckpoint(scheme, identity.surface, target)
            return StudioFontWrite(identity, scheme, captured.record, captured)
        }

        fun recover(
            identity: FontReceiptIdentity,
            receipts: List<PortableReceipt>,
        ): StudioFontWrite = StudioFontWrite(identity, null, StudioFontReceipts.decode(identity, receipts), null)

        private fun isTerminal(receipt: PortableReceipt): Boolean =
            receipt.status == RestoreStatus.RESTORED || receipt.status == RestoreStatus.FOREIGN
    }
}
