package dev.ayuislands.theme

/** Predicts native serialization from captured facts; null means the payload cannot be qualified safely. */
internal object SchemePersistence {
    private const val RGB_MASK = 0xFFFFFF

    /** The caller supplies the actual stored key's fallback, not a replacement from the key registry. */
    fun project(
        local: SchemeCellFacts,
        base: SchemeCellFacts,
        directBase: SchemeCellFacts,
        fallbackName: String?,
    ): SchemeStoredCell? =
        when (local) {
            is SchemeCellFacts.Color -> color(local, base, directBase, fallbackName)
            is SchemeCellFacts.Attributes -> attributes(local, base)
            SchemeCellFacts.InheritedAttributes -> inheritedAttributes(directBase, fallbackName)
            SchemeCellFacts.Unavailable -> null
        }

    private fun color(
        local: SchemeCellFacts.Color,
        base: SchemeCellFacts,
        directBase: SchemeCellFacts,
        fallbackName: String?,
    ): SchemeStoredCell? {
        val value = local.value ?: return SchemeStoredCell.Omitted
        if (value is SchemeColorFacts.Marker && value.kind == SchemeColorMarker.INHERITED) {
            return inheritedColor(directBase, fallbackName)
        }
        if (base !is SchemeCellFacts.Color) return null
        val original = base.value
        if (value is SchemeColorFacts.Marker) {
            return if (value.identity === original?.identity) SchemeStoredCell.Omitted else SchemeStoredCell.NullColor
        }
        val normalized = original.takeUnless { it is SchemeColorFacts.Marker && it.kind == SchemeColorMarker.NULL }
        return when (SchemeComparison.colors(value, normalized)) {
            SchemeEquality.EQUAL -> SchemeStoredCell.Omitted
            SchemeEquality.UNKNOWN -> null
            SchemeEquality.DIFFERENT -> (value as? SchemeColorFacts.Plain)?.let { SchemeStoredCell.Color(it.argb) }
        }
    }

    private fun inheritedColor(
        directBase: SchemeCellFacts,
        fallbackName: String?,
    ): SchemeStoredCell? {
        if (directBase !is SchemeCellFacts.Color) return null
        val direct = directBase.value
        return when {
            fallbackName == null || direct == null -> SchemeStoredCell.Omitted
            direct is SchemeColorFacts.Marker && direct.kind == SchemeColorMarker.INHERITED -> SchemeStoredCell.Omitted
            else -> SchemeStoredCell.InheritedColor(fallbackName)
        }
    }

    private fun attributes(
        local: SchemeCellFacts.Attributes,
        base: SchemeCellFacts,
    ): SchemeStoredCell? {
        val value = local.value ?: return SchemeStoredCell.Omitted
        if (base !is SchemeCellFacts.Attributes) return null
        // Native serialization compares local to base; pruning deliberately uses the opposite direction.
        return when (SchemeComparison.attributes(value, base.value)) {
            SchemeEquality.EQUAL -> SchemeStoredCell.Omitted
            SchemeEquality.UNKNOWN -> null
            SchemeEquality.DIFFERENT -> attributePayload(value.fields)
        }
    }

    private fun inheritedAttributes(
        directBase: SchemeCellFacts,
        fallbackName: String?,
    ): SchemeStoredCell? =
        when (directBase) {
            is SchemeCellFacts.Attributes -> {
                if (directBase.value ==
                    null
                ) {
                    SchemeStoredCell.Omitted
                } else {
                    SchemeStoredCell.InheritedAttributes(fallbackName.orEmpty())
                }
            }
            SchemeCellFacts.InheritedAttributes -> SchemeStoredCell.Omitted
            is SchemeCellFacts.Color, SchemeCellFacts.Unavailable -> null
        }

    private fun attributePayload(fields: SchemeAttributeFields): SchemeStoredCell.Attributes? {
        val colors = listOf(fields.foreground, fields.background, fields.effectColor, fields.errorStripe)
        if (colors.any { it != null && it !is SchemeColorFacts.Plain }) return null
        return SchemeStoredCell.Attributes(
            foregroundRgb = rgb(fields.foreground),
            backgroundRgb = rgb(fields.background),
            effectRgb = rgb(fields.effectColor),
            errorStripeRgb = rgb(fields.errorStripe),
            fontType = fields.fontType,
            effectCode = fields.effectType?.let(SCHEME_STORED_EFFECTS::indexOf) ?: -1,
        )
    }

    private fun rgb(color: SchemeColorFacts?): Int? = (color as? SchemeColorFacts.Plain)?.argb?.and(RGB_MASK)
}
