package dev.ayuislands.studio.model

private val typesByKey by lazy {
    StudioFieldCatalog.fields.associate { LegacyFieldKey(it.source, it.name) to it.type }
}

internal fun StudioField.requireKnownType() {
    val type = requireNotNull(typesByKey[key]) { "Unknown field requires an opaque extension" }
    require(value.matchesLegacyType(type)) { "Incorrect value type for ${key.source}/${key.name}" }
}

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
