package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Adds the process-local semantic write to the same outer native acceptance and rollback. */
internal class StudioSyntaxOperation(
    private val schemes: StudioSchemeOperation,
    private val nativeContext: CoroutineContext,
    private val fonts: StudioSyntaxFonts?,
    private val lostFonts: StudioList<PortableReceipt> = StudioList(emptyList()),
) : StudioEffectOperation {
    override val receipts: StudioList<PortableReceipt>
        get() = StudioList(schemes.receipts + (fonts?.receipts ?: lostFonts))

    override val runtimeChanges: StudioList<RuntimeFieldChange> = StudioList(emptyList())

    override suspend fun apply() =
        withContext(nativeContext) {
            check(fonts != null) { "Syntax recovery cannot replay Apply" }
            check(fonts.apply()) { "Semantic styles changed before application" }
            schemes.apply()
        }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) = schemes.prepareRestore(blockedBy)

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        withContext(nativeContext) {
            fonts?.restore(blockedBy)
            schemes.restore(blockedBy)
        }

    override suspend fun accept() =
        withContext(nativeContext) {
            fonts?.accept()
            schemes.accept()
        }
}
