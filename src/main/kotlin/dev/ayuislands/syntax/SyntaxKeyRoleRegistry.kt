package dev.ayuislands.syntax

import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap

sealed interface SyntaxKeyRole {
    sealed interface LanguageOwned : SyntaxKeyRole {
        val languageId: String
    }

    data class Tunable(
        override val languageId: String,
        val primitive: PrimitiveCategory,
    ) : LanguageOwned

    data class Excluded(
        override val languageId: String,
        val reason: String,
    ) : LanguageOwned

    data class OutsideLanguageScope(
        val reason: String,
        val renderingPrimitive: PrimitiveCategory? = null,
    ) : SyntaxKeyRole

    data class Unknown(
        val keyName: String,
    ) : SyntaxKeyRole
}

internal val SyntaxKeyRole.effectivePrimitive: PrimitiveCategory?
    get() =
        when (this) {
            is SyntaxKeyRole.Tunable -> primitive
            is SyntaxKeyRole.OutsideLanguageScope -> renderingPrimitive
            is SyntaxKeyRole.Excluded,
            is SyntaxKeyRole.Unknown,
            -> null
        }

/**
 * Total classifier for `TextAttributesKey.externalName` values.
 *
 * Language-owned keys become tunable or explicitly excluded. Known suffixes
 * outside a language remain available to preset rendering but never become
 * per-language controls. Unknown keys are retained as an explicit role and
 * logged once per suffix.
 */
object SyntaxKeyRoleRegistry {
    private const val SUFFIX_LATCH_MAX_CHARS = 24

