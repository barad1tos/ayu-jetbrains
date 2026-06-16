package dev.ayuislands.syntax

import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Classifies a `TextAttributesKey.externalName` to a [PrimitiveCategory] using
 * a language-aware two-step (D-06, INTENSITY-05):
 *
 *  1. Delegate to [SyntaxLanguageRegistry.classify] so its bucket inference
 *     and latched unknown-prefix logging fire for every key this method sees.
 *     The returned tag is intentionally discarded — the applicator caller
 *     (Plan 50-04) consults [SyntaxLanguageRegistry] itself when it needs the
 *     language tag for per-language preset-curve lookup. This step exists so
 *     CASCADE-bucket keys (`DEFAULT_STRING`, `DEFAULT_LINE_COMMENT`, …) still
 *     classify via the suffix rule and OTHER-bucket keys are NOT auto-null.
 *  2. Match the `keyName` against an ordered suffix-rule table. First match
 *     wins — more-specific suffixes (`DOC_COMMENT`, `STATIC_FIELD`) MUST come
 *     before less-specific ones (`COMMENT`, `KEYWORD`).
 *
 * Unknown suffixes return `null` and log INFO exactly once per (suffix,
 * session) via a Pattern A `ConcurrentHashMap.newKeySet` latch — never spam.
 *
 * Pure-compute object (no `@Service`, no IDE state). Thread-safe by
 * construction: immutable rule list + `ConcurrentHashMap`-backed latch.
 * No exception-handling primitives appear in this file because the classifier
 * does no I/O; the only operations are regex matching and set membership,
 * neither of which can fail in a recoverable way (Pattern B compliance).
 */
object SyntaxCategoryRegistry {
    private const val SUFFIX_LATCH_MAX_CHARS = 24

    private val log = logger<SyntaxCategoryRegistry>()
    private val warnedUnknownSuffixes = ConcurrentHashMap.newKeySet<String>()

