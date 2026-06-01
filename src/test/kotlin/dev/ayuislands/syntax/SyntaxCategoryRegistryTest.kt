package dev.ayuislands.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RED → GREEN coverage for [SyntaxCategoryRegistry]. Pins:
 *  - per-suffix classification across Java / Kotlin / Python / JS / TS keys
 *  - language-aware composition lock (D-06, Codex MEDIUM #8): the registry
 *    MUST delegate to [SyntaxLanguageRegistry.classify] before applying its
 *    own suffix table — proven indirectly by CASCADE-bucket keys
 *    (`DEFAULT_*`) still classifying via the suffix rule and OTHER-bucket
 *    keys still classifying instead of auto-null-ing
 *  - Pattern A latched unknown-suffix INFO log (one log per (suffix, session))
 *  - >=95% coverage on the curated Mirage baseline overlay set, computed via
 *    classpath read of the in-repo theme XML — no IDE service container is
 *    consulted, keeping Wave 2 strictly pure-compute
 *
 * NOTE: tests use `kotlin.test` (JUnit 5 backed) to match the codebase
 * convention. No `LightPlatformTestCase` setup is required because the
 * registry is a plain object with no IDE-state dependency.
 */
class SyntaxCategoryRegistryTest {
    // ---------------------------------------------------------------------
    // Suffix-rule coverage — per-language probes
    // ---------------------------------------------------------------------

    @Test
    fun `JAVA_KEYWORD classifies to KEYWORD`() {
        assertEquals(PrimitiveCategory.KEYWORD, SyntaxCategoryRegistry.classify("JAVA_KEYWORD"))
        assertEquals(
            SyntaxLanguageRegistry.Bucket.LANGUAGE,
            SyntaxLanguageRegistry.classify("JAVA_KEYWORD").bucket,
            "JAVA_KEYWORD should be in the LANGUAGE bucket per SyntaxLanguageRegistry",
        )
    }

    @Test
    fun `KOTLIN_KEYWORD classifies to KEYWORD`() {
        assertEquals(PrimitiveCategory.KEYWORD, SyntaxCategoryRegistry.classify("KOTLIN_KEYWORD"))
        assertEquals("Kotlin", SyntaxLanguageRegistry.classify("KOTLIN_KEYWORD").tag)
    }

    @Test
    fun `PY_KEYWORD (dot-namespaced) classifies to KEYWORD`() {
        assertEquals(PrimitiveCategory.KEYWORD, SyntaxCategoryRegistry.classify("PY.KEYWORD"))
        assertEquals("Python", SyntaxLanguageRegistry.classify("PY.KEYWORD").tag)
    }

    @Test
    fun `JAVA_STRING classifies to STRING_LITERAL`() {
        assertEquals(PrimitiveCategory.STRING_LITERAL, SyntaxCategoryRegistry.classify("JAVA_STRING"))
    }

    @Test
    fun `JAVA_NUMBER classifies to NUMBER_LITERAL`() {
        assertEquals(PrimitiveCategory.NUMBER_LITERAL, SyntaxCategoryRegistry.classify("JAVA_NUMBER"))
    }

    @Test
    fun `JAVA_LINE_COMMENT classifies to COMMENT`() {
        assertEquals(PrimitiveCategory.COMMENT, SyntaxCategoryRegistry.classify("JAVA_LINE_COMMENT"))
    }

    @Test
    fun `JAVA_BLOCK_COMMENT classifies to COMMENT`() {
        assertEquals(PrimitiveCategory.COMMENT, SyntaxCategoryRegistry.classify("JAVA_BLOCK_COMMENT"))
    }

    @Test
    fun `JAVA_DOC_COMMENT classifies to DOCUMENTATION (more-specific suffix wins)`() {
        assertEquals(
            PrimitiveCategory.DOCUMENTATION,
            SyntaxCategoryRegistry.classify("JAVA_DOC_COMMENT"),
            "DOC_COMMENT must beat COMMENT — order of suffix rules matters",
        )
    }

    @Test
    fun `KOTLIN_FUNCTION_DECLARATION classifies to FUNCTION_DECL`() {
        assertEquals(
            PrimitiveCategory.FUNCTION_DECL,
            SyntaxCategoryRegistry.classify("KOTLIN_FUNCTION_DECLARATION"),
        )
    }

    @Test
    fun `JAVA_CLASS_NAME classifies to CLASS_DECL (decl + name fold to CLASS_DECL at this granularity)`() {
        assertEquals(PrimitiveCategory.CLASS_DECL, SyntaxCategoryRegistry.classify("JAVA_CLASS_NAME"))
    }

    @Test
    fun `JAVA_PARAMETER classifies to PARAMETER`() {
        assertEquals(PrimitiveCategory.PARAMETER, SyntaxCategoryRegistry.classify("JAVA_PARAMETER"))
    }

    @Test
    fun `JAVA_LOCAL_VARIABLE classifies to LOCAL_VAR`() {
        assertEquals(
            PrimitiveCategory.LOCAL_VAR,
            SyntaxCategoryRegistry.classify("JAVA_LOCAL_VARIABLE"),
        )
    }

    @Test
    fun `JAVA_OPERATION_SIGN classifies to OPERATOR`() {
        assertEquals(
            PrimitiveCategory.OPERATOR,
            SyntaxCategoryRegistry.classify("JAVA_OPERATION_SIGN"),
        )
    }

    @Test
    fun `JAVA_ANNOTATION classifies to ANNOTATION`() {
        assertEquals(
            PrimitiveCategory.ANNOTATION,
            SyntaxCategoryRegistry.classify("JAVA_ANNOTATION"),
        )
    }

    @Test
    fun `JAVA_STATIC_FIELD_IMPORTED_ATTRIBUTES classifies to STATIC_FIELD`() {
        assertEquals(
            PrimitiveCategory.STATIC_FIELD,
            SyntaxCategoryRegistry.classify("JAVA_STATIC_FIELD_IMPORTED_ATTRIBUTES"),
        )
    }

    @Test
    fun `JAVA_INSTANCE_FIELD_ATTRIBUTES classifies to INSTANCE_FIELD`() {
        assertEquals(
            PrimitiveCategory.INSTANCE_FIELD,
            SyntaxCategoryRegistry.classify("JAVA_INSTANCE_FIELD_ATTRIBUTES"),
        )
    }

    @Test
    fun `KOTLIN_GENERICS classifies to GENERICS`() {
        assertEquals(
            PrimitiveCategory.GENERICS,
            SyntaxCategoryRegistry.classify("KOTLIN_GENERICS"),
        )
    }

    @Test
    fun `all ignore plugin keys classify for Syntax presets`() {
        val expectedCategories =
            mapOf(
                "IGNORE.COMMENT" to PrimitiveCategory.COMMENT,
                "IGNORE.SECTION" to PrimitiveCategory.KEYWORD,
                "IGNORE.HEADER" to PrimitiveCategory.KEYWORD,
                "IGNORE.NEGATION" to PrimitiveCategory.KEYWORD,
                "IGNORE.BRACKET" to PrimitiveCategory.OPERATOR,
                "IGNORE.SLASH" to PrimitiveCategory.KEYWORD,
                "IGNORE.SYNTAX" to PrimitiveCategory.KEYWORD,
                "IGNORE.VALUE" to PrimitiveCategory.STRING_LITERAL,
                "IGNORE.UNUSED_ENTRY" to PrimitiveCategory.COMMENT,
            )

        for ((key, expected) in expectedCategories) {
            assertEquals(
                expected,
                SyntaxCategoryRegistry.classify(key),
                "$key must classify so Syntax presets can transform .ignore plugin colors.",
            )
            assertEquals(
                "Ignore",
                SyntaxLanguageRegistry.classify(key).tag,
                "$key must stay grouped under the Ignore language tag.",
            )
        }
    }

    // ---------------------------------------------------------------------
    // Unknown suffix + Pattern A latch
    // ---------------------------------------------------------------------

    @Test
    fun `unknown suffix returns null`() {
        assertNull(
            SyntaxCategoryRegistry.classify("UNKNOWN_FOO_BAR"),
            "Unknown suffixes must return null so the applicator can skip them safely",
        )
    }

    @Test
    fun `repeated unknown-suffix calls are idempotent and safe (Pattern A latch)`() {
        // Use a unique suffix so the persistent ConcurrentHashMap latch
        // (object state survives across tests) does not collide with prior
        // invocations. Two calls to the same unknown key MUST both return
        // null without throwing — the Pattern A latch deduplicates the
        // INFO log silently.
        //
        // We don't capture the INFO line here because IntelliJ's
        // LoggedErrorProcessor only intercepts errors and warnings; INFO
        // routes through Logger.getInstance and is platform-version
        // dependent. The functional contract — null result, no throw,
        // no log spam — is what the applicator depends on, and that is
        // what we lock down.
        val uniqueSuffix = "UNIQUE_${System.nanoTime()}"
        val keyName = "ACME_$uniqueSuffix"

        assertNull(SyntaxCategoryRegistry.classify(keyName))
        // Second invocation hits the latched-already branch and still must
        // return null. If the latch logic regressed (e.g., wrote to the
        // set before checking add(), or threw on duplicates), this call
        // would fail.
        assertNull(SyntaxCategoryRegistry.classify(keyName))
        assertNull(SyntaxCategoryRegistry.classify(keyName))
    }

    // ---------------------------------------------------------------------
    // Coverage threshold — classpath read, no @Service / no IDE container
    // ---------------------------------------------------------------------

    @Test
    fun `classifier covers at least 95 percent of Mirage baseline overlay keys`() {
        // Read overlay XML directly from classpath — keeps the test pure-unit
        // and avoids any IDE service container dependency. The XML lives in
        // src/main/resources/themes/extended and is automatically on the test
        // classpath via Gradle's default configuration.
        val stream =
            javaClass.classLoader.getResourceAsStream("themes/extended/AyuIslandsMirage.extended.xml")
                ?: error("themes/extended/AyuIslandsMirage.extended.xml missing from test classpath")
        val xmlContent = stream.use { it.readBytes().decodeToString() }

        // Top-level attribute keys are wrapped in <option name="X"><value>...
        // Child <option name="FOREGROUND" value="..."/> have NO <value>
        // sibling, so the lookahead filters them out cleanly.
        val keyNamePattern = Regex("""<option\s+name="([^"]+)"\s*>\s*<value>""")
        val keyNames: Set<String> =
            keyNamePattern
                .findAll(xmlContent)
                .map { it.groupValues[1] }
                .toSet()

        require(keyNames.size >= 50) {
            "Mirage overlay should expose at least 50 top-level keys; got ${keyNames.size}"
        }

        val total = keyNames.size
        val classified = keyNames.count { SyntaxCategoryRegistry.classify(it) != null }
        val unclassified = keyNames.filter { SyntaxCategoryRegistry.classify(it) == null }
        val ratio = classified.toFloat() / total.toFloat()

        if (ratio < 0.95f) {
            System.err.println(
                "Unclassified Mirage keys (need new suffix rules): $unclassified",
            )
        }
        assertTrue(
            ratio >= 0.95f,
            "Coverage on Mirage baseline overlay: $classified/$total = " +
                "${"%.2f".format(ratio * 100)}% — must be >= 95%",
        )
    }

    // ---------------------------------------------------------------------
    // Language-aware composition lock (Codex MEDIUM #8)
    // ---------------------------------------------------------------------

    @Test
    fun `CASCADE-bucket DEFAULT_LINE_COMMENT classifies to COMMENT via suffix rule`() {
        // DEFAULT_LINE_COMMENT is in SyntaxLanguageRegistry.Bucket.CASCADE
        // (no language prefix). The classifier must still return COMMENT
        // through the suffix rule, proving the language step does NOT
        // short-circuit to null for non-LANGUAGE buckets.
        assertEquals(
            SyntaxLanguageRegistry.Bucket.CASCADE,
            SyntaxLanguageRegistry.classify("DEFAULT_LINE_COMMENT").bucket,
            "Sanity: DEFAULT_LINE_COMMENT must be CASCADE per SyntaxLanguageRegistry",
        )
        assertEquals(
            PrimitiveCategory.COMMENT,
            SyntaxCategoryRegistry.classify("DEFAULT_LINE_COMMENT"),
            "CASCADE-bucket keys must still classify via the suffix rule",
        )
    }

    @Test
    fun `CASCADE-bucket DEFAULT_STRING classifies to STRING_LITERAL via suffix rule`() {
        assertEquals(
            SyntaxLanguageRegistry.Bucket.CASCADE,
            SyntaxLanguageRegistry.classify("DEFAULT_STRING").bucket,
        )
        assertEquals(
            PrimitiveCategory.STRING_LITERAL,
            SyntaxCategoryRegistry.classify("DEFAULT_STRING"),
        )
    }

    @Test
    fun `OTHER-bucket key still classifies by suffix (not auto-null)`() {
        // RECTANGLE_TEXT_KEYWORD has no language prefix in
        // SyntaxLanguageRegistry's prefixRules table, so it falls into the
        // OTHER bucket. The classifier MUST still match the KEYWORD suffix
        // — the language identification is NOT a gate.
        val langTag = SyntaxLanguageRegistry.classify("RECTANGLE_TEXT_KEYWORD")
        // It may end up in OTHER (no prefix match); the contract is that the
        // category classifier is unaffected by the language bucket result.
        assertNotNull(langTag, "SyntaxLanguageRegistry must always return a tag")
        assertEquals(
            PrimitiveCategory.KEYWORD,
            SyntaxCategoryRegistry.classify("RECTANGLE_TEXT_KEYWORD"),
            "OTHER-bucket keys are NOT auto-null — the suffix rule still applies",
        )
    }
}