    private val log = logger<SyntaxKeyRoleRegistry>()
    private val warnedUnknownSuffixes = ConcurrentHashMap.newKeySet<String>()
    private val excludedLanguageKeys =
        mapOf(
            "COFFEESCRIPT.CLASS_NAME" to "Provider emits JS.EXPORTED.CLASS for class names",
        )

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
                "Scala (?:Line|Block) comment",
            )
            // GraphQL directives are annotation-like metadata, not language directives.
            addRules(PrimitiveCategory.ANNOTATION, "^GRAPHQL_DIRECTIVE$")
            // --- Swift instance method (must beat INSTANCE_FIELD) ----------
            addRules(
                PrimitiveCategory.FUNCTION_DECL,
                "SWIFT_INSTANCE_METHOD",
            )
            // --- Fields (must beat KEYWORD / LOCAL_VAR) ---------------------
            addRules(
                PrimitiveCategory.STATIC_FIELD,
                "STATIC_FIELD|STATIC_FINAL_FIELD|STATIC_GETTER|STATIC_SETTER",
                "STATIC_MEMBER_VARIABLE|STATIC_MEMBER_FIELD|^Static field$",
                "org\\.rust\\.STATIC|org\\.rust\\.MUT_STATIC|PHP_CONSTANT",
                "^Static property reference ID$",
                "SWIFT\\.CONSTANT",
            )
            addRules(
                PrimitiveCategory.INSTANCE_FIELD,
                "INSTANCE_FIELD|INSTANCE_FINAL_FIELD|INSTANCE_GETTER|INSTANCE_SETTER",
                "INSTANCE_MEMBER_VARIABLE|INSTANCE_MEMBER_FIELD|INSTANCE_PROPERTY",
                "TOP_LEVEL_GETTER|TOP_LEVEL_SETTER",
                "TOP_LEVEL_VARIABLE|TOP_LEVEL_FUNCTION|PROPERTY_REFERENCE|HASH_KEY|TAG_KEY",
                "TAG_VALUE|MAP_KEY|INSTANCE_PROPERTY_CUSTOM|PACKAGE_PROPERTY",
                "INSTANCE_FIELD_ATTRIBUTES|SYNTHETIC_EXTENSION_PROPERTY|^Instance field$",
                "LUA_FIELD|SWIFT_PROPERTY|SWIFT_GLOBAL_VARIABLE|Instance property reference|GO_TAG_TEXT",
                "GO_STRUCT_(?:EXPORTED|LOCAL)_MEMBER$",
                "RUBY_IVAR",
                "SWIFT\\.GLOBAL_VARIABLE|SWIFT\\.PROPERTY",
                "POWER_SHELL_PROPERTY_REF_NAME",
                "MAGIC_MEMBER_ACCESS|EDITORCONFIG_PROPERTY_KEY|DOTENV_KEY",
                "HCL\\.BLOCK_ONLY_NAME_KEY|HCL\\.PROPERTY_KEY",
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
                "POWER_SHELL_METHOD_DECLARATION_NAME",
                "GO_STRUCT_(?:EXPORTED|LOCAL)_MEMBER_CALL",
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
            addRules(
                PrimitiveCategory.TYPE_REF,
                "GO_(?:EXPORTED|LOCAL)_STRUCT_REFERENCE",
                "PY\\.ANNOTATION",
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
                "DART_EXTENSION$|DART_MIXIN|GRAPHQL_IDENTIFIER|QL_ENTITY|PUPPET_NAME",
                "POWER_SHELL_TYPE_NAME",
                "IntelliJComposableCallTextAttributes",
            )
            // --- Generics --------------------------------------------------
            addRules(
                PrimitiveCategory.GENERICS,
                "TYPE_PARAMETER$|GENERIC_TYPE_PARAMETER|GENERICS$|GENERIC$|TYPE_ARGUMENT$|TYPE_NAME_DYNAMIC",
                "^Type parameter$",
            )
            // --- Keywords / modifiers --------------------------------------
            addRules(
                PrimitiveCategory.KEYWORD,
                "^HTTP_REQUEST_METHOD_TYPE$",
                "KEYWORD($|S|_)|MODIFIER$|RESERVED_WORD$|KEYWORD_OPERATIONS$",
                "DIRECTIVE$|HEADER$|TAG_NAME$|XML_NS_PREFIX|XML_TAG_DATA",
                "DIRECTIVE_PREFIX|DIRECTIVE_COMMAND|DIRECTIVE_KEY|DIRECTIVE_VALUE",
                "MACRO_RULES|MACRO_IDENTIFIER|MACRO_BINDING|MACRO_META_VAR",
                "MACRO_GROUP|MACRO_DOLLAR|MACRO_COLON|MACRO_EXCL|MACRO(?:_|$)",
                "SELF_SUPER|DIRECTIVE_CONDITION|DIRECTIVE_FLAG|PREDEFINED_SYMBOL",
                "REQUIRE_CALL|WORDS|MARKDOWN_HEADER|NGINX_IF|NGINX_GEO",
                "NGINX_MAP|DROOLS_OPERATIONS|Scalatest keyword|Scala directive",
                "Scala Keyword|Scala XML tag$|Scala XML tag name|GENERATED_ITEM|QUTE_BOOLEAN",
                "JSONPATH\\.BOOLEAN|JSONPATH\\.OPERATIONS|JSONPATH\\.CONTEXT",
                "IGNORE\\.NEGATION|IGNORE\\.SYNTAX|IGNORE\\.SLASH",
                "IGNORE\\.SECTION|IGNORE\\.HEADER|MAKEFILE_FUNCTION$",
                "GITLAB_CI_EXPRESSION_REGEXP|CDATA_SECTION|PHP_TAG|PHP_MARKUP_ID",
                "MISSORTED_IMPORTS_ATTRIBUTES",
            )
            // --- Parameter --------------------------------------------------
            addRules(
                PrimitiveCategory.PARAMETER,
                "^Map key$|PARAMETER$|FUNCTION_PARAMETER$|ARG$|NAMED_ARGUMENT$|PARAMETER_DECLARATION",
                "PARAMETER_REFERENCE|DYNAMIC_PARAMETER_DECLARATION|DYNAMIC_PARAMETER_REFERENCE",
                "PARAMETER_ATTRIBUTES|REASSIGNED_PARAMETER_ATTRIBUTES|LAMBDA_PARAMETER_ATTRIBUTES",
                "ARGUMENT_LABEL|ANONYMOUS_PARAMETER|TUPLE_LABEL|TUPLE_TYPE_LABEL",
                "ANONYMOUS_CLOSURE_PARAMETER",
                "FUNCTION_PARAM|MAKEFILE_FUNCTION_PARAM|Closure parameter|Groovy parameter",
                "Groovy reassigned parameter|Scala Parameter|Scala Named Argument",
                "Scala Anonymous Parameter|GHERKIN_TABLE_HEADER_CELL",
            )
            // --- Local variables -------------------------------------------
            addRules(
                PrimitiveCategory.LOCAL_VAR,
                "LOCAL_VARIABLE$|LOCAL_VAR$|LOCAL_VARIABLE_ATTRIBUTES|REASSIGNED_LOCAL_VARIABLE_ATTRIBUTES",
                "LOCAL_VAR_|LOCAL_VARIABLE_|VAR_USE|VAR_DEF|VAR_USE_COMPOSED|VAR_DEF_NAME",
                "DYNAMIC_LOCAL_VARIABLE_DECLARATION|DYNAMIC_LOCAL_VARIABLE_REFERENCE",
                "VARIABLE_DECLARATION|VARIABLE_REFERENCE",
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
                "String Injection|VALUE$|CONTENT$|Scala String",
                "PY\\.STRING\\.",
            )
            // --- Number literals -------------------------------------------
            addRules(
                PrimitiveCategory.NUMBER_LITERAL,
                "NUMBER$|INTEGER$|FLOAT$|HEX$|NUMBER_LITERAL$|^Number$|Scala Number",
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
                "WILDCARD|Scala (?:Assign|Braces|Brackets|Comma|Parentheses)",
            )
            // --- Type references / aliases ---------------------------------
            // PUBLIC_/PROTECTED_/PACKAGE_PRIVATE_/PRIVATE_REFERENCE are deliberately
            // absent: Java merges them over the role colour of a reference rather than
            // painting one itself, so classifying them lets the applicator write a
            // foreground and flatten Java highlighting. See the theme overlay XMLs.
            addRules(
                PrimitiveCategory.TYPE_REF,
                "TYPE_REFERENCE$|TYPE_NAME$|TYPE_ALIAS$|TYPE$|TYPEALIAS$|TYPEALIAS_REFERENCE",
                "PRIMITIVE\\.TYPES$",
                "ASSOCIATED_TYPE_DECLARATION",
                "TYPE_HINT|PRIMITIVE_TYPE_HINT|PREDEFINED_SCOPE|PREDEFINED|Scala Type",
                "Scala Predefined types|Scala Mutable Collection|Scala Immutable Collection",
                "StandardF Java Collection|TYPE_GUARD|DOCKER_ATTRIBUTES",
                "DOCKER_CONSTANT|QL_DATETIME|KOTLIN_WRAPPED_INTO_REF",
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

    /** Declared tunable roles keyed by stable primitive storage ID. */
    fun rolesFor(languageId: String): Map<String, SyntaxKeyRole.LanguageOwned> {
        val specification = SyntaxLanguageRegistry.findByStorageId(languageId) ?: return emptyMap()
        return specification.preview.files
            .flatMap { it.demonstratedCategories }
            .distinct()
            .associate { primitive ->
                primitive.specification.storageId to SyntaxKeyRole.Tunable(languageId, primitive)
            }
    }

    fun classify(keyName: String): SyntaxKeyRole {
        val language = SyntaxLanguageRegistry.classify(keyName)
        val primitive = primitiveFor(keyName)
        if (language.bucket == SyntaxLanguageRegistry.Bucket.LANGUAGE) {
            excludedLanguageKeys[keyName]?.let { reason ->
                return SyntaxKeyRole.Excluded(language.displayName, reason)
            }
            return if (primitive == null) {
                SyntaxKeyRole.Excluded(language.displayName, "No supported primitive role")
            } else {
                SyntaxKeyRole.Tunable(language.displayName, primitive)
            }
        }
        if (primitive != null) {
            return SyntaxKeyRole.OutsideLanguageScope(
                reason = "${language.bucket} keys are not per-language controls",
                renderingPrimitive = primitive,
            )
        }
        if (language.bucket != SyntaxLanguageRegistry.Bucket.OTHER) {
            return SyntaxKeyRole.OutsideLanguageScope("${language.bucket} key")
        }
        logUnknownSuffix(keyName)
        return SyntaxKeyRole.Unknown(keyName)
    }

    private fun primitiveFor(keyName: String): PrimitiveCategory? =
        suffixRules.firstNotNullOfOrNull { (regex, category) ->
            category.takeIf { regex.containsMatchIn(keyName) }
        }

    private fun logUnknownSuffix(keyName: String) {
        val suffix =
            keyName
                .substringAfterLast('_')
                .substringAfterLast('.')
                .take(SUFFIX_LATCH_MAX_CHARS)
        if (warnedUnknownSuffixes.add(suffix)) {
            log.info("Unknown TextAttributesKey suffix '$suffix' for key '$keyName'")
        }
    }
}
