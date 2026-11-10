package dev.ayuislands.studio

import dev.ayuislands.studio.persistence.StagedFile
import dev.ayuislands.studio.persistence.StudioFiles
import java.io.IOException

internal enum class IoOperation { READ, STAGE, CREATE, WRITE, FORCE, CLOSE, REPLACE, REMOVE }

internal enum class IoBoundary { BEFORE, AFTER }

internal data class IoEvent(
    val operation: IoOperation,
    val name: String,
    val boundary: IoBoundary,
)

/** Faults wrap real temporary-directory IO; matching is ordered so a lost acknowledgement can precede a failed read. */
internal class FaultingStudioFiles(
    private val actual: StudioFiles,
) : StudioFiles by actual {
    private data class ScheduledFailure(
        val event: IoEvent,
        var remaining: Int,
    )

    private val failures = ArrayDeque<ScheduledFailure>()
    val observed = mutableListOf<IoEvent>()
    val pendingFailures: Int get() = failures.size

    fun arm(vararg events: IoEvent) {
        check(failures.isEmpty())
        failures.addAll(events.map { ScheduledFailure(it, 1) })
        observed.clear()
    }

    fun armAt(
        event: IoEvent,
        occurrence: Int,
    ) {
        check(failures.isEmpty() && occurrence > 0)
        failures.addLast(ScheduledFailure(event, occurrence))
        observed.clear()
    }

    override fun read(name: String): ByteArray? = around(IoOperation.READ, name) { actual.read(name) }

    override fun replace(
        source: String,
        destination: String,
    ) = around(IoOperation.REPLACE, destination) { actual.replace(source, destination) }

    override fun remove(name: String) = around(IoOperation.REMOVE, name) { actual.remove(name) }

    override fun stage(name: String): StagedFile = open(name, IoOperation.STAGE) { actual.stage(name) }

    override fun create(name: String): StagedFile = open(name, IoOperation.CREATE) { actual.create(name) }

    private fun open(
        name: String,
        operation: IoOperation,
        action: () -> StagedFile,
    ): StagedFile {
        visit(operation, name, IoBoundary.BEFORE)
        val opened = action()
        try {
            visit(operation, name, IoBoundary.AFTER)
        } catch (failure: IOException) {
            opened.close()
            throw failure
        }
        return object : StagedFile {
            override fun write(bytes: ByteArray) = around(IoOperation.WRITE, name) { opened.write(bytes) }

            override fun force() = around(IoOperation.FORCE, name) { opened.force() }

            override fun close() {
                try {
                    visit(IoOperation.CLOSE, name, IoBoundary.BEFORE)
                } finally {
                    opened.close()
                }
                visit(IoOperation.CLOSE, name, IoBoundary.AFTER)
            }
        }
    }

    private fun <T> around(
        operation: IoOperation,
        name: String,
        action: () -> T,
    ): T {
        visit(operation, name, IoBoundary.BEFORE)
        val result = action()
        visit(operation, name, IoBoundary.AFTER)
        return result
    }

    private fun visit(
        operation: IoOperation,
        name: String,
        boundary: IoBoundary,
    ) {
        val event = IoEvent(operation, name, boundary)
        observed.add(event)
        val failure = failures.firstOrNull()
        if (failure?.event == event) {
            failure.remaining--
            if (failure.remaining == 0) {
                failures.removeFirst()
                throw IOException("Injected $operation $boundary")
            }
        }
    }
}
