package dev.ayuislands.syntax

import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Prefix-based classifier for syntax `TextAttributesKey.externalName` strings.
 *
 * Buckets every key into one of [Bucket.LANGUAGE] (per-language picker entry),
 * [Bucket.CASCADE] (cross-language defaults like `DEFAULT_LINE_COMMENT`),
 * [Bucket.DIAGNOSTICS] (warnings/errors/typos — hidden from picker),
 * [Bucket.EDITOR_OVERLAY] (breakpoints/folded text/diff — hidden from picker),
 * or [Bucket.OTHER] (unknown prefix; logged once via [warnedUnknownPrefixes]
 * latch — Pattern A from [SyntaxOverlayLoader]).
 *
 * Also owns the closed catalog of 5 cascade keys eligible for per-language
 * materialization in Phase 50A — see [cascadeKeysInScope] and
 * [cascadeTargets]. Per Phase 50 RESEARCH OQ-04 the catalog is intentionally
 * conservative (`DEFAULT_LINE_COMMENT` / `BLOCK_COMMENT` / `DOC_COMMENT` /
 * `STRING` / `NUMBER`); broader cascade keys are deferred until Wave 3
 * proves the materialization cycle does not double-apply.
 *
 * Object (not `@Service`) — pure compute, no IDE state. Thread-safe by
 * construction (immutable maps + ConcurrentHashMap-backed unknown-prefix
 * latch). Reads:
 *  - [Bucket] = LANGUAGE → per-language picker entry, intensity-eligible
 *  - [Bucket] = CASCADE → cross-language baseline used to derive
 *    per-language materialized keys at apply time
 *  - [Bucket] / DIAGNOSTICS / EDITOR_OVERLAY / OTHER → hidden from picker;
 *    intensity does NOT modulate these (R-7 — pollution risk)
 *
 * References Phase 50 RESEARCH OQ-03 (full prefix map) + OQ-04
 * (cascade-targets table) + CONTEXT D-06 (prefix ownership) + D-07
 * (cross-language cascade materialized per-language).
 */
object SyntaxLanguageRegistry {
    enum class Bucket { LANGUAGE, CASCADE, DIAGNOSTICS, EDITOR_OVERLAY, OTHER }

    data class LangTag(
        val tag: String,
        val displayName: String,
        val bucket: Bucket,
    )

    data class CascadeTarget(
        val language: LangTag,
        val keyName: String,
    )

    private const val UNKNOWN_PREFIX_LATCH_MAX_CHARS = 20

    private val log = logger<SyntaxLanguageRegistry>()
    private val warnedUnknownPrefixes = ConcurrentHashMap.newKeySet<String>()
    private val otherTag = LangTag("OTHER", "Other", Bucket.OTHER)
    private val groovyTag = LangTag("Groovy", "Groovy", Bucket.LANGUAGE)

    private val groovyExactKeys =
        setOf(
            "Groovy method declaration",
            "Groovy constructor declaration",
            "Groovy constructor call",
            "Groovy var",
            "Groovy reassigned var",
            "Groovy parameter",
            "Groovy reassigned parameter",
            "GROOVY_KEYWORD",
            "GString",
            "Groovydoc comment",
            "Groovydoc tag",
        )

    private val cascadeKeysInScopeSet =
        setOf(
            "DEFAULT_LINE_COMMENT",
            "DEFAULT_BLOCK_COMMENT",
            "DEFAULT_DOC_COMMENT",
            "DEFAULT_STRING",
            "DEFAULT_NUMBER",
        )

    private val diagnosticsKeys =
        setOf(
            "WARNING_ATTRIBUTES",
            "ERRORS_ATTRIBUTES",
            "INFO_ATTRIBUTES",
            "DEPRECATED_ATTRIBUTES",
            "BAD_CHARACTER",
            "TYPO",
            "TODO_DEFAULT_ATTRIBUTES",
            "NOT_USED_ELEMENT_ATTRIBUTES",
        )

    private val editorOverlayPrefixes =
        listOf(
            "BREAKPOINT_",
            "EXECUTIONPOINT_",
            "DEBUGGER_",
            "GOTO_DECLARATION_",
            "INJECTED_LANGUAGE_",
            "IDENTIFIER_UNDER_CARET",
            "INLAY_",
            "INLINE_REFACTORING_",
            "SEARCH_RESULT_",
            "LIVE_TEMPLATE_",
            "MATCHED_BRACE_",
            "UNMATCHED_BRACE_",
            "LINE_PARTIAL_COVERAGE",
            "IMPLICIT_",
            "INHERITED_",
            "INACTIVE_TEMPLATE_",
            "FOLDED_TEXT_",
            "INSTANCE_FINAL_FIELD_",
            "DIFF_",
            "BREADCRUMBS_",
            "FOLLOWED_HYPERLINK_",
            "HYPERLINK_",
            "CONSOLE_",
        )

