package dev.ayuislands.studio.session

import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Keeps Accent surfaces under one receipt lifetime and publishes their result only after native refresh. */
internal class StudioAccentOperation(
    fields: List<StudioAccentWrite>,
    private val schemes: StudioSchemeOperation,
    private val integrations: StudioEffectOperation,
    private val nativeContext: CoroutineContext,
    private val refresh: () -> Unit,
    private val confirm: (suspend () -> Unit)?,
    private val preparedChanges: StudioList<RuntimeFieldChange>,
    private val lostFields: StudioList<PortableReceipt> = StudioList(emptyList()),
) : StudioEffectOperation {
    private val fields = fields.toList()
    private var phase = Phase.PREPARED

    override val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                buildList {
                    fields.forEach { addAll(it.receipts) }
                    addAll(lostFields)
                    addAll(schemes.receipts)
                    addAll(integrations.receipts)
                },
            )

    override val runtimeChanges: StudioList<RuntimeFieldChange>
        get() =
            when (phase) {
                Phase.APPLIED, Phase.ACCEPTING, Phase.ACCEPTED -> {
                    StudioList(preparedChanges + integrations.runtimeChanges)
                }

                else -> {
                    StudioList(emptyList())
                }
            }

    override suspend fun apply(): Unit =
        withContext(nativeContext) {
            val publish = checkNotNull(confirm) { "Accent recovery cannot replay application" }
            check(phase == Phase.PREPARED) { "Accent application cannot be replayed" }
            phase = Phase.APPLYING
            fields.forEach { it.apply() }
            schemes.apply()
            integrations.apply()
            refresh()
            publish()
            phase = Phase.APPLIED
        }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>): Unit =
        withContext(nativeContext) {
            checkRestorable()
            if (phase == Phase.RESTORED) return@withContext
            var failure = attempt { integrations.prepareRestore(blockedBy) }
            failure =
                accumulate(failure, attempt { schemes.prepareRestore(StudioList(blockedBy + integrations.receipts)) })
            failure?.let { throw it }
        }

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        withContext(nativeContext) {
            checkRestorable()
            if (phase == Phase.RESTORED) return@withContext StudioRestoreContinuation.NONE
            phase = Phase.RESTORING
            var continuation = StudioRestoreContinuation.NONE
            var failure =
                attempt {
                    if (integrations.restore(blockedBy) == StudioRestoreContinuation.REQUIRED) {
                        continuation = StudioRestoreContinuation.REQUIRED
                    }
                }
            val integrationBlockers = StudioList(blockedBy + integrations.receipts)
            failure =
                accumulate(
                    failure,
                    attempt {
                        if (schemes.restore(integrationBlockers) == StudioRestoreContinuation.REQUIRED) {
                            continuation = StudioRestoreContinuation.REQUIRED
                        }
                    },
                )
            if (continuation == StudioRestoreContinuation.REQUIRED && failure == null) {
                return@withContext StudioRestoreContinuation.REQUIRED
            }
            failure = restoreFields(integrationBlockers + schemes.receipts, failure)
            failure?.let { throw it }
            if (continuation == StudioRestoreContinuation.NONE &&
                receipts.all { StudioNativeEvidence.isTerminal(it.status) }
            ) {
                phase = Phase.RESTORED
            }
            continuation
        }

    private suspend fun restoreFields(
        blockedBy: List<PortableReceipt>,
        previousFailure: Throwable?,
    ): Throwable? {
        val newer = blockedBy.toMutableList()
        var failure = previousFailure
        for (field in fields.asReversed()) {
            failure = accumulate(failure, attempt { field.restore(newer) })
            newer += field.receipts
        }
        return accumulate(failure, attempt { refresh() })
    }

    override suspend fun accept(): Unit =
        withContext(nativeContext) {
            if (phase == Phase.ACCEPTED) return@withContext
            check(confirm == null || phase == Phase.APPLIED || phase == Phase.ACCEPTING) {
                "Accent application has not completed"
            }
            phase = Phase.ACCEPTING
            fields.forEach { it.accept() }
            schemes.accept()
            integrations.accept()
            phase = Phase.ACCEPTED
        }

    private fun checkRestorable() {
        check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted Accent cannot be restored" }
    }

    private suspend fun attempt(action: suspend () -> Unit): Throwable? =
        runCatching { action() }.exceptionOrNull().also { failure ->
            if (failure is VirtualMachineError) throw failure
        }

    private fun accumulate(
        previous: Throwable?,
        next: Throwable?,
    ): Throwable? = if (next == null) previous else combineUiFailures(previous, next)

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, ACCEPTING, ACCEPTED }
}
