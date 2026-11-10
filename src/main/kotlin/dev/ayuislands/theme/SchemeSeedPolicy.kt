package dev.ayuislands.theme

/** Selects target-only seed steps; the owner still has to qualify the complete native deletion census. */
internal object SchemeSeedPolicy {
    fun attributes(
        parent: SchemeAttributeCell,
        current: SchemeAttributeCell,
        comparison: SchemeEquality,
    ): SchemeAttributeSeed {
        if (current == SchemeAttributeCell.Absent) return SchemeAttributeSeed.NONE
        return when (parent) {
            SchemeAttributeCell.Absent, SchemeAttributeCell.Inherited ->
                if (current == SchemeAttributeCell.Inherited) {
                    SchemeAttributeSeed.NONE
                } else {
                    SchemeAttributeSeed.INHERITED
                }
            is SchemeAttributeCell.Value ->
                when (comparison) {
                    SchemeEquality.UNKNOWN -> SchemeAttributeSeed.UNAVAILABLE
                    SchemeEquality.DIFFERENT -> SchemeAttributeSeed.PARENT
                    SchemeEquality.EQUAL ->
                        if (current == SchemeAttributeCell.Inherited) {
                            SchemeAttributeSeed.UNAVAILABLE
                        } else {
                            SchemeAttributeSeed.NONE
                        }
                }
        }
    }

    fun colors(
        parent: SchemeColorCell,
        current: SchemeColorCell,
        comparison: SchemeEquality,
    ): SchemeColorSeed {
        if (current == SchemeColorCell.Absent) return SchemeColorSeed.NONE
        return when (parent) {
            SchemeColorCell.Absent, SchemeColorCell.Inherited ->
                if (current == SchemeColorCell.Inherited) SchemeColorSeed.NONE else SchemeColorSeed.INHERITED
            SchemeColorCell.Null ->
                if (current == SchemeColorCell.Null) SchemeColorSeed.NONE else SchemeColorSeed.NULL
            is SchemeColorCell.Value ->
                when {
                    comparison == SchemeEquality.EQUAL && current is SchemeColorCell.Value -> SchemeColorSeed.NONE
                    current == SchemeColorCell.Inherited || current == SchemeColorCell.Null -> SchemeColorSeed.PARENT
                    else -> SchemeColorSeed.BARRIER
                }
        }
    }
}
