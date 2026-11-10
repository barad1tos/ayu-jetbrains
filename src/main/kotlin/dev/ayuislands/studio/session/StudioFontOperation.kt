package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.font.FontIdentityCheckpoint
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** A Font operation has no persistence authority; outer acceptance owns all receipt acknowledgements. */
internal class StudioFontOperation(
    private val host: StudioFontHost,
    private val nativeContext: CoroutineContext,
    private val token: String,
    private val writes: List<StudioFontWrite>,
    private val adoption: FontIdentityCheckpoint?,
    private val prepared: StudioList<RuntimeFieldChange>?,
    private val expectedActive: EditorColorsScheme?,
    private val reservedKeys: Set<String>,
) : StudioEffectOperation {
    private var hasApplied = false

    override val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                listOfNotNull(
                    adoption?.let { StudioSchemeIdentityReceipt.encode(EffectOwner.FONT, token, it.record) },
                ) +
                    writes.flatMap { it.receipts },
            )

    override val runtimeChanges: StudioList<RuntimeFieldChange>
        get() = if (hasApplied) checkNotNull(prepared) else StudioList(emptyList())

    override suspend fun apply() =
        withContext(nativeContext) {
            check(prepared != null && !hasApplied) { "Font recovery cannot replay Apply" }
            requireActive()
            adoption?.apply(census(), reservedKeys)
            for (write in writes) {
                requireActive()
                write.apply(census())
            }
            host.refresh()
            requireActive()
            for (write in writes) write.verifyTarget(census())
            hasApplied = true
        }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) =
        withContext(nativeContext) {
            for (write in writes.asReversed()) write.prepareRestore(census(), blockedBy)
        }

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>) =
        withContext(nativeContext) {
            hasApplied = false
            for (write in writes.asReversed()) write.restore(census(), blockedBy)
            adoption?.let { metadata ->
                val id = metadata.record.id
                val dependentsSettled =
                    writes.flatMap { it.receipts }.all(::isTerminal) &&
                        blockedBy.none { it.identity.resource == id && !isTerminal(it) }
                metadata.restore(census(), dependentsSettled)
            }
            host.refresh()
            StudioRestoreContinuation.NONE
        }

    override suspend fun accept() = withContext(nativeContext) { host.refresh() }

    private fun census(): FontSchemeCensus = FontSchemeCensus(host.observe().schemes.toTypedArray())

    private fun requireActive() {
        check(expectedActive == null || host.observe().active === expectedActive) { "Active Font scheme changed" }
    }

    private fun isTerminal(receipt: PortableReceipt): Boolean =
        receipt.status == RestoreStatus.RESTORED || receipt.status == RestoreStatus.FOREIGN
}
