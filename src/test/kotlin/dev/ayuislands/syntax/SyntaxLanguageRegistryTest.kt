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
 * prefix OTHER fallback with log-once latch, and the [supportedLanguages]
 * picker contract (≥26 LANGUAGE entries, no non-LANGUAGE leaks,
 * alphabetical sort by displayName).
 */
class SyntaxLanguageRegistryTest {
    private data class Row(
        val key: String,
        val tag: String,
        val displayName: String,
        val bucket: SyntaxLanguageRegistry.Bucket,
    )

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
        for (row in languageRows) {
            val tag = SyntaxLanguageRegistry.classify(row.key)
            assertEquals(row.tag, tag.tag, "tag for ${row.key}")
            assertEquals(row.displayName, tag.displayName, "displayName for ${row.key}")
            assertEquals(row.bucket, tag.bucket, "bucket for ${row.key}")
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
    fun `supportedLanguages returns at least 26 LANGUAGE entries`() {
        val supported = SyntaxLanguageRegistry.supportedLanguages()
        assertTrue(supported.size >= 26, "expected >=26 LANGUAGE entries, got ${supported.size}")
    }

    @Test
    fun `supportedLanguages excludes non-LANGUAGE buckets`() {
        val supported = SyntaxLanguageRegistry.supportedLanguages()
        for (entry in supported) {
            assertEquals(
                SyntaxLanguageRegistry.Bucket.LANGUAGE,
                entry.bucket,
                "supportedLanguages must not leak bucket=${entry.bucket} (${entry.tag})",
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
}
