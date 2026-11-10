package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Shared focus and manager effects settle together; the outer lease alone releases callback holds. */
internal class StudioGlowOperation(
    private val surfaces: List<StudioGlowSurfaces>,
    private val focus: List<StudioGlowFocusWrite>,
    private val additions: List<StudioGlowFocusAdd>,
    private val nativeContext: CoroutineContext,
) : StudioEffectOperation {
    private var phase = Phase.PREPARED

    override val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                buildList {
                    surfaces.forEach { addAll(it.receipts) }
                    focus.forEach { addAll(it.receipts) }
                    additions.forEach { addAll(it.receipts) }
                },
            )

    override val runtimeChanges: StudioList<RuntimeFieldChange> = StudioList(emptyList())

    override suspend fun apply() =
        withContext(nativeContext) {
            check(phase == Phase.PREPARED) { "Glow application was already started" }
            phase = Phase.APPLYING
            surfaces.forEach { it.pause() }
            surfaces.forEach { it.apply() }
            focus.forEach { it.apply() }
            additions.forEach { it.apply() }
            surfaces.forEach { it.start() }
            phase = Phase.APPLIED
        }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) =
        withContext(nativeContext) {
            check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted Glow cannot be restored" }
            when (phase) {
                Phase.RESTORED -> Unit

                Phase.FIELDS_RESTORED -> {
                    surfaces.asReversed().forEach { it.preparePlaybackRestore(blockedBy) }
                }

                else -> {
                    focus.asReversed().forEach { it.prepareRestore() }
                    surfaces.asReversed().forEach { it.prepareFieldsRestore() }
                }
            }
        }

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        withContext(nativeContext) {
            check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted Glow cannot be restored" }
            if (phase == Phase.RESTORED) return@withContext StudioRestoreContinuation.NONE
            if (phase != Phase.FIELDS_RESTORED) {
                surfaces.asReversed().forEach { it.stop(blockedBy) }
                additions.asReversed().forEach { it.restore(blockedBy) }
                focus.asReversed().forEach { it.restore(blockedBy) }
                surfaces.asReversed().forEach { it.restoreFields(blockedBy) }
                phase = Phase.FIELDS_RESTORED
                return@withContext StudioRestoreContinuation.REQUIRED
            }
            var isComplete = true
            for (surface in surfaces.asReversed()) {
                if (!surface.restorePlayback(blockedBy)) isComplete = false
            }
            if (!isComplete) return@withContext StudioRestoreContinuation.REQUIRED
            phase = Phase.RESTORED
            StudioRestoreContinuation.NONE
        }

    override suspend fun accept() =
        withContext(nativeContext) {
            if (phase == Phase.ACCEPTED) return@withContext
            check(phase == Phase.APPLIED || phase == Phase.ACCEPTING) { "Glow application has not completed" }
            phase = Phase.ACCEPTING
            surfaces.forEach { it.acceptPlayback() }
            focus.forEach { it.accept() }
            additions.forEach { it.accept() }
            surfaces.forEach { it.acceptFields() }
            surfaces.forEach { it.registerCallbacks() }
            phase = Phase.ACCEPTED
        }

    private enum class Phase { PREPARED, APPLYING, APPLIED, FIELDS_RESTORED, RESTORED, ACCEPTING, ACCEPTED }
}