    /**
     * Ordered suffix-rule table. First match wins.
     *
     * Order rationale:
     *  - `DOC_COMMENT` / `DOC_TAG` must beat `COMMENT` so JavaDoc-style keys
     *    route to [PrimitiveCategory.DOCUMENTATION].
     *  - `STATIC_FIELD` / `INSTANCE_FIELD` must beat any generic FIELD rule
     *    (none exists today, but the explicit order makes the intent obvious).
     *  - `FUNCTION_DECLARATION` / `METHOD_DECLARATION` must beat the bare
     *    `OPERATOR` family because some platforms emit
     *    `KOTLIN_FUNCTION_DECLARATION` and we want FUNCTION_DECL, not OPERATOR.
     *  - `CLASS_DECLARATION` / `CLASS_NAME` route to CLASS_DECL — at this
     *    granularity class-references and class-declarations are NOT
     *    distinguished (Plan 50-04 modulates them with the same curve).
     *  - `INTERFACE_NAME` / `INTERFACE_DECLARATION` must beat CLASS_*; place
     *    INTERFACE BEFORE CLASS in the table.
     *  - `LOCAL_VARIABLE` must beat the generic VARIABLE family (no rule yet).
     *  - `TYPE_PARAMETER` (GENERICS) must beat the bare `TYPE` family so
     *    generic-type-parameter keys map to GENERICS, not TYPE_REF.
     *  - `STATIC_FIELD_*_ATTRIBUTES` suffix variants are handled via
     *    `containsMatchIn` (not anchored `$`) because the platform appends
     *    `_ATTRIBUTES` to many keys.
     */
    private val suffixRules: List<Pair<Regex, PrimitiveCategory>> =
        buildList {
            // --- Documentation: must beat COMMENT and KEYWORD ----------------
            addRules(
                PrimitiveCategory.DOCUMENTATION,
                "DOC_COMMENT($|_)|DOC_TAG|DOCUMENTATION$|KDOC_LINK|KDOC_TAG_NAME",
                "DOC_IDENTIFIER|DOC_METHOD_IDENTIFIER|DOC_PROPERTY_IDENTIFIER|DOC_VAR|LUA_DOC_VALUE",
                "ScalaDoc|MARKDOWN_LIST_MARKER|MARKDOWN_HRULE|MARKDOWN_CODE_FENCE_LANGUAGE",
                "MARKDOWN_STRIKE_THROUGH|^Groovydoc comment$|^Groovydoc tag$",
            )
            // --- Comments ---------------------------------------------------
            addRules(
                PrimitiveCategory.COMMENT,
                "LINE_COMMENT$|BLOCK_COMMENT$|COMMENT$|COMMENT_REFERENCE$|_COMMENT_",
                "MARKDOWN_BLOCK_QUOTE_MARKER|MARKDOWN_FRONT_MATTER",
                "IGNORE\\.COMMENT|IGNORE\\.UNUSED_ENTRY|COND_NOT_COMPILED",
            )
            // --- Swift instance method (must beat INSTANCE_FIELD) ----------
            addRules(
                PrimitiveCategory.FUNCTION_DECL,
                "SWIFT_INSTANCE_METHOD",
            )
            // --- Fields (must beat KEYWORD / LOCAL_VAR) ---------------------
            addRules(
                PrimitiveCategory.STATIC_FIELD,
                "STATIC_FIELD|STATIC_FINAL_FIELD|STATIC_GETTER|STATIC_SETTER",
                "STATIC_MEMBER|^Static field$|org\\.rust\\.STATIC|org\\.rust\\.MUT_STATIC|PHP_CONSTANT",
                "^Static property reference ID$",
                "SWIFT\\.CONSTANT",
            )
            addRules(
                PrimitiveCategory.INSTANCE_FIELD,
                "INSTANCE_FIELD|INSTANCE_FINAL_FIELD|INSTANCE_GETTER|INSTANCE_SETTER",
                "INSTANCE_MEMBER|INSTANCE_PROPERTY|TOP_LEVEL_GETTER|TOP_LEVEL_SETTER",
                "TOP_LEVEL_VARIABLE|TOP_LEVEL_FUNCTION|PROPERTY_REFERENCE|HASH_KEY|TAG_KEY",
                "TAG_VALUE|MAP_KEY|INSTANCE_PROPERTY_CUSTOM|PACKAGE_PROPERTY",
                "INSTANCE_FIELD_ATTRIBUTES|SYNTHETIC_EXTENSION_PROPERTY|^Instance field$",
                "^Map key$|LUA_FIELD|SWIFT_PROPERTY|SWIFT_GLOBAL_VARIABLE|Instance property reference|GO_TAG_TEXT",
                "SWIFT\\.GLOBAL_VARIABLE|SWIFT\\.PROPERTY",
                "MAGIC_MEMBER_ACCESS|EDITORCONFIG_PROPERTY_KEY|DOTENV_KEY|HCL\\.BLOCK_ONLY_NAME_KEY",
            )
            // --- Functions / methods (must beat KEYWORD / OPERATOR) ---------
            addRules(
                PrimitiveCategory.FUNCTION_DECL,
                "FUNCTION_DECLARATION$|METHOD_DECLARATION$|FUNCTION_DEFINITION$",
                "FUNCTION_DECL$|METHOD_DECL$|FUNCTION_CALL|METHOD_CALL|FUNCTION_DEF_NAME",
                "GLOBAL_FUNCTION|LOCAL_FUNCTION|LOCAL_METHOD|NESTED_FUNCTION|NESTED_FUNC_DEFINITION",
                "SUSPEND_FUNCTION_CALL|EXTENSION_FUNCTION_CALL|BUILTIN_FUNCTION_CALL",
                "EXPORTED_FUNCTION|FUNCTION_ARROW|CONSTRUCTOR_CALL|CONSTRUCTOR_DECLARATION",
                "CONSTRUCTOR_TEAR_OFF|METHOD_CALL_ATTRIBUTES|METHOD_DECLARATION_ATTRIBUTES",
                "STATIC_METHOD_ATTRIBUTES|STATIC_METHOD_IMPORTED_ATTRIBUTES",
                "CONSTRUCTOR_CALL_ATTRIBUTES|CONSTRUCTOR_DECLARATION_ATTRIBUTES",
                "ABSTRACT_METHOD_ATTRIBUTES|INHERITED_METHOD_ATTRIBUTES|PRIVATE_CALL",
                "PROTECTED_CALL|PUBLIC_CALL|STATIC_FUNCTION|REQUIRE_CALL|IMPORT_CALL",
                "REQUIRE_ARG_CALL|VARIABLE_AS_FUNCTION|PARAMDEF_CALL|FUNCTION$|METHOD$",
                "FUNCTION_NAME|METHOD_NAME",
                "KOTLIN_CONSTRUCTOR|TEAR_OFF|Method call|Method declaration",
                "Groovy method declaration|Groovy constructor declaration|Groovy constructor call",
                "LOCAL_FUNC|STD_API|POWER_SHELL_COMMAND_NAME|POWER_SHELL_METHOD_CALL_NAME",
                "RBS_TMETHOD_NAME|RBS_RUBY_SPECIFIC_CALLS",
                "FUNCTION_REFERENCE|^Static method access$",
            )
            // --- Interface / trait -----------------------------------------
            addRules(
                PrimitiveCategory.INTERFACE_DECL,
                "INTERFACE_NAME$|INTERFACE_DECLARATION$|TRAIT_NAME$|INTERFACE_REFERENCE",
                "INTERFACE_NAME_ATTRIBUTES|PROTOCOL_REFERENCE|PROTOCOL_NAME|PROTOCOL_DECLARATION",
                "KOTLIN_TRAIT|EXPORTED_INTERFACE",
                "INTERFACE$|^Trait name$|^Interface name$|Scala Trait|RBS_TINTERFACEIDENT",
            )
            // --- Class / enum / struct (declarations + references) ----------
            addRules(
                PrimitiveCategory.CLASS_DECL,
                "CLASS_DECLARATION$|CLASS_NAME$|CLASS_REFERENCE|CLASS_METHOD_CALL",
                "CLASS_NAME_ATTRIBUTES|ANONYMOUS_CLASS_NAME|ABSTRACT_CLASS_NAME|ENUM_NAME",
                "ENUM_REFERENCE|ENUM_VALUE|ENUM_ENTRY|ENUM_SINGLETON|ENUM_CLASS_CASE",
                "RECORD_NAME|RECORD_COMPONENT|STRUCT_REFERENCE|STRUCT_LOCAL_MEMBER_CALL",
                "STRUCT_EXPORTED_MEMBER_CALL|PACKAGE_EXPORTED_STRUCT|PACKAGE_LOCAL_STRUCT",
                "PACKAGE_EXPORTED_INTERFACE|PACKAGE_LOCAL_INTERFACE|LOCAL_INTERFACE_REFERENCE",
                "EXPORTED_STRUCT_REFERENCE|ACTOR_REFERENCE|ACTOR_DECLARATION|MIXIN|DATA_OBJECT|GIVEN",
                "STRUCT_NAME|STRUCT_DECLARATION|ENUM_DECLARATION|ENUM_MEMBER",
                "ABSTRACT_CLASS|MODULE_NAME|OBJECT$|CLASS$|ENUM$|^Class$",
                "^Enum name$|^Anonymous class name$|^Abstract class name$|Scala Class",
                "Scala Object|Scala Given|Scala Abstract class|Scala Enum|GO_TYPE_SPECIFICATION",
                "MAKEFILE_TARGET|MAKEFILE_SPECIAL_TARGET|MAKEFILE_PREREQUISITE|NGINX_TYPES",
                "DART_EXTENSION$|DART_MIXIN|GRAPHQL_IDENTIFIER|QL_ENTITY",
                "IntelliJComposableCallTextAttributes",
            )
            // --- Generics --------------------------------------------------
            addRules(
                PrimitiveCategory.GENERICS,
                "TYPE_PARAMETER$|GENERIC_TYPE_PARAMETER|GENERICS$|GENERIC$|TYPE_ARGUMENT$|TYPE_NAME_DYNAMIC",
            )
            // --- Keywords / modifiers --------------------------------------
            addRules(
                PrimitiveCategory.KEYWORD,
                "KEYWORD($|S|_)|MODIFIER$|RESERVED_WORD$|KEYWORD_OPERATIONS$",
                "DIRECTIVE$|HEADER$|TAG_NAME$|XML_NS_PREFIX|XML_TAG_DATA",
                "DIRECTIVE_PREFIX|DIRECTIVE_COMMAND|DIRECTIVE_KEY|DIRECTIVE_VALUE",
                "MACRO_RULES|MACRO_IDENTIFIER|MACRO_BINDING|MACRO_META_VAR",
                "MACRO_GROUP|MACRO_DOLLAR|MACRO_COLON|MACRO_EXCL|MACRO(?:_|$)",
                "SELF_SUPER|DIRECTIVE_CONDITION|DIRECTIVE_FLAG",
                "REQUIRE_CALL|WORDS|MARKDOWN_HEADER|NGINX_IF|NGINX_GEO",
                "NGINX_MAP|DROOLS_OPERATIONS|Scalatest keyword|Scala directive",
                "Scala XML tag$|Scala XML tag name|GENERATED_ITEM|QUTE_BOOLEAN",
                "JSONPATH\\.BOOLEAN|JSONPATH\\.OPERATIONS|JSONPATH\\.CONTEXT",
                "IGNORE\\.NEGATION|IGNORE\\.SYNTAX|IGNORE\\.SLASH",
                "IGNORE\\.SECTION|IGNORE\\.HEADER|MAKEFILE_FUNCTION$",
                "GITLAB_CI_EXPRESSION_REGEXP|CDATA_SECTION|PHP_TAG|PHP_MARKUP_ID",
                "MISSORTED_IMPORTS_ATTRIBUTES",
            )
            // --- Parameter --------------------------------------------------
            addRules(
                PrimitiveCategory.PARAMETER,
                "PARAMETER$|FUNCTION_PARAMETER$|ARG$|NAMED_ARGUMENT$|PARAMETER_DECLARATION",
                "PARAMETER_REFERENCE|DYNAMIC_PARAMETER_DECLARATION|DYNAMIC_PARAMETER_REFERENCE",
                "PARAMETER_ATTRIBUTES|REASSIGNED_PARAMETER_ATTRIBUTES|LAMBDA_PARAMETER_ATTRIBUTES",
                "ARGUMENT_LABEL|ANONYMOUS_PARAMETER|TUPLE_LABEL|TUPLE_TYPE_LABEL",
                "ANONYMOUS_CLOSURE_PARAMETER",
                "FUNCTION_PARAM|MAKEFILE_FUNCTION_PARAM|Closure parameter|Groovy parameter",
                "Groovy reassigned parameter|Scala Parameter|Scala Named Argument",
                "Scala Anonymous Parameter",
            )
            // --- Local variables -------------------------------------------
            addRules(
                PrimitiveCategory.LOCAL_VAR,
                "LOCAL_VARIABLE$|LOCAL_VAR$|LOCAL_VARIABLE_ATTRIBUTES|REASSIGNED_LOCAL_VARIABLE_ATTRIBUTES",
                "LOCAL_VAR_|LOCAL_VARIABLE_|VAR_USE|VAR_DEF|VAR_USE_COMPOSED|VAR_DEF_NAME",
                "DYNAMIC_LOCAL_VARIABLE_DECLARATION|DYNAMIC_LOCAL_VARIABLE_REFERENCE",
                "REASSIGNMENT_IN_SHORT_VAR|SCOPE_VARIABLE|LOCAL_VARIABLE_CALL",
                "GLOBAL_VARIABLE|VARIABLE$|VARIABLE_CALL|VAR$|CVAR$|UP_VALUE|SELF$",
                "Scala Local value|Scala Local variable|Scala Local lazy|Scala Template val",
                "Scala Template var|Scala Template lazy|Scala Pattern value|Scala For statement value",
                "Groovy var|Groovy reassigned var|IDENTIFIER$|TUIDENT$|TLIDENT$",
                "TGLOBALIDENT$|TSYMBOL$|TNAMESPACE$|RBS_T|BATCH\\.EXPRESSION",
                "DQL_PLACEHOLDER|DQL_EXPR|CRONEXP\\.IDENTIFIER|EDITORCONFIG_IDENTIFIER",
                "EDITORCONFIG_PATTERN|EDITORCONFIG_SPECIAL_SYMBOL|EDITORCONFIG_VARIABLE",
                "NGINX_VARIABLE|NGINX_LUA_BLOCK_DIRECTIVE|HTTP_REQUEST_PROTOCOL",
                "HTTP_REQUEST_PORT|HTTP_REQUEST_PARAMETER_NAME|HTTP_REQUEST_PARAMETER_VALUE",
                "HTTP_REQUEST_FILE_VARIABLE_NAME|COOKIE_TOKEN|POWER_SHELL_VARIABLE",
                "POWER_SHELL_PROPERTY_REF_NAME",
                "QUTE_IDENTIFIER|QUTE_TAG_NAME|TIL\\.IDENTIFIER|TIL\\.PROPERTY_REFERENCE",
                "TIL\\.RESOURCE_INSTANCE_REFERENCE|PROTO_IDENTIFIER|PROTOTEXT_IDENTIFIER",
                "PROTO_ENUM_VALUE|PROTOTEXT_ENUM_VALUE|JSONPATH\\.IDENTIFIER|JSONPATH\\.FUNCTION",
                "GITLAB_CI_EXPRESSION_IDENTIFIER|RUBY_PARAMDEF_CALL|GO_TAG_KEY",
                "CSS\\.UNIT|CSS\\.UNICODE",
            )
            // --- String literals -------------------------------------------
            addRules(
                PrimitiveCategory.STRING_LITERAL,
                "STRING$|TEMPLATE_STRING$|RAW_STRING$|CHAR$|CHARACTER$|STRING_LITERAL$|^String$|^GString$",
                "STRING_ESCAPE|ESCAPE_SEQUENCE|HEREDOC_ID|HEREDOC_CONTENT|HEREDOC|BACKQUOTE",
                "GString|FSTRING_FRAGMENT|REGEX$|REGEXP$|ESCAPE$",
                "VALID_ESCAPE|INVALID_ESCAPE|^Valid string escape$|^Invalid string escape$",
                "MARKDOWN_CODE_SPAN|INTERPOLATION",
                "String Injection|VALUE$|CONTENT$",
            )
            // --- Number literals -------------------------------------------
            addRules(
                PrimitiveCategory.NUMBER_LITERAL,
                "NUMBER$|INTEGER$|FLOAT$|HEX$|NUMBER_LITERAL$|^Number$",
            )
            // --- Annotations / decorators / metadata ------------------------
            addRules(
                PrimitiveCategory.ANNOTATION,
                "ANNOTATION$|DECORATOR$|ATTRIBUTE$|ANNOTATION_NAME|ATTRIBUTE_NAME",
                "ATTRIBUTE_ARGUMENT|ANNOTATION_ATTRIBUTE_NAME|ANNOTATION_ATTRIBUTE_NAME_ATTRIBUTES",
                "BUILD_TAG|Scala Annotation|^Annotation$|^Anotation attribute name$",
                "ERROR_HINT|METADATA$",
                "PROPERTY_BINDING_ATTR_NAME|EVENT_BINDING_ATTR_NAME|BANANA_BINDING_ATTR_NAME",
                "TEMPLATE_VARIABLE_ATTR_NAME|TEMPLATE_BINDINGS_ATTR_NAME|YAML_ANCHOR",
                "Scala XML attribute",
            )
            // --- Operators / punctuation -----------------------------------
            addRules(
                PrimitiveCategory.OPERATOR,
                "OPERATION_SIGN$|OPERATOR$|OPERATORS$|PUNCTUATION$|BRACES$",
                "BRACKETS$|PARENTHS$|PARENTHESES$|COMMA$|COLON$|DOT$|SEMICOLON$",
                "FAT_ARROW|PIPE$|BANG$|AMP$|SPREAD$",
                "SIGN$|BINARY_OPERATORS|REDIRECTION|SEPARATOR|CONCATENATION",
                "TAG_BRACE|SCRIPT_DELIMITERS|TEMPLATE_BINDINGS|^Lambda braces$",
                "^Closure braces$|^Operation sign$|^Braces$|^Brackets$|^Parentheses$|^Label$|JS\\.LABEL|BATCH\\.LABEL",
                "BATCH\\.LABEL_REFERENCE|GOTO_LABEL|POWER_SHELL_LABEL_NAME",
                "YAML_SCALAR_LIST|CSS\\.AMPERSAND|PROGUARD_WILDCARD|IGNORE\\.BRACKET",
                "WILDCARD",
            )
            // --- Type references / aliases ---------------------------------
            addRules(
                PrimitiveCategory.TYPE_REF,
                "TYPE_REFERENCE$|TYPE_NAME$|TYPE_ALIAS$|TYPE$|TYPEALIAS$|TYPEALIAS_REFERENCE",
                "ASSOCIATED_TYPE_DECLARATION",
                "TYPE_HINT|PRIMITIVE_TYPE_HINT|PREDEFINED_SCOPE|PREDEFINED|Scala Type",
                "Scala Predefined types|Scala Mutable Collection|Scala Immutable Collection",
                "StandardF Java Collection|Type parameter|TYPE_GUARD|DOCKER_ATTRIBUTES",
                "DOCKER_CONSTANT|QL_DATETIME|PUBLIC_REFERENCE|PROTECTED_REFERENCE",
                "PACKAGE_PRIVATE_REFERENCE|PRIVATE_REFERENCE|KOTLIN_WRAPPED_INTO_REF",
                "KOTLIN_ANDROID_EXTENSIONS_PROPERTY_CALL|VELOCITY_REFERENCE|FTL_REFERENCE",
                "PHP_ALIAS_REFERENCE|RUBY_CONSTANT_DECLARATION|RUBY_CONSTANT_DEF_ID",
                "Implicit conversion",
            )
        }

