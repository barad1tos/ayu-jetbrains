package dev.ayuislands.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RED → GREEN coverage for [SyntaxLanguageRegistry.cascadeTargets] and
 * [SyntaxLanguageRegistry.cascadeKeysInScope]. Pins the closed catalog of 5
 * cascade keys × ~14 languages per Phase 50 RESEARCH OQ-04 conservative scope,
 * plus the per-language materialization edge cases (Go uses `GO_COMMENT` not
 * `GO_LINE_COMMENT`; Java has no `JAVA_STRING` materialization; Scala line/
 * block comments use space-separated keys).
 */
class LanguageCascadeMapTest {
    private data class CascadeRow(
        val language: String,
        val defaultKey: String,
        val expected: String,
    )

    private val materialized =
        listOf(
            CascadeRow("Java", "DEFAULT_LINE_COMMENT", "JAVA_LINE_COMMENT"),
            CascadeRow("Java", "DEFAULT_BLOCK_COMMENT", "JAVA_BLOCK_COMMENT"),
            CascadeRow("Java", "DEFAULT_DOC_COMMENT", "JAVA_DOC_COMMENT"),
            CascadeRow("Kotlin", "DEFAULT_LINE_COMMENT", "KOTLIN_LINE_COMMENT"),
            CascadeRow("Kotlin", "DEFAULT_BLOCK_COMMENT", "KOTLIN_BLOCK_COMMENT"),
            CascadeRow("Kotlin", "DEFAULT_DOC_COMMENT", "KOTLIN_DOC_COMMENT"),
            CascadeRow("Go", "DEFAULT_LINE_COMMENT", "GO_COMMENT"),
            CascadeRow("Python", "DEFAULT_LINE_COMMENT", "PY.LINE_COMMENT"),
            CascadeRow("Scala", "DEFAULT_LINE_COMMENT", "Scala Line comment"),
            CascadeRow("Rust", "DEFAULT_LINE_COMMENT", "org.rust.LINE_COMMENT"),
            CascadeRow("Rust", "DEFAULT_BLOCK_COMMENT", "org.rust.BLOCK_COMMENT"),
        )

    @Test
    fun `cascadeTargets materializes per-language keys per closed catalog`() {
        for (row in materialized) {
            val out = SyntaxLanguageRegistry.cascadeTargets(row.language, row.defaultKey)
            assertEquals(row.expected, out, "${row.language} ${row.defaultKey}")
        }
    }

    @Test
    fun `cascadeTargets returns null when language has no materialization for the cascade key`() {
        assertNull(
            SyntaxLanguageRegistry.cascadeTargets("Java", "DEFAULT_STRING"),
            "Java has no DEFAULT_STRING materialization in the Phase 50A catalog",
        )
    }

    @Test
    fun `cascadeTargets returns null for unknown language tag`() {
        assertNull(
            SyntaxLanguageRegistry.cascadeTargets("UnknownLang", "DEFAULT_LINE_COMMENT"),
            "unknown language must not invent a materialization",
        )
    }

    @Test
    fun `cascadeKeysInScope returns exactly the 5 conservative-scope keys`() {
        val scope = SyntaxLanguageRegistry.cascadeKeysInScope()
        val expected =
            setOf(
                "DEFAULT_LINE_COMMENT",
                "DEFAULT_BLOCK_COMMENT",
                "DEFAULT_DOC_COMMENT",
                "DEFAULT_STRING",
                "DEFAULT_NUMBER",
            )
        assertEquals(expected, scope, "cascadeKeysInScope must equal Phase 50A 5-key conservative scope")
    }

    @Test
    fun `every materialized cascade target uses one of the 5 in-scope cascade keys`() {
        val scope = SyntaxLanguageRegistry.cascadeKeysInScope()
        for (row in materialized) {
            assertTrue(
                row.defaultKey in scope,
                "${row.defaultKey} must be in cascadeKeysInScope (closed catalog)",
            )
        }
    }
}
