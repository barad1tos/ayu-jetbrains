package dev.ayuislands.syntax

/**
 * Closed catalog of 16 primitive syntax categories. Granularity ceiling for
 * Custom drill-down sliders (premium follow-up) and preset curve tables in
 * `SyntaxPresetCurves`. Hardcoded — adding a 17th entry requires an explicit
 * schema bump and migration plan, not a drive-by edit (D-05, INTENSITY-05).
 *
 * No companion: Custom-overrides storage keys by [name] (enum intrinsic). A
 * missing override means "use the preset curve default" and is handled at the
 * lookup site, so no `fromName` defaulting is needed.
 */
enum class PrimitiveCategory(
    val displayName: String,
) {
    FUNCTION_DECL("Function declaration"),
    CLASS_DECL("Class declaration"),
    INTERFACE_DECL("Interface declaration"),
    KEYWORD("Keyword"),
    PARAMETER("Parameter"),
    LOCAL_VAR("Local variable"),
    STRING_LITERAL("String literal"),
    NUMBER_LITERAL("Number literal"),
    COMMENT("Comment"),
    ANNOTATION("Annotation"),
    OPERATOR("Operator"),
    TYPE_REF("Type reference"),
    STATIC_FIELD("Static field"),
    INSTANCE_FIELD("Instance field"),
    GENERICS("Generics"),
    DOCUMENTATION("Documentation"),
}
