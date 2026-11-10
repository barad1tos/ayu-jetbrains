package dev.ayuislands.studio.session

import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.StudioReceiptPolicy
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Retains the complete project census until one durable decision settles native and persistent Workspace effects. */
internal class StudioWorkspaceOperation(
    fields: List<StudioWorkspaceFields>,
    private val nativeContext: CoroutineContext,
    private val publication: (() -> StudioList<RuntimeFieldChange>)?,
    private val properties: StudioWorkspaceProperties? = null,
    private val root: StudioWorkspaceRoot? = null,
    private val lostFields: StudioList<PortableReceipt> = StudioList(emptyList()),
) : StudioEffectOperation {
    private val fields = fields.toList()
    private var phase = Phase.PREPARED
    private var published = StudioList<RuntimeFieldChange>(emptyList())

    init {
        properties?.sealCapture()
    }

    override val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                buildList {
                    addAll(root?.receipts.orEmpty())
                    fields.forEach { addAll(it.receipts) }
                    addAll(properties?.receipts.orEmpty())
                    addAll(lostFields)
                },
            )

    override val runtimeChanges: StudioList<RuntimeFieldChange>
        get() =
            when (phase) {
                Phase.APPLIED, Phase.ACCEPTING, Phase.ACCEPTED -> published
                else -> StudioList(emptyList())
            }

    override suspend fun apply(): Unit =
        withContext(nativeContext) {
            val publish = checkNotNull(publication) { "Workspace recovery cannot replay application" }
            check(phase == Phase.PREPARED) { "Workspace application cannot be replayed" }
            phase = Phase.APPLYING
            var failure =
                attempt {
                    root?.apply()
                    fields.forEach { it.apply() }
                    properties?.observe()
                    published = publish()
                    phase = Phase.APPLIED
                }
            if (failure != null) failure = accumulate(failure, attempt { properties?.observe() })
            failure?.let { throw it }
        }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>): Unit =
        withContext(nativeContext) {
            checkRestorable()
            if (phase == Phase.RESTORED) return@withContext
            var failure = attempt { properties?.prepareRestore(blockedBy) }
            failure = accumulate(failure, attempt { root?.prepareRestore(blockedBy) })
            failure?.let { throw it }
        }

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        withContext(nativeContext) {
            checkRestorable()
            if (phase == Phase.RESTORED) return@withContext StudioRestoreContinuation.NONE
            phase = Phase.RESTORING
            var failure: Throwable? = null
            val newer = blockedBy.toMutableList()
            for (field in fields.asReversed()) {
                failure = accumulate(failure, attempt { field.restore(newer) })
                newer += field.receipts
            }
            failure = accumulate(failure, attempt { properties?.observe() })
            if (fields.none { it.hasPendingGeometry }) {
                failure = accumulate(failure, attempt { properties?.restore(newer) })
            }
            failure = accumulate(failure, attempt { root?.restore(newer + properties?.receipts.orEmpty()) })
            failure?.let { throw it }
            if (receipts.all(StudioReceiptPolicy::isTerminal)) phase = Phase.RESTORED
            StudioRestoreContinuation.NONE
        }

    override suspend fun accept(): Unit =
        withContext(nativeContext) {
            if (phase == Phase.ACCEPTED) return@withContext
            check(publication == null || phase == Phase.APPLIED || phase == Phase.ACCEPTING) {
                "Workspace application has not completed"
            }
            phase = Phase.ACCEPTING
            var failure: Throwable? = null
            for (field in fields) failure = accumulate(failure, attempt { field.accept() })
            failure = accumulate(failure, attempt { properties?.accept() })
            failure = accumulate(failure, attempt { root?.accept() })
            failure?.let { throw it }
            phase = Phase.ACCEPTED
        }

    private fun checkRestorable() {
        check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted Workspace cannot be restored" }
    }

    private fun attempt(action: () -> Unit): Throwable? = runCatchingPreservingCancellation(action).exceptionOrNull()

    private fun accumulate(
        previous: Throwable?,
        next: Throwable?,
    ): Throwable? = if (next == null) previous else combineUiFailures(previous, next)

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, ACCEPTING, ACCEPTED }
}
