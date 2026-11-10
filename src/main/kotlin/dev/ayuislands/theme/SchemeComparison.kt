package dev.ayuislands.theme

/** Predicts native equality without evaluating a foreign object or reading platform state. */
internal object SchemeComparison {
    fun inherited(
        parentIdentity: Any?,
        markerIdentity: Any,
    ): SchemeEquality =
        if (parentIdentity == null || parentIdentity === markerIdentity) {
            SchemeEquality.EQUAL
        } else {
            SchemeEquality.DIFFERENT
        }

    fun permitsPruning(
        facts: SchemePruningFacts,
        attributes: Set<String>,
        colors: Set<String>,
    ): Boolean = matchesPruning(facts.attributes, attributes) && matchesPruning(facts.colors, colors)

    private fun matchesPruning(
        entries: Map<String, SchemeEquality>,
        targets: Set<String>,
    ): Boolean =
        entries.keys.containsAll(targets) &&
            entries.all { (key, result) ->
                result == if (key in targets) SchemeEquality.EQUAL else SchemeEquality.DIFFERENT
            }

    fun colors(
        parent: SchemeColorFacts?,
        local: SchemeColorFacts?,
    ): SchemeEquality {
        if (parent?.identity === local?.identity) return SchemeEquality.EQUAL
        if (parent == null || local == null) return SchemeEquality.DIFFERENT
        return compareColors(parent, local)
    }

    fun attributes(
        parent: SchemeAttributeFacts?,
        local: SchemeAttributeFacts?,
    ): SchemeEquality {
        if (parent?.identity === local?.identity) return SchemeEquality.EQUAL
        if (parent == null || local == null) return SchemeEquality.DIFFERENT
        if (parent !is SchemeAttributeFacts.Native || local !is SchemeAttributeFacts.Native) {
            return SchemeEquality.UNKNOWN
        }
        if (parent.flyweight === local.flyweight) return SchemeEquality.EQUAL
        return compareFields(parent.fields, local.fields)
    }

    private fun compareColors(
        parent: SchemeColorFacts?,
        local: SchemeColorFacts?,
    ): SchemeEquality {
        if (parent?.identity === local?.identity) return SchemeEquality.EQUAL
        return when (parent) {
            null, is SchemeColorFacts.Marker -> SchemeEquality.DIFFERENT
            is SchemeColorFacts.Opaque -> SchemeEquality.UNKNOWN
            is SchemeColorFacts.Plain ->
                when (local) {
                    null -> SchemeEquality.DIFFERENT
                    is SchemeColorFacts.Plain ->
                        if (parent.argb == local.argb) SchemeEquality.EQUAL else SchemeEquality.DIFFERENT
                    else -> SchemeEquality.UNKNOWN
                }
        }
    }

    private fun compareFields(
        parent: SchemeAttributeFields,
        local: SchemeAttributeFields,
    ): SchemeEquality {
        if (parent.fontType != local.fontType) return SchemeEquality.DIFFERENT
        val background = compareColors(parent.background, local.background)
        if (background != SchemeEquality.EQUAL) return background
        val effect = compareColors(parent.effectColor, local.effectColor)
        if (effect != SchemeEquality.EQUAL) return effect
        if (parent.effectType != local.effectType) return SchemeEquality.DIFFERENT
        val stripe = compareColors(parent.errorStripe, local.errorStripe)
        if (stripe != SchemeEquality.EQUAL) return stripe
        val foreground = compareColors(parent.foreground, local.foreground)
        if (foreground != SchemeEquality.EQUAL) return foreground
        // Public getters do not expose the raw additional-effects map. Never reconstruct a flyweight to infer it.
        return SchemeEquality.UNKNOWN
    }
}
