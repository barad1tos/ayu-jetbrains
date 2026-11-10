package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.StudioSessionRecord

/** Owns one session's deferred writes; all entry points and completions share one dispatcher. */
internal class StudioSessionJournal(
    private val scheduler: SessionScheduler,
    private val writer: SessionWriter,
    private val events: (SessionEvent) -> Unit,
) {
    private enum class Readiness { WAITING, READY }

    private data class Queued(
        val record: StudioSessionRecord,
        val readiness: Readiness,
        val completions: List<(DraftWriteResult) -> Unit> = emptyList(),
    )

    private data class Writing(
        val record: StudioSessionRecord,
        val attempt: Long,
        val completions: MutableList<(DraftWriteResult) -> Unit>,
    )

    private data class Acknowledged(
        val record: StudioSessionRecord,
        val revision: Long,
    )

    private var latest: StudioSessionRecord? = null
    private var queued: Queued? = null
    private var writing: Writing? = null
    private var acknowledged: Acknowledged? = null
    private var timer: ScheduledCall? = null
    private var timerGeneration = 0L
    private var attempt = 0L

    fun schedule(record: StudioSessionRecord) {
        if (record.availability != SessionAvailability.LIVE || problem(record) != null) return
        if (writing?.record == record || acknowledged?.record == record || queued?.record == record) return
        latest = record
        replaceQueued(Queued(record, Readiness.WAITING))
        cancelTimer()
        val generation = timerGeneration
        timer =
            scheduler.schedule(SAVE_DELAY_MILLIS) {
                if (generation == timerGeneration && queued?.record == record) {
                    queued = requireNotNull(queued).copy(readiness = Readiness.READY)
                    pump()
                }
            }
    }

    fun flush(
        record: StudioSessionRecord,
        completion: (DraftWriteResult) -> Unit,
    ) {
        val issue = problem(record)
        if (issue != null || record.availability != SessionAvailability.LIVE) {
            completion(DraftWriteResult.Failed(issue ?: "retired"))
            return
        }
        latest = record
        acknowledged?.takeIf { it.record == record }?.let {
            completion(DraftWriteResult.Saved(it.revision))
            return
        }
        writing?.takeIf { it.record == record }?.let {
            it.completions.add(completion)
            return
        }
        cancelTimer()
        val callbacks = queued?.takeIf { it.record == record }?.completions.orEmpty() + completion
        replaceQueued(Queued(record, Readiness.READY, callbacks))
        pump()
    }

    fun retire(record: StudioSessionRecord) {
        require(record.availability != SessionAvailability.LIVE)
        if (problem(record) != null) return
        latest = record
        cancelTimer()
        if (writing?.record == record || acknowledged?.record == record) return
        replaceQueued(Queued(record, Readiness.READY))
        pump()
    }

    private fun problem(record: StudioSessionRecord): String? {
        val previous = latest ?: return null
        return when {
            previous.id != record.id || previous.target != record.target -> "foreign-session"
            previous.availability != SessionAvailability.LIVE && record.availability == SessionAvailability.LIVE ->
                "retired"
            record.generation < previous.generation -> "stale-generation"
            record.generation == previous.generation && record != previous -> "conflicting-generation"
            else -> null
        }
    }

    private fun replaceQueued(next: Queued) {
        val previous = queued
        queued = next
        if (previous != null && previous.record != next.record) {
            previous.completions.forEach { it(DraftWriteResult.Failed("superseded")) }
        }
    }

    private fun pump() {
        if (writing != null) return
        val next = queued?.takeIf { it.readiness == Readiness.READY } ?: return
        queued = null
        cancelTimer()
        val active = Writing(next.record, ++attempt, next.completions.toMutableList())
        writing = active
        writer.write(active.record) { result -> complete(active, result) }
    }

    private fun complete(
        active: Writing,
        result: DraftWriteResult,
    ) {
        if (writing !== active) return
        writing = null
        val event =
            when (result) {
                is DraftWriteResult.Saved -> {
                    acknowledged = Acknowledged(active.record, result.revision)
                    SessionJournalEvent.Saved(result.revision)
                }
                is DraftWriteResult.Failed -> SessionJournalEvent.Failed(result.code)
            }
        val stamp = CallbackStamp(SessionTransitions.journalStamp(active.record), "save:${active.attempt}")
        events(SessionEvent.JournalCompleted(stamp, event))
        active.completions.toList().forEach { it(result) }
        pump()
    }

    private fun cancelTimer() {
        timerGeneration++
        timer?.cancel()
        timer = null
    }

    private companion object {
        const val SAVE_DELAY_MILLIS = 250L
    }
}