    private fun MutableList<Pair<Regex, PrimitiveCategory>>.addRules(
        category: PrimitiveCategory,
        vararg patterns: String,
    ) {
        patterns.forEach { pattern -> add(Regex(pattern) to category) }
    }

    /**
     * Classify a `TextAttributesKey` external name into a [PrimitiveCategory].
     *
     * Returns `null` for unknown suffixes; logs INFO once per (suffix, session)
     * via the Pattern A latch.
     */
    fun classify(keyName: String): PrimitiveCategory? {
        // Step 1: language-aware delegation. Result is discarded — the
        // applicator calls SyntaxLanguageRegistry itself when it needs the
        // tag. We invoke it here so the unknown-prefix latched logging fires
        // for the same set of keys this classifier processes (D-06 contract).
        SyntaxLanguageRegistry.classify(keyName)

        // Step 2: ordered suffix-rule match.
        for ((regex, category) in suffixRules) {
            if (regex.containsMatchIn(keyName)) return category
        }

        val suffix =
            keyName
                .substringAfterLast('_')
                .substringAfterLast('.')
                .take(SUFFIX_LATCH_MAX_CHARS)
        if (warnedUnknownSuffixes.add(suffix)) {
            log.info(
                "Unknown TextAttributesKey suffix '$suffix' for key '$keyName' — " +
                    "preset will skip this key",
            )
        }
        return null
    }
}
