package dev.ayuislands.studio.session

import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontFamily
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList

/** Exact attempt snapshots; never uses the legacy managed-baseline envelope or its numeric policy. */
internal object StudioFontCodec {
    fun encode(snapshot: FontSnapshot): RawValue =
        when (snapshot) {
            FontSnapshot.Inherited -> record("version" to RawValue.Integer(1), "mode" to RawValue.Text("inherited"))
            is FontSnapshot.Explicit ->
                record(
                    "version" to RawValue.Integer(1),
                    "mode" to RawValue.Text("explicit"),
                    "data" to encodeData(snapshot.preferences),
                )
        }

    fun decode(value: RawValue): FontSnapshot {
        val fields = readFields(value)
        require(fields["version"] == RawValue.Integer(1)) { "Unknown Font checkpoint version" }
        return when (fields["mode"]) {
            RawValue.Text("inherited") -> {
                require(fields.keys == setOf("version", "mode"))
                FontSnapshot.Inherited
            }
            RawValue.Text("explicit") -> {
                require(fields.keys == setOf("version", "mode", "data"))
                FontSnapshot.Explicit(decodeData(fields.getValue("data")))
            }
            else -> throw IllegalArgumentException("Unknown Font checkpoint mode")
        }
    }

    private fun encodeData(data: FontData): RawValue =
        record(
            "effective" to RawValue.OrderedList(StudioList(data.effectiveFamilies.map(RawValue::Text))),
            "families" to
                RawValue.OrderedList(
                    StudioList(
                        data.families.map { family ->
                            record(
                                "name" to RawValue.Text(family.name),
                                "size" to (family.size?.let { RawValue.FloatBits(it.toRawBits()) } ?: RawValue.Null),
                            )
                        },
                    ),
                ),
            "template" to RawValue.FloatBits(data.templateSize.toRawBits()),
            "spacing" to RawValue.FloatBits(data.lineSpacing.toRawBits()),
            "ligatures" to RawValue.BooleanValue(data.ligatures),
            "regular" to (data.regularSubFamily?.let(RawValue::Text) ?: RawValue.Null),
            "bold" to (data.boldSubFamily?.let(RawValue::Text) ?: RawValue.Null),
        )

    private fun decodeData(value: RawValue): FontData {
        val fields = readFields(value)
        require(fields.keys == setOf("effective", "families", "template", "spacing", "ligatures", "regular", "bold"))
        return FontData(
            list(fields.getValue("effective")).map { requireNotNull(text(it)) },
            list(fields.getValue("families")).map { item ->
                val family = readFields(item)
                require(family.keys == setOf("name", "size"))
                val size = family.getValue("size")
                FontFamily(
                    requireNotNull(text(family.getValue("name"))),
                    if (size ==
                        RawValue.Null
                    ) {
                        null
                    } else {
                        number(size)
                    },
                )
            },
            number(fields.getValue("template")),
            number(fields.getValue("spacing")),
            requireNotNull(fields["ligatures"] as? RawValue.BooleanValue).value,
            text(fields.getValue("regular")),
            text(fields.getValue("bold")),
        )
    }

    fun readFields(value: RawValue): Map<String, RawValue> {
        val entries = requireNotNull(value as? RawValue.OrderedMap).entries
        require(entries.map { it.key }.distinct().size == entries.size) { "Duplicate Font checkpoint field" }
        return entries.associate { it.key to it.value }
    }

    private fun list(value: RawValue): StudioList<RawValue> = requireNotNull(value as? RawValue.OrderedList).values

    private fun number(value: RawValue): Float = Float.fromBits(requireNotNull(value as? RawValue.FloatBits).bits)

    private fun text(value: RawValue): String? =
        if (value == RawValue.Null) null else requireNotNull(value as? RawValue.Text).value

    fun record(vararg entries: Pair<String, RawValue>): RawValue = record(entries.asIterable())

    fun record(entries: Iterable<Pair<String, RawValue>>): RawValue =
        RawValue.OrderedMap(StudioList(entries.map { (key, value) -> RawValue.Entry(key, value) }))
}
