package dev.ayuislands.font

/** Families, template and optional sizes share one native reset operation and one restore guard. */
internal enum class FontPreferenceField { FAMILIES, LIGATURES, SPACING, REGULAR_SUBFAMILY, BOLD_SUBFAMILY }

/** Exact exposed field comparisons and known prefixes of the native preference copy. */
internal object FontPreferencePolicy {
    fun restoreDecision(
        field: FontPreferenceField,
        current: FontData,
        evidence: FontFieldEvidence,
    ): FontRestoreDecision {
        if (matches(field, current, evidence.before)) return FontRestoreDecision.RESTORED
        val origin = evidence.origin
        val owned =
            when {
                origin != null && !evidence.isInterrupted -> matches(field, current, origin)
                field != FontPreferenceField.FAMILIES -> matches(field, current, origin ?: evidence.target)
                origin == null -> ownsFamilyPrefix(current, evidence.before, evidence.target)
                else -> {
                    val sizes = evidence.before.families.associate { it.name to it.size }
                    buildList {
                        addAll(listOf(origin))
                        addAll(this@FontPreferencePolicy.familyWrites(origin, evidence.before))
                        addAll(this@FontPreferencePolicy.familyWrites(origin, evidence.before, sizes))
                    }.any {
                        matches(field, current, it)
                    }
                }
            }
        return when {
            owned -> FontRestoreDecision.OWNED
            field == FontPreferenceField.FAMILIES && evidence.isInterrupted -> FontRestoreDecision.UNRESOLVED
            else -> FontRestoreDecision.FOREIGN
        }
    }

    fun matches(
        first: FontSnapshot,
        second: FontSnapshot,
    ): Boolean =
        when (first) {
            FontSnapshot.Inherited -> second == FontSnapshot.Inherited
            is FontSnapshot.Explicit ->
                second is FontSnapshot.Explicit &&
                    matches(first.preferences, second.preferences)
        }

    fun matches(
        first: FontData,
        second: FontData,
    ): Boolean = FontPreferenceField.entries.all { matches(it, first, second) }

    fun matches(
        field: FontPreferenceField,
        first: FontData,
        second: FontData,
    ): Boolean =
        when (field) {
            FontPreferenceField.FAMILIES ->
                first.effectiveFamilies == second.effectiveFamilies &&
                    first.families.map { it.name to it.size?.toRawBits() } ==
                    second.families.map { it.name to it.size?.toRawBits() } &&
                    first.templateSize.toRawBits() == second.templateSize.toRawBits()
            FontPreferenceField.LIGATURES -> first.ligatures == second.ligatures
            FontPreferenceField.SPACING -> first.lineSpacing.toRawBits() == second.lineSpacing.toRawBits()
            FontPreferenceField.REGULAR_SUBFAMILY -> first.regularSubFamily == second.regularSubFamily
            FontPreferenceField.BOLD_SUBFAMILY -> first.boldSubFamily == second.boldSubFamily
        }

    /** Includes the state after each setter, including a reset before individual sizes are restored. */
    fun familyWrites(
        before: FontData,
        target: FontData,
        retainedSizes: Map<String, Float?> = emptyMap(),
    ): List<FontData> {
        var current = before.copy(effectiveFamilies = target.effectiveFamilies)
        val states = mutableListOf(current)
        current =
            current.copy(
                families =
                    target.families.map { family ->
                        val previous = before.families.firstOrNull { it.name == family.name }
                        FontFamily(family.name, if (previous != null) previous.size else retainedSizes[family.name])
                    },
            )
        states.add(current)
        current = current.copy(templateSize = target.templateSize)
        states.add(current)
        current = current.copy(families = current.families.map { it.copy(size = null) })
        states.add(current)
        for (family in target.families.filter { it.size != null }) {
            current = current.copy(families = current.families.map { if (it.name == family.name) family else it })
            states.add(current)
        }
        return states
    }

    fun ownsFamilyPrefix(
        current: FontData,
        before: FontData,
        target: FontData,
    ): Boolean {
        val applied = listOf(before) + familyWrites(before, target)
        val baselineSizes = before.families.associate { it.name to it.size }
        val restored = applied.flatMap { familyWrites(it, before) + familyWrites(it, before, baselineSizes) }
        return (applied + restored).any { matches(FontPreferenceField.FAMILIES, current, it) }
    }

    fun copyWrites(
        before: FontData,
        target: FontData,
    ): List<FontData> {
        val states = (listOf(before) + familyWrites(before, target)).toMutableList()
        states.add(states.last().copy(ligatures = target.ligatures))
        states.add(states.last().copy(lineSpacing = target.lineSpacing))
        states.add(states.last().copy(regularSubFamily = target.regularSubFamily))
        states.add(states.last().copy(boldSubFamily = target.boldSubFamily))
        return states
    }
}
