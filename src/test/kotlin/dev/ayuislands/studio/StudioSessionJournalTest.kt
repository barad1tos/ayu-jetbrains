package dev.ayuislands.studio

import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.JournalAcknowledgement
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.session.ScheduledCall
import dev.ayuislands.studio.session.SessionScheduler
import dev.ayuislands.studio.session.SessionWriter
import dev.ayuislands.studio.session.StudioSessionJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSessionJournalTest {
    @Test
    fun `quiet period coalesces exact records at 250ms without delaying local edits`() {
        val fixture = journalFixture()
        val first = journalRecord(catalogTestDocument()).copy(generation = 1)
        val latest = first.copy(generation = 2)
        fixture.journal.schedule(first)
        fixture.scheduler.advance(249)
        assertTrue(fixture.writer.requests.isEmpty())
        fixture.journal.schedule(latest)
        fixture.scheduler.advance(249)
        assertTrue(fixture.writer.requests.isEmpty())
        fixture.scheduler.advance(1)
        assertEquals(listOf(latest), fixture.writer.requests.map { it.record })
        assertTrue(fixture.events.isEmpty())
        fixture.writer.complete(0, DraftWriteResult.Saved(7))
        val acknowledgement = fixture.events.single()
        assertEquals(latest.generation, acknowledgement.stamp.operation.generation)
        assertEquals(DraftWriteResult.Saved(7), acknowledgement.result)
    }

    @Test
    fun `flush waits for the exact frozen generation behind an earlier in flight save`() {
        val fixture = journalFixture()
        val first = journalRecord(catalogTestDocument()).copy(generation = 1)
        val latest = first.copy(generation = 2)
        fixture.journal.schedule(first)
        fixture.scheduler.advance(250)
        fixture.journal.schedule(latest)
        val results = mutableListOf<DraftWriteResult>()
        fixture.journal.flush(latest, results::add)
        assertEquals(1, fixture.writer.requests.size)
        assertTrue(results.isEmpty())
        fixture.writer.complete(0, DraftWriteResult.Saved(3))
        assertEquals(listOf(first, latest), fixture.writer.requests.map { it.record })
        assertTrue(results.isEmpty())
        fixture.writer.complete(1, DraftWriteResult.Saved(4))
        assertEquals(listOf<DraftWriteResult>(DraftWriteResult.Saved(4)), results)
        fixture.scheduler.advance(250)
        assertEquals(2, fixture.writer.requests.size)
    }

    @Test
    fun `failed save retains exact work and a fresh retry acknowledgement is distinguishable`() {
        val fixture = journalFixture()
        val record = journalRecord(catalogTestDocument())
        fixture.journal.schedule(record)
        fixture.scheduler.advance(250)
        fixture.writer.complete(0, DraftWriteResult.Failed("io"))
        val retried = mutableListOf<DraftWriteResult>()
        fixture.journal.flush(record, retried::add)
        assertEquals(listOf(record, record), fixture.writer.requests.map { it.record })
        fixture.writer.complete(1, DraftWriteResult.Saved(8))
        assertEquals(listOf<DraftWriteResult>(DraftWriteResult.Saved(8)), retried)
        val callbacks = fixture.events.map { it.stamp }
        assertEquals(2, callbacks.distinct().size)
        fixture.writer.complete(0, DraftWriteResult.Saved(3))
        assertEquals(2, fixture.events.size)
    }

    @Test
    fun `retirement wins over queued timers and late save completions`() {
        val fixture = journalFixture()
        val record = journalRecord(catalogTestDocument())
        fixture.journal.schedule(record)
        fixture.scheduler.advance(250)
        val retired = record.copy(generation = 1, availability = SessionAvailability.RETIRED)
        fixture.journal.retire(retired)
        fixture.scheduler.fireCancelled()
        assertEquals(listOf(record), fixture.writer.requests.map { it.record })
        fixture.writer.complete(0, DraftWriteResult.Saved(2))
        assertEquals(listOf(record, retired), fixture.writer.requests.map { it.record })
        fixture.writer.complete(1, DraftWriteResult.Failed("discard-unconfirmed"))
        fixture.journal.schedule(record.copy(generation = 2))
        fixture.scheduler.advance(1000)
        assertEquals(2, fixture.writer.requests.size)
        val results = mutableListOf<DraftWriteResult>()
        fixture.journal.flush(record.copy(generation = 2), results::add)
        assertIs<DraftWriteResult.Failed>(results.single())
    }

    @Test
    fun `superseded timer and duplicate flush cannot write an older snapshot`() {
        val fixture = journalFixture()
        val first = journalRecord(catalogTestDocument())
        val latest = first.copy(generation = 1)
        fixture.journal.schedule(first)
        fixture.journal.schedule(latest)
        fixture.scheduler.fireCancelled()
        assertTrue(fixture.writer.requests.isEmpty())
        val results = mutableListOf<DraftWriteResult>()
        fixture.journal.flush(latest, results::add)
        fixture.journal.flush(latest, results::add)
        assertEquals(listOf(latest), fixture.writer.requests.map { it.record })
        fixture.writer.complete(0, DraftWriteResult.Saved(9))
        assertEquals(listOf<DraftWriteResult>(DraftWriteResult.Saved(9), DraftWriteResult.Saved(9)), results)
        val stale = mutableListOf<DraftWriteResult>()
        fixture.journal.flush(first, stale::add)
        assertIs<DraftWriteResult.Failed>(stale.single())
    }

    @Test
    fun `superseded flush can schedule a newer draft without losing its timer`() {
        val fixture = journalFixture()
        val first = journalRecord(catalogTestDocument())
        val waiting = first.copy(generation = 1)
        val intermediate = first.copy(generation = 2)
        val latest = first.copy(generation = 3)
        fixture.journal.schedule(first)
        fixture.scheduler.advance(250)
        fixture.journal.flush(waiting) { result ->
            assertEquals(DraftWriteResult.Failed("superseded"), result)
            fixture.journal.schedule(latest)
        }

        fixture.journal.schedule(intermediate)
        fixture.writer.complete(0, DraftWriteResult.Saved(7))
        fixture.scheduler.advance(249)
        assertEquals(listOf(first), fixture.writer.requests.map { it.record })
        fixture.scheduler.advance(1)

        assertEquals(listOf(first, latest), fixture.writer.requests.map { it.record })
    }

    private fun journalFixture(): JournalFixture {
        val scheduler = ManualSessionScheduler()
        val writer = ManualSessionWriter()
        val events = mutableListOf<JournalAcknowledgement>()
        return JournalFixture(scheduler, writer, events, StudioSessionJournal(scheduler, writer, events::add))
    }

    private data class JournalFixture(
        val scheduler: ManualSessionScheduler,
        val writer: ManualSessionWriter,
        val events: MutableList<JournalAcknowledgement>,
        val journal: StudioSessionJournal,
    )
}

