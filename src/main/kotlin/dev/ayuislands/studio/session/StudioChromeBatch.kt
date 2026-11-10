package dev.ayuislands.studio.session

import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import javax.swing.SwingUtilities

internal data class StudioChromeEntry(
    val surface: StudioChromeSurface?,
    val membership: StudioAccentCell?,
)

/** The outer Accent operation places non-Chrome writes between these independently retained phases. */
internal class StudioChromeBatch(
    val membershipBarrier: StudioAccentWrite,
    val elements: StudioAccentWrite,
) {
    val receipts: StudioList<PortableReceipt>
        get() = StudioList(membershipBarrier.receipts + elements.receipts)
}

/** Preserves extension order and each external membership's dependency on its native surface. */
internal class StudioChromeElements(
    private val entries: List<StudioChromeEntry>,
) : StudioAccentWrite {
    private var phase = Phase.PREPARED

    override val receipts: StudioList<PortableReceipt>
        get() = StudioList(entries.flatMap { it.membership?.receipts.orEmpty() + it.surface?.receipts.orEmpty() })

    override fun apply() {
        check(SwingUtilities.isEventDispatchThread()) { "Chrome application must run on EDT" }
        check(phase == Phase.PREPARED) { "Chrome application was already started" }
        phase = Phase.APPLYING
        entries.forEach {
            it.membership?.apply()
            it.surface?.apply()
        }
        phase = Phase.APPLIED
    }

    override fun restore(blockedBy: List<PortableReceipt>) {
        check(SwingUtilities.isEventDispatchThread()) { "Chrome restoration must run on EDT" }
        check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted Chrome cannot be restored" }
        if (phase == Phase.RESTORED) return
        phase = Phase.RESTORING
        val newer = blockedBy.toMutableList()
        var failure: Throwable? = null
        for ((surface, membership) in entries.asReversed()) {
            failure = restoreStep(failure) { surface?.restore(newer) }
            newer += surface?.receipts.orEmpty()
            failure = restoreStep(failure) { membership?.restore(newer) }
            newer += membership?.receipts.orEmpty()
        }
        failure?.let { throw it }
        phase = Phase.RESTORED
    }

    override fun accept() {
        check(SwingUtilities.isEventDispatchThread()) { "Chrome acceptance must run on EDT" }
        if (phase == Phase.ACCEPTED) return
        check(phase == Phase.APPLIED || phase == Phase.ACCEPTING) { "Chrome application has not completed" }
        phase = Phase.ACCEPTING
        entries.forEach { it.surface?.accept() }
        entries.forEach { it.membership?.accept() }
        phase = Phase.ACCEPTED
    }

    private fun restoreStep(
        previous: Throwable?,
        action: () -> Unit,
    ): Throwable? {
        val failure = runCatching(action).exceptionOrNull() ?: return previous
        if (failure is VirtualMachineError) throw failure
        return combineUiFailures(previous, failure)
    }

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, ACCEPTING, ACCEPTED }
}