    private val defaultCascadePrefix = Regex("^DEFAULT_")

    private val prefixRules: List<Pair<Regex, LangTag>> = buildPrefixRules()

    private val cascadeTargetsMap: Map<String, Map<String, String>> = buildCascadeTargets()

    fun classify(keyName: String): LangTag {
        if (keyName in diagnosticsKeys) {
            return LangTag(keyName, keyName, Bucket.DIAGNOSTICS)
        }
        if (editorOverlayPrefixes.any { keyName.startsWith(it) }) {
            return LangTag(keyName, keyName, Bucket.EDITOR_OVERLAY)
        }
        if (keyName in groovyExactKeys) {
            return groovyTag
        }
        if (defaultCascadePrefix.containsMatchIn(keyName)) {
            return LangTag(keyName, keyName, Bucket.CASCADE)
        }
        for ((regex, tag) in prefixRules) {
            if (regex.containsMatchIn(keyName)) return tag
        }
        val prefix =
            keyName
                .substringBefore('_')
                .substringBefore('.')
                .take(UNKNOWN_PREFIX_LATCH_MAX_CHARS)
        if (warnedUnknownPrefixes.add(prefix)) {
            log.info("Unknown language prefix '$prefix' for key '$keyName' — bucketing under OTHER")
        }
        return otherTag
    }

    fun supportedLanguages(): List<LangTag> =
        prefixRules
            .map { it.second }
            .distinctBy { it.tag }
            .filter { it.bucket == Bucket.LANGUAGE }
            .sortedBy { it.displayName }

    fun cascadeTargets(
        languageTag: String,
        defaultCascadeKey: String,
    ): String? = cascadeTargetsMap[languageTag]?.get(defaultCascadeKey)

    fun cascadeTargetsFor(defaultCascadeKey: String): List<CascadeTarget> =
        cascadeTargetsMap.mapNotNull { (languageTag, targets) ->
            val targetKey = targets[defaultCascadeKey] ?: return@mapNotNull null
            val tag =
                prefixRules
                    .map { it.second }
                    .firstOrNull { it.tag == languageTag }
                    ?: return@mapNotNull null
            CascadeTarget(tag, targetKey)
        }

    fun cascadeKeysInScope(): Set<String> = cascadeKeysInScopeSet

    private fun buildPrefixRules(): List<Pair<Regex, LangTag>> =
        spaceSeparatedRules() + pluginNamespacedRules() + dotNamespacedRules() + underscoreRules()

    private fun spaceSeparatedRules(): List<Pair<Regex, LangTag>> =
        listOf(
            Regex("^Scala ") to LangTag("Scala", "Scala", Bucket.LANGUAGE),
            Regex("^Scalatest ") to LangTag("Scala", "Scala", Bucket.LANGUAGE),
            Regex("^Groovy(doc)? ") to groovyTag,
        )

    private fun pluginNamespacedRules(): List<Pair<Regex, LangTag>> =
        listOf(
            Regex("^org\\.rust\\.") to LangTag("Rust", "Rust", Bucket.LANGUAGE),
            Regex("^ReSharper\\.") to LangTag("ReSharperCSharp", "C# (ReSharper)", Bucket.LANGUAGE),
        )

