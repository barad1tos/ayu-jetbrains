package dev.ayuislands.syntax

enum class PrimitiveGroup(
    val displayName: String,
    val columnIndex: Int,
    val columnOrder: Int,
) {
    DECLARATIONS("Declarations", FIRST_COLUMN, FIRST_ORDER),
    IDENTIFIERS("Identifiers & Members", SECOND_COLUMN, FIRST_ORDER),
    LITERALS("Literals", SECOND_COLUMN, SECOND_ORDER),
    KEYWORDS_DOCS("Keywords & Docs", FIRST_COLUMN, SECOND_ORDER),
}

data class PrimitiveSpec(
    val storageId: String,
    val displayName: String,
    val group: PrimitiveGroup,
    val order: Int,
)

/** Stable identity and presentation catalog for persisted syntax controls. */
enum class PrimitiveCategory(
    val specification: PrimitiveSpec,
) {
    FUNCTION_DECL(PrimitiveSpec("FUNCTION_DECL", "Function declaration", PrimitiveGroup.DECLARATIONS, FIRST_ORDER)),
    CLASS_DECL(PrimitiveSpec("CLASS_DECL", "Class declaration", PrimitiveGroup.DECLARATIONS, SECOND_ORDER)),
    INTERFACE_DECL(PrimitiveSpec("INTERFACE_DECL", "Interface declaration", PrimitiveGroup.DECLARATIONS, THIRD_ORDER)),
    KEYWORD(PrimitiveSpec("KEYWORD", "Keyword", PrimitiveGroup.KEYWORDS_DOCS, FIRST_ORDER)),
    PARAMETER(PrimitiveSpec("PARAMETER", "Parameter", PrimitiveGroup.IDENTIFIERS, FIRST_ORDER)),
    LOCAL_VAR(PrimitiveSpec("LOCAL_VAR", "Local variable", PrimitiveGroup.IDENTIFIERS, SECOND_ORDER)),
    STRING_LITERAL(PrimitiveSpec("STRING_LITERAL", "String literal", PrimitiveGroup.LITERALS, FIRST_ORDER)),
    NUMBER_LITERAL(PrimitiveSpec("NUMBER_LITERAL", "Number literal", PrimitiveGroup.LITERALS, SECOND_ORDER)),
    COMMENT(PrimitiveSpec("COMMENT", "Comment", PrimitiveGroup.KEYWORDS_DOCS, THIRD_ORDER)),
    ANNOTATION(PrimitiveSpec("ANNOTATION", "Annotation", PrimitiveGroup.KEYWORDS_DOCS, SECOND_ORDER)),
    OPERATOR(PrimitiveSpec("OPERATOR", "Operator", PrimitiveGroup.LITERALS, THIRD_ORDER)),
    TYPE_REF(PrimitiveSpec("TYPE_REF", "Type reference", PrimitiveGroup.DECLARATIONS, FOURTH_ORDER)),
    STATIC_FIELD(PrimitiveSpec("STATIC_FIELD", "Static field", PrimitiveGroup.IDENTIFIERS, FOURTH_ORDER)),
    INSTANCE_FIELD(PrimitiveSpec("INSTANCE_FIELD", "Instance field", PrimitiveGroup.IDENTIFIERS, THIRD_ORDER)),
    GENERICS(PrimitiveSpec("GENERICS", "Generics", PrimitiveGroup.IDENTIFIERS, FIFTH_ORDER)),
    DOCUMENTATION(PrimitiveSpec("DOCUMENTATION", "Documentation", PrimitiveGroup.KEYWORDS_DOCS, FOURTH_ORDER)),
    ;

    val displayName: String
        get() = specification.displayName
}

private const val FIRST_ORDER = 10
private const val SECOND_ORDER = 20
private const val THIRD_ORDER = 30
private const val FOURTH_ORDER = 40
private const val FIFTH_ORDER = 50
private const val FIRST_COLUMN = 0
private const val SECOND_COLUMN = 1
