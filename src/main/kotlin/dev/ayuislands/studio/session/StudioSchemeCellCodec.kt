package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.session.StudioNativeEvidence.fields
import dev.ayuislands.studio.session.StudioNativeEvidence.record
import dev.ayuislands.theme.SCHEME_STORED_EFFECTS
import dev.ayuislands.theme.SchemeStoredCell

/** Pure native-file payload encoding; it neither reconstructs a flyweight nor establishes write ownership. */
internal object StudioSchemeCellCodec {
    private const val EFFECT_COLOR = "effect-color"
    private const val ERROR_STRIPE = "error-stripe"

    private const val RGB_MAX = 0xFFFFFF

    fun encode(cell: SchemeStoredCell): RawValue =
        when (cell) {
            SchemeStoredCell.Omitted -> payload("omitted")
            is SchemeStoredCell.Color -> payload("color", "argb" to RawValue.Integer(cell.argb))
            SchemeStoredCell.NullColor -> payload("null-color")
            is SchemeStoredCell.InheritedColor ->
                payload(
                    "inherited-color",
                    "fallback" to RawValue.Text(cell.fallbackName),
                )
            is SchemeStoredCell.InheritedAttributes -> {
                payload("inherited-attributes", "fallback" to RawValue.Text(cell.fallbackName))
            }
            is SchemeStoredCell.Attributes -> {
                validateAttributes(cell)
                payload(
                    "attributes",
                    "foreground" to rgbValue(cell.foregroundRgb),
                    "background" to rgbValue(cell.backgroundRgb),
                    EFFECT_COLOR to rgbValue(cell.effectRgb),
                    ERROR_STRIPE to rgbValue(cell.errorStripeRgb),
                    "font" to RawValue.Integer(cell.fontType),
                    "effect" to RawValue.Integer(cell.effectCode),
                )
            }
        }

    fun decode(value: RawValue): SchemeStoredCell {
        val entries = fields(value)
        require(entries["version"] == RawValue.Integer(1)) { "Unsupported editor cell version" }
        return when (text(entries, "kind")) {
            "omitted" -> {
                requireFields(entries)
                SchemeStoredCell.Omitted
            }
            "color" -> {
                requireFields(entries, "argb")
                SchemeStoredCell.Color(integer(entries, "argb"))
            }
            "null-color" -> {
                requireFields(entries)
                SchemeStoredCell.NullColor
            }
            "inherited-color" -> {
                requireFields(entries, "fallback")
                SchemeStoredCell.InheritedColor(text(entries, "fallback"))
            }
            "inherited-attributes" -> {
                requireFields(entries, "fallback")
                SchemeStoredCell.InheritedAttributes(text(entries, "fallback"))
            }
            "attributes" -> readAttributes(entries)
            else -> throw IllegalArgumentException("Unknown editor cell kind")
        }
    }

    private fun readAttributes(entries: Map<String, RawValue>): SchemeStoredCell.Attributes {
        requireFields(entries, "foreground", "background", EFFECT_COLOR, ERROR_STRIPE, "font", "effect")
        val attributes =
            SchemeStoredCell.Attributes(
                foregroundRgb = rgb(entries, "foreground"),
                backgroundRgb = rgb(entries, "background"),
                effectRgb = rgb(entries, EFFECT_COLOR),
                errorStripeRgb = rgb(entries, ERROR_STRIPE),
                fontType = integer(entries, "font"),
                effectCode = integer(entries, "effect"),
            )
        validateAttributes(attributes)
        return attributes
    }

    private fun validateAttributes(cell: SchemeStoredCell.Attributes) {
        val colors = listOf(cell.foregroundRgb, cell.backgroundRgb, cell.effectRgb, cell.errorStripeRgb)
        require(colors.all { it == null || it in 0..RGB_MAX }) { "Invalid persisted attribute RGB" }
        require(cell.effectCode in -1 until SCHEME_STORED_EFFECTS.size) { "Invalid persisted attribute effect" }
    }

    private fun payload(
        kind: String,
        vararg entries: Pair<String, RawValue>,
    ): RawValue = record(listOf("version" to RawValue.Integer(1), "kind" to RawValue.Text(kind)) + entries)

    private fun requireFields(
        entries: Map<String, RawValue>,
        vararg names: String,
    ) {
        require(entries.keys == setOf("version", "kind") + names) { "Unexpected editor cell fields" }
    }

    private fun text(
        entries: Map<String, RawValue>,
        key: String,
    ): String = requireNotNull(entries[key] as? RawValue.Text) { "Invalid editor cell text" }.value

    private fun integer(
        entries: Map<String, RawValue>,
        key: String,
    ): Int = requireNotNull(entries[key] as? RawValue.Integer) { "Invalid editor cell integer" }.value

    private fun rgb(
        entries: Map<String, RawValue>,
        key: String,
    ): Int? = if (entries[key] == RawValue.Null) null else integer(entries, key)

    private fun rgbValue(value: Int?): RawValue = value?.let { RawValue.Integer(it) } ?: RawValue.Null
}