    private fun dotNamespacedRules(): List<Pair<Regex, LangTag>> =
        listOf(
            Regex("^PY\\.") to LangTag("Python", "Python", Bucket.LANGUAGE),
            Regex("^JS\\.") to LangTag("JavaScript", "JavaScript", Bucket.LANGUAGE),
            Regex("^CSS\\.") to LangTag("CSS", "CSS", Bucket.LANGUAGE),
            Regex("^BASH\\.") to LangTag("Bash", "Bash", Bucket.LANGUAGE),
            Regex("^TS\\.") to LangTag("TypeScript", "TypeScript", Bucket.LANGUAGE),
            Regex("^OC\\.") to LangTag("ObjectiveC", "Objective-C", Bucket.LANGUAGE),
            Regex("^COFFEESCRIPT\\.") to LangTag("CoffeeScript", "CoffeeScript", Bucket.LANGUAGE),
            Regex("^REGEXP\\.") to LangTag("RegExp", "RegExp", Bucket.LANGUAGE),
            Regex("^NG\\.") to LangTag("Angular", "Angular", Bucket.LANGUAGE),
            Regex("^JSONPATH\\.") to LangTag("JSONPath", "JSONPath", Bucket.LANGUAGE),
            Regex("^HCL\\.") to LangTag("HCL", "HCL", Bucket.LANGUAGE),
            Regex("^BATCH\\.") to LangTag("Batch", "Windows Batch", Bucket.LANGUAGE),
            Regex("^IGNORE\\.") to LangTag("Ignore", "Ignore files", Bucket.LANGUAGE),
            Regex("^JSON\\.") to LangTag("JSON", "JSON", Bucket.LANGUAGE),
            Regex("^PROPERTIES\\.") to LangTag("Properties", "Properties files", Bucket.LANGUAGE),
            Regex("^SWIFT\\.") to LangTag("Swift", "Swift", Bucket.LANGUAGE),
            Regex("^TIL\\.") to LangTag("TIL", "TIL", Bucket.LANGUAGE),
            Regex("^DQL\\.") to LangTag("DQL", "DQL", Bucket.LANGUAGE),
            Regex("^VUE\\.") to LangTag("Vue", "Vue", Bucket.LANGUAGE),
            Regex("^CRONEXP\\.") to LangTag("Cron", "Cron expression", Bucket.LANGUAGE),
        )

    private fun underscoreRules(): List<Pair<Regex, LangTag>> =
        listOf(
            Regex("^GO_") to LangTag("Go", "Go", Bucket.LANGUAGE),
            Regex("^DART_") to LangTag("Dart", "Dart", Bucket.LANGUAGE),
            Regex("^KOTLIN_") to LangTag("Kotlin", "Kotlin", Bucket.LANGUAGE),
            Regex("^JAVA_") to LangTag("Java", "Java", Bucket.LANGUAGE),
            Regex("^PHP_") to LangTag("PHP", "PHP", Bucket.LANGUAGE),
            Regex("^RUBY_") to LangTag("Ruby", "Ruby", Bucket.LANGUAGE),
            Regex("^SWIFT_") to LangTag("Swift", "Swift", Bucket.LANGUAGE),
            Regex("^MARKDOWN_") to LangTag("Markdown", "Markdown", Bucket.LANGUAGE),
            Regex("^LUA_") to LangTag("Lua", "Lua", Bucket.LANGUAGE),
            Regex("^RBS_") to LangTag("Ruby", "Ruby", Bucket.LANGUAGE),
            Regex("^SCALA_") to LangTag("Scala", "Scala", Bucket.LANGUAGE),
            Regex("^MAKEFILE_") to LangTag("Makefile", "Makefile", Bucket.LANGUAGE),
            Regex("^PUPPET_") to LangTag("Puppet", "Puppet", Bucket.LANGUAGE),
            Regex("^HAML_") to LangTag("HAML", "HAML", Bucket.LANGUAGE),
            Regex("^XML_") to LangTag("XML", "XML", Bucket.LANGUAGE),
            Regex("^NGINX_") to LangTag("Nginx", "Nginx", Bucket.LANGUAGE),
            Regex("^QUTE_") to LangTag("Qute", "Qute", Bucket.LANGUAGE),
            Regex("^HTTP_") to LangTag("HTTP", "HTTP client", Bucket.LANGUAGE),
            Regex("^GHERKIN_") to LangTag("Gherkin", "Gherkin", Bucket.LANGUAGE),
            Regex("^YAML_") to LangTag("YAML", "YAML", Bucket.LANGUAGE),
            Regex("^SASS_") to LangTag("Sass", "Sass", Bucket.LANGUAGE),
            Regex("^HTML_") to LangTag("HTML", "HTML", Bucket.LANGUAGE),
            Regex("^GRAPHQL_") to LangTag("GraphQL", "GraphQL", Bucket.LANGUAGE),
            Regex("^DOCKER_") to LangTag("Docker", "Docker", Bucket.LANGUAGE),
            Regex("^GQL_") to LangTag("GraphQL", "GraphQL", Bucket.LANGUAGE),
            Regex("^DROOLS_") to LangTag("Drools", "Drools", Bucket.LANGUAGE),
            Regex("^DJANGO_") to LangTag("Django", "Django", Bucket.LANGUAGE),
            Regex("^VELOCITY_") to LangTag("Velocity", "Velocity", Bucket.LANGUAGE),
            Regex("^SLIM_") to LangTag("Slim", "Slim", Bucket.LANGUAGE),
            Regex("^FTL_") to LangTag("FreeMarker", "FreeMarker", Bucket.LANGUAGE),
            Regex("^EDITORCONFIG_") to LangTag("EditorConfig", "EditorConfig", Bucket.LANGUAGE),
            Regex("^PROTOTEXT_") to LangTag("ProtobufText", "Protobuf text", Bucket.LANGUAGE),
            Regex("^PROTO_") to LangTag("Protobuf", "Protobuf", Bucket.LANGUAGE),
            Regex("^ERL_") to LangTag("Erlang", "Erlang", Bucket.LANGUAGE),
            Regex("^GITLAB_") to LangTag("GitLab", "GitLab CI", Bucket.LANGUAGE),
            Regex("^DOTENV_") to LangTag("dotenv", "dotenv", Bucket.LANGUAGE),
            Regex("^APPLE_") to LangTag("ApplePlist", "Apple plist", Bucket.LANGUAGE),
            Regex("^QL_") to LangTag("CodeQL", "CodeQL", Bucket.LANGUAGE),
            Regex("^POWER_") to LangTag("PowerShell", "PowerShell", Bucket.LANGUAGE),
        )

