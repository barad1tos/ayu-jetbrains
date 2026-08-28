package dev.ayuislands.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RED → GREEN coverage for [SyntaxLanguageRegistry]. Pins the prefix-map
 * classification table (≥30 entries across underscore, dot-namespaced,
 * space-separated, and plugin-namespaced buckets per RESEARCH OQ-03), the
 * cross-cutting CASCADE / DIAGNOSTICS / EDITOR_OVERLAY routing, the unknown-
 * prefix OTHER fallback with log-once latch, and the [SyntaxLanguageRegistry.supportedLanguages]
 * picker contract (≥26 LANGUAGE entries, no non-LANGUAGE leaks,
 * alphabetical sort by displayName).
 */
class SyntaxLanguageRegistryTest {
    private data class Row(
        val key: String,
        val expected: SyntaxLanguageRegistry.LangTag,
    ) {
        constructor(
            key: String,
            tag: String,
            displayName: String,
            bucket: SyntaxLanguageRegistry.Bucket,
        ) : this(key, SyntaxLanguageRegistry.LangTag(tag, displayName, bucket))
    }

    private val languageRows =
        listOf(
            // Underscore bucket
            Row("GO_KEYWORD", "Go", "Go", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("DART_KEYWORD", "Dart", "Dart", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("KOTLIN_KEYWORD", "Kotlin", "Kotlin", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("JAVA_KEYWORD", "Java", "Java", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("PHP_KEYWORD", "PHP", "PHP", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("RUBY_KEYWORD", "Ruby", "Ruby", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("SWIFT_KEYWORD", "Swift", "Swift", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("SWIFT.TYPE", "Swift", "Swift", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("MARKDOWN_HEADER", "Markdown", "Markdown", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("LUA_KEYWORD", "Lua", "Lua", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("SCALA_CLASS", "Scala", "Scala", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("MAKEFILE_KEYWORD", "Makefile", "Makefile", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("XML_TAG", "XML", "XML", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("YAML_KEY", "YAML", "YAML", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("HTML_TAG", "HTML", "HTML", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("GRAPHQL_KEYWORD", "GraphQL", "GraphQL", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("DOCKER_KEYWORD", "Docker", "Docker", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("DJANGO_KEYWORD", "Django", "Django", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("HAML_KEYWORD", "HAML", "HAML", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("SLIM_KEYWORD", "Slim", "Slim", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            // Dot-namespaced bucket
            Row("PY.KEYWORD", "Python", "Python", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("JS.GLOBAL_FUNCTION", "JavaScript", "JavaScript", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("TS.KEYWORD", "TypeScript", "TypeScript", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("CSS.PROPERTY", "CSS", "CSS", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("BASH.KEYWORD", "Bash", "Bash", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("HCL.IDENTIFIER", "HCL", "HCL", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("DQL_PLACEHOLDER", "DQL", "DQL", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("JSON.PROPERTY_KEY", "JSON", "JSON", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            // Space-separated bucket — verifies space-rule fires before underscore-rule
            Row("Scala Line comment", "Scala", "Scala", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("Groovy keyword", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("Groovy method declaration", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("Groovy constructor declaration", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("Groovy constructor call", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("Groovy var", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("Groovy reassigned var", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("Groovy parameter", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("Groovy reassigned parameter", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("GROOVY_KEYWORD", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("GString", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("Groovydoc comment", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row("Groovydoc tag", "Groovy", "Groovy", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            // Plugin-namespaced bucket
            Row("org.rust.IDENTIFIER", "Rust", "Rust", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            Row(
                "ReSharper.CSHARP_KEYWORD",
                "ReSharperCSharp",
                "C# (ReSharper)",
                SyntaxLanguageRegistry.Bucket.LANGUAGE,
            ),
        )

    private val cascadeKeys =
        listOf(
            "DEFAULT_LINE_COMMENT",
            "DEFAULT_BLOCK_COMMENT",
            "DEFAULT_DOC_COMMENT",
            "DEFAULT_STRING",
            "DEFAULT_NUMBER",
        )

    private val diagnosticsKeys =
        listOf(
            "WARNING_ATTRIBUTES",
            "ERRORS_ATTRIBUTES",
            "TYPO",
            "TODO_DEFAULT_ATTRIBUTES",
        )

    private val editorOverlayKeys =
        listOf(
            "BREAKPOINT_ATTRIBUTES",
            "FOLDED_TEXT_ATTRIBUTES",
            "LIVE_TEMPLATE_ATTRIBUTES",
            "DIFF_MODIFIED",
            "HYPERLINK_ATTRIBUTES",
        )

    private val genericBareKeys =
        listOf(
            "String",
            "Number",
            "Braces",
            "Brackets",
            "Parentheses",
            "Method call",
            "Static method access",
            "Map key",
            "Class",
            "Type parameter",
        )

    @Test
    fun `classify routes language prefixes to expected tag display bucket`() {
        for ((key, expected) in languageRows) {
            val tag = SyntaxLanguageRegistry.classify(key)
            assertEquals(expected.tag, tag.tag, "tag for $key")
            assertEquals(expected.displayName, tag.displayName, "displayName for $key")
            assertEquals(expected.bucket, tag.bucket, "bucket for $key")
        }
    }

    @Test
    fun `classify routes DEFAULT_ cascade keys to CASCADE bucket`() {
        for (key in cascadeKeys) {
            val tag = SyntaxLanguageRegistry.classify(key)
            assertEquals(SyntaxLanguageRegistry.Bucket.CASCADE, tag.bucket, "bucket for $key")
        }
    }

    @Test
    fun `classify routes diagnostics keys to DIAGNOSTICS bucket`() {
        for (key in diagnosticsKeys) {
            val tag = SyntaxLanguageRegistry.classify(key)
            assertEquals(SyntaxLanguageRegistry.Bucket.DIAGNOSTICS, tag.bucket, "bucket for $key")
        }
    }

    @Test
    fun `classify routes editor-overlay keys to EDITOR_OVERLAY bucket`() {
        for (key in editorOverlayKeys) {
            val tag = SyntaxLanguageRegistry.classify(key)
            assertEquals(SyntaxLanguageRegistry.Bucket.EDITOR_OVERLAY, tag.bucket, "bucket for $key")
        }
    }

    @Test
    fun `classify unknown prefix returns OTHER tag`() {
        val tag = SyntaxLanguageRegistry.classify("ZZZ_NOVEL_KEY_UNIQUE_${System.nanoTime()}")
        assertEquals("OTHER", tag.tag, "tag")
        assertEquals("Other", tag.displayName, "displayName")
        assertEquals(SyntaxLanguageRegistry.Bucket.OTHER, tag.bucket, "bucket")
    }

    @Test
    fun `classify keeps generic bare keys out of the Groovy language bucket`() {
        for (key in genericBareKeys) {
            val tag = SyntaxLanguageRegistry.classify(key)
            assertEquals("OTHER", tag.tag, "tag for $key")
            assertEquals("Other", tag.displayName, "displayName for $key")
            assertEquals(SyntaxLanguageRegistry.Bucket.OTHER, tag.bucket, "bucket for $key")
        }
    }

    @Test
    fun `classify unknown prefix returns OTHER on repeated invocations (latch idempotent)`() {
        val key = "ZZZUNK_PREFIX_REPEAT_${System.nanoTime()}"
        val first = SyntaxLanguageRegistry.classify(key)
        val second = SyntaxLanguageRegistry.classify(key)
        assertEquals(SyntaxLanguageRegistry.Bucket.OTHER, first.bucket, "first bucket")
        assertEquals(SyntaxLanguageRegistry.Bucket.OTHER, second.bucket, "second bucket")
        assertEquals(first, second, "OTHER tag is stable across repeated calls")
    }

    @Test
    fun `supportedLanguages preserves the advertised language contract`() {
        val supported = SyntaxLanguageRegistry.supportedLanguages().mapTo(linkedSetOf()) { it.displayName }

        assertEquals(
            setOf(
                "Angular",
                "Apple plist",
                "Bash",
                "C# (ReSharper)",
                "CodeQL",
                "CoffeeScript",
                "Cron expression",
                "CSS",
                "Dart",
                "Django",
                "Docker",
                "dotenv",
                "DQL",
                "Drools",
                "EditorConfig",
                "Erlang",
                "FreeMarker",
                "Gherkin",
                "GitLab CI",
                "Go",
                "GraphQL",
                "Groovy",
                "HAML",
                "HCL",
                "HTML",
                "HTTP client",
                "Ignore files",
                "Java",
                "JavaScript",
                "JSON",
                "JSONPath",
                "Kotlin",
                "Lua",
                "Makefile",
                "Markdown",
                "Nginx",
                "Objective-C",
                "PHP",
                "PowerShell",
                "Properties files",
                "Protobuf",
                "Protobuf text",
                "Puppet",
                "Python",
                "Qute",
                "RegExp",
                "Ruby",
                "Rust",
                "Sass",
                "Scala",
                "Slim",
                "Swift",
                "TIL",
                "TypeScript",
                "Velocity",
                "Vue",
                "Windows Batch",
                "XML",
                "YAML",
            ),
            supported,
        )
    }

    @Test
    fun `supportedLanguages excludes non-LANGUAGE buckets`() {
        val supported = SyntaxLanguageRegistry.supportedLanguages()
        for ((tag, displayName, bucket) in supported) {
            assertEquals(
                SyntaxLanguageRegistry.Bucket.LANGUAGE,
                bucket,
                "supportedLanguages must not leak bucket=$bucket ($tag/$displayName)",
            )
        }
    }

    @Test
    fun `supportedLanguages sorted alphabetically by displayName`() {
        val displays = SyntaxLanguageRegistry.supportedLanguages().map { it.displayName }
        assertEquals(displays.sorted(), displays, "supportedLanguages must be alphabetically sorted")
    }

    @Test
    fun `supportedLanguages dedups multi-prefix tags (eg Ruby with RUBY_ and RBS_)`() {
        val supported = SyntaxLanguageRegistry.supportedLanguages()
        val tagCounts = supported.groupingBy { it.tag }.eachCount()
        for ((tag, count) in tagCounts) {
            assertEquals(1, count, "tag '$tag' appears $count times — supportedLanguages must dedup by tag")
        }
    }

    @Test
    fun `space-separated rule precedes underscore rule (Scala Line comment NOT SCALA_)`() {
        // If the underscore rule matched first via containsMatchIn, "Scala Line comment"
        // would still hit ^SCALA_ via case-insensitive accident — but ^SCALA_ is uppercased
        // so this should pin the space-rule precedence specifically.
        val tag = SyntaxLanguageRegistry.classify("Scala Line comment")
        assertEquals("Scala", tag.tag)
        assertEquals(SyntaxLanguageRegistry.Bucket.LANGUAGE, tag.bucket)
    }

    @Test
    fun `unknown-prefix latch does not flood when classify is called many times for the same key`() {
        // No direct log capture without IntelliJ test fixture; we assert the side-effect-
        // free contract: repeated classify returns the same OTHER tag and does not throw.
        val key = "WEIRDUNKNOWNNS_X_${System.nanoTime()}"
        repeat(50) {
            assertEquals(SyntaxLanguageRegistry.Bucket.OTHER, SyntaxLanguageRegistry.classify(key).bucket)
        }
        // After repeated calls, the LANGUAGE picker still excludes OTHER entries.
        assertFalse(
            SyntaxLanguageRegistry.supportedLanguages().any { it.bucket == SyntaxLanguageRegistry.Bucket.OTHER },
            "OTHER must never leak into supportedLanguages",
        )
    }

    @Test
    fun `language specifications have unique stable ids aliases profiles and previews`() {
        val specifications = SyntaxLanguageRegistry.specifications()

        assertEquals(
            specifications.size,
            specifications.map { it.storageId }.toSet().size,
            "Language storage IDs must be unique",
        )
        for (language in specifications) {
            assertEquals(language, SyntaxLanguageRegistry.findByStorageId(language.storageId))
            assertTrue(language.aliases.isNotEmpty(), "${language.storageId} must declare at least one alias")
            assertTrue(language.nativeProfiles.isNotEmpty(), "${language.storageId} must declare a native profile")
            assertTrue(language.preview.files.isNotEmpty(), "${language.storageId} must declare preview evidence")
            for (alias in language.aliases) {
                assertEquals(language, SyntaxLanguageRegistry.resolveAlias(alias), "alias $alias")
            }
        }
    }

    @Test
    fun `Swift specification accepts Noctule as a native provider`() {
        val swift = requireNotNull(SyntaxLanguageRegistry.findByStorageId("Swift"))

        assertTrue(
            swift.nativeProfiles
                .single()
                .fileTypeNames
                .contains("NoctuleSwift"),
        )
        assertEquals("dev.j-a.swift", requireNotNull(swift.pluginRequirement).pluginId)
        assertEquals("Noctule, the Swift IDE", swift.pluginRequirement.displayName)
    }

    @Test
    fun `Ruby exposes only provider-backed primitives`() {
        val ruby = requireNotNull(SyntaxLanguageRegistry.findByStorageId("Ruby"))
        val primitives = ruby.preview.files.flatMapTo(linkedSetOf(), PreviewFileSpec::demonstratedCategories)

        assertTrue(PrimitiveCategory.INSTANCE_FIELD in primitives)
        assertFalse(PrimitiveCategory.INTERFACE_DECL in primitives)
    }

    @Test
    fun `Swift exposes only Noctule-backed primitives`() {
        val swift = requireNotNull(SyntaxLanguageRegistry.findByStorageId("Swift"))
        val primitives = swift.preview.files.flatMapTo(linkedSetOf(), PreviewFileSpec::demonstratedCategories)

        assertTrue(PrimitiveCategory.INTERFACE_DECL in primitives)
        assertFalse(PrimitiveCategory.TYPE_REF in primitives)
        assertFalse(PrimitiveCategory.GENERICS in primitives)
        assertFalse(PrimitiveCategory.STATIC_FIELD in primitives)
        assertFalse(PrimitiveCategory.DOCUMENTATION in primitives)
    }

    @Test
    fun `Gherkin exposes only provider-backed primitives`() {
        val gherkin = requireNotNull(SyntaxLanguageRegistry.findByStorageId("Gherkin"))
        val primitives = gherkin.preview.files.flatMapTo(linkedSetOf(), PreviewFileSpec::demonstratedCategories)

        assertTrue(PrimitiveCategory.PARAMETER in primitives)
        assertFalse(PrimitiveCategory.STRING_LITERAL in primitives)
    }

    @Test
    fun `native profiles include provider identities`() {
        val expectedIdentities =
            mapOf(
                "Angular" to (setOf("Angular2Html") to setOf("Angular2Html")),
                "Django" to (setOf("DjangoTemplate") to setOf("DjangoTemplate")),
                "Docker" to (setOf("Dockerfile") to setOf("Dockerfile")),
                "dotenv" to (setOf(".env file") to setOf("DotEnv")),
                "FreeMarker" to (setOf("FTL") to setOf("FTL")),
                "Gherkin" to (setOf("Cucumber") to setOf("Gherkin")),
                "GitLab CI" to
                    (setOf("GitLabCiExpression") to setOf("GitLabCiExpressionLanguage")),
                "HTTP client" to (setOf("HTTP Request") to setOf("HTTP Request")),
                "Objective-C" to (setOf("ObjectiveC") to setOf("ObjectiveC")),
                "Protobuf text" to (setOf("prototext") to setOf("prototext")),
                "Ruby" to (setOf("Ruby") to setOf("ruby")),
                "Sass" to (setOf("SCSS") to setOf("SCSS")),
                "Velocity" to (setOf("VTL") to setOf("VTL")),
                "Vue" to (setOf("Vue.js") to setOf("Vue")),
            )

        expectedIdentities.forEach { (language, identity) ->
            val profile = requireNotNull(SyntaxLanguageRegistry.findByStorageId(language)).nativeProfiles.single()

            assertTrue(profile.fileTypeNames.containsAll(identity.first), language)
            assertTrue(profile.languageIds.containsAll(identity.second), language)
        }
    }

    @Test
    fun `GitLab CI previews native expressions while matching its YAML host file`() {
        val gitLabCi = requireNotNull(SyntaxLanguageRegistry.findByStorageId("GitLab CI"))
        val profile = gitLabCi.nativeProfiles.single()
        val preview = gitLabCi.preview.files.single()

        assertEquals("preview.gitlabciexpression", preview.fileName)
        assertEquals(setOf(".gitlab-ci.yml"), profile.exactFileNames)
    }
}
