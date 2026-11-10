package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptPolicy
import java.time.Instant

/** Pure evidence for ephemeral native references; it never resolves or reconstructs platform objects. */
internal object StudioNativeEvidence {
    private const val MAX_NANOSECOND = 999_999_999

    fun isTerminal(status: RestoreStatus): Boolean = StudioReceiptPolicy.isTerminal(status)

    fun reference(token: String?): RawValue {
        if (token == null) return RawValue.Null
        require(token.isNotBlank()) { "Native reference identity is unavailable" }
        return RawValue.Text(token)
    }

    fun envelope(
        origin: StudioProcess,
        value: RawValue,
    ): NativeValue =
        NativeValue.Present(
            record(
                "version" to RawValue.Integer(1),
                "process" to processValue(origin),
                "value" to value,
            ),
        )

    private fun processValue(origin: StudioProcess): RawValue {
        val start =
            origin.startedAt?.let {
                record(
                    "seconds" to RawValue.LongValue(it.epochSecond),
                    "nanos" to RawValue.Integer(it.nano),
                )
            } ?: RawValue.Null
        return record("pid" to RawValue.LongValue(origin.pid), "start" to start)
    }

    fun readProcess(native: NativeValue): StudioProcess {
        val payload = fields(requireNotNull(native as? NativeValue.Present).value)
        require(payload.keys == setOf("version", "process", "value") && payload["version"] == RawValue.Integer(1))
        val identity = fields(payload.getValue("process"))
        require(identity.keys == setOf("pid", "start"))
        val pid = requireNotNull(identity["pid"] as? RawValue.LongValue).value
        val rawStart = identity.getValue("start")
        val start =
            if (rawStart == RawValue.Null) {
                null
            } else {
                val timestamp = fields(rawStart)
                require(timestamp.keys == setOf("seconds", "nanos"))
                val seconds = requireNotNull(timestamp["seconds"] as? RawValue.LongValue).value
                val nanos = requireNotNull(timestamp["nanos"] as? RawValue.Integer).value
                require(nanos in 0..MAX_NANOSECOND) { "Invalid native process timestamp" }
                Instant.ofEpochSecond(seconds, nanos.toLong())
            }
        return StudioProcess(pid, start)
    }

    fun fields(value: RawValue): Map<String, RawValue> {
        val entries = requireNotNull(value as? RawValue.OrderedMap).entries
        require(entries.map { it.key }.distinct().size == entries.size) { "Duplicate native evidence field" }
        return entries.associate { it.key to it.value }
    }

    fun record(vararg values: Pair<String, RawValue>): RawValue = record(values.asList())

    fun record(values: List<Pair<String, RawValue>>): RawValue =
        RawValue.OrderedMap(StudioList(values.map { (key, value) -> RawValue.Entry(key, value) }))
}