    private fun buildCascadeTargets(): Map<String, Map<String, String>> =
        mapOf(
            "Java" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "JAVA_LINE_COMMENT",
                    "DEFAULT_BLOCK_COMMENT" to "JAVA_BLOCK_COMMENT",
                    "DEFAULT_DOC_COMMENT" to "JAVA_DOC_COMMENT",
                ),
            "Kotlin" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "KOTLIN_LINE_COMMENT",
                    "DEFAULT_BLOCK_COMMENT" to "KOTLIN_BLOCK_COMMENT",
                    "DEFAULT_DOC_COMMENT" to "KOTLIN_DOC_COMMENT",
                    "DEFAULT_STRING" to "KOTLIN_STRING",
                    "DEFAULT_NUMBER" to "KOTLIN_NUMBER",
                ),
            "Swift" to
                mapOf(
                    "DEFAULT_STRING" to "SWIFT_STRING",
                    "DEFAULT_NUMBER" to "SWIFT_NUMBER",
                ),
            "Go" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "GO_COMMENT",
                    "DEFAULT_STRING" to "GO_STRING",
                    "DEFAULT_NUMBER" to "GO_NUMBER",
                ),
            "Python" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "PY.LINE_COMMENT",
                    "DEFAULT_BLOCK_COMMENT" to "PY.DOC_COMMENT",
                    "DEFAULT_STRING" to "PY.STRING",
                    "DEFAULT_NUMBER" to "PY.NUMBER",
                ),
            "Scala" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "Scala Line comment",
                    "DEFAULT_BLOCK_COMMENT" to "Scala Block comment",
                    "DEFAULT_DOC_COMMENT" to "SCALA_DOC_COMMENT",
                    "DEFAULT_STRING" to "Scala String",
                    "DEFAULT_NUMBER" to "SCALA_NUMBER",
                ),
            "Rust" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "org.rust.LINE_COMMENT",
                    "DEFAULT_BLOCK_COMMENT" to "org.rust.BLOCK_COMMENT",
                    "DEFAULT_DOC_COMMENT" to "org.rust.DOC_COMMENT",
                    "DEFAULT_STRING" to "org.rust.STRING",
                    "DEFAULT_NUMBER" to "org.rust.NUMBER",
                ),
            "Ruby" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "RUBY_COMMENT",
                    "DEFAULT_STRING" to "RUBY_STRING",
                ),
            "PHP" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "PHP_COMMENT",
                ),
            "Sass" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "SASS_COMMENT",
                ),
            "ReSharperCSharp" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "ReSharper.CSHARP_LINE_COMMENT",
                    "DEFAULT_BLOCK_COMMENT" to "ReSharper.CSHARP_BLOCK_COMMENT",
                    "DEFAULT_DOC_COMMENT" to "ReSharper.CSHARP_DOC_COMMENT",
                    "DEFAULT_STRING" to "ReSharper.CSHARP_STRING",
                    "DEFAULT_NUMBER" to "ReSharper.CSHARP_NUMBER",
                ),
            "JavaScript" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "JS.LINE_COMMENT",
                    "DEFAULT_BLOCK_COMMENT" to "JS.BLOCK_COMMENT",
                    "DEFAULT_DOC_COMMENT" to "JS.DOC_COMMENT",
                ),
            "TypeScript" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "TS.LINE_COMMENT",
                    "DEFAULT_BLOCK_COMMENT" to "TS.BLOCK_COMMENT",
                    "DEFAULT_DOC_COMMENT" to "TS.DOC_COMMENT",
                ),
            "HCL" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "HCL.LINE_COMMENT",
                ),
            "Bash" to
                mapOf(
                    "DEFAULT_LINE_COMMENT" to "BASH.LINE_COMMENT",
                ),
        )
}
