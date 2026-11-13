package dev.ayuislands.studio.model

/** Checks the legacy value shape without interpreting or rewriting its contents. */
internal fun RawValue.matchesLegacyType(type: LegacyValueType): Boolean =
    when (type) {
        LegacyValueType.TEXT -> this is RawValue.Text || this == RawValue.Null
        LegacyValueType.BOOLEAN -> this is RawValue.BooleanValue
        LegacyValueType.INTEGER -> this is RawValue.Integer
        LegacyValueType.LONG -> this is RawValue.LongValue
        LegacyValueType.FLOAT -> this is RawValue.FloatBits
        LegacyValueType.STRING_MAP -> this is RawValue.OrderedMap && entries.all { it.value is RawValue.Text }
        LegacyValueType.STRING_SET -> this is RawValue.OrderedSet
    }