private class ManualSessionScheduler : SessionScheduler {
    private data class Task(
        val due: Long,
        val action: () -> Unit,
        var isCancelled: Boolean = false,
    )

    private var time = 0L
    private val tasks = mutableListOf<Task>()
    private val cancelled = mutableListOf<Task>()

    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ): ScheduledCall {
        val task = Task(time + delayMillis, action)
        tasks.add(task)
        return ScheduledCall {
            task.isCancelled = true
            cancelled.add(task)
        }
    }

    fun advance(millis: Long) {
        time += millis
        val due = tasks.filter { it.due <= time }
        tasks.removeAll(due.toSet())
        due.filterNot { it.isCancelled }.forEach { it.action() }
    }

    fun fireCancelled() {
        val callbacks = cancelled.toList()
        cancelled.clear()
        callbacks.forEach { it.action() }
    }
}

private class ManualSessionWriter : SessionWriter {
    data class Request(
        val record: StudioSessionRecord,
        val completion: (DraftWriteResult) -> Unit,
    )

    val requests = mutableListOf<Request>()

    override fun write(
        record: StudioSessionRecord,
        completion: (DraftWriteResult) -> Unit,
    ) {
        requests.add(Request(record, completion))
    }

    fun complete(
        index: Int,
        result: DraftWriteResult,
    ) {
        requests[index].completion(result)
    }
}
