package dev.ayuislands.theme

/** Captured identities are opaque tokens; comparisons must never invoke their equality or hash methods. */
internal sealed interface SchemeColorFacts {
    val identity: Any

    class Plain(
        override val identity: Any,
        val argb: Int,
    ) : SchemeColorFacts

    class Marker(
        override val identity: Any,
        val kind: SchemeColorMarker,
    ) : SchemeColorFacts

    class Opaque(
        override val identity: Any,
    ) : SchemeColorFacts
}

internal enum class SchemeColorMarker { INHERITED, NULL }

internal data class SchemeAttributeFields(
    val fontType: Int,
    val background: SchemeColorFacts?,
    val effectColor: SchemeColorFacts?,
    val effectType: String?,
    val errorStripe: SchemeColorFacts?,
    val foreground: SchemeColorFacts?,
)

internal sealed interface SchemeAttributeFacts {
    val identity: Any

    class Native(
        override val identity: Any,
        val flyweight: Any,
        val fields: SchemeAttributeFields,
    ) : SchemeAttributeFacts

    class Opaque(
        override val identity: Any,
    ) : SchemeAttributeFacts
}

internal enum class SchemeEquality {
    EQUAL,
    DIFFERENT,
    UNKNOWN,
}

/** Exact local cell kind and identities, separate from the equality used by native pruning. */
internal sealed interface SchemeCellFacts {
    class Color(
        val value: SchemeColorFacts?,
    ) : SchemeCellFacts

    class Attributes(
        val value: SchemeAttributeFacts.Native?,
    ) : SchemeCellFacts

    data object InheritedAttributes : SchemeCellFacts

    data object Unavailable : SchemeCellFacts
}

/** Provenance and raw metadata observations never invoke equality on foreign values. */
internal class SchemeAdmissionFacts(
    val cell: SchemeCellFacts,
    val owner: Any?,
    val metadataTable: Any,
    val hasMetadata: Boolean,
    val metadataValue: Any?,
    val parent: Any?,
)

internal enum class SchemeComponent { VALUE, OWNERSHIP, METADATA }

/** Complete native deletion census across both maps, including foreign and inherited entries. */
internal data class SchemePruningFacts(
    val attributes: Map<String, SchemeEquality>,
    val colors: Map<String, SchemeEquality>,
)
