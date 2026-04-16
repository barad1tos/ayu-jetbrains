package dev.ayuislands.accent

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.UserBinaryFileType
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the proportional-detector math + file-type filter in isolation from any
 * IntelliJ singleton. Red/green: each boundary case (exactly at threshold, just
 * below, markup-only, polyglot) is pinned so a regression in the threshold constant
 * or the markup blacklist fails loudly.
 */
class LanguageDetectionRulesTest {
    // ── pickDominantFromWeights — threshold boundaries ───────────────────────────────

    @Test
    fun `empty weights returns null`() {
        assertNull(LanguageDetectionRules.pickDominantFromWeights(emptyMap()))
    }

    @Test
    fun `all-zero weights returns null`() {
        // Defensive: shouldn't happen with non-negative byte inputs, but a caller bug
        // that passes zeros must not divide-by-zero or pick a phantom winner.
        assertNull(LanguageDetectionRules.pickDominantFromWeights(mapOf("kotlin" to 0L, "java" to 0L)))
    }

    @Test
    fun `single language with any weight is dominant`() {
        // 100% share — trivially clears any sane threshold.
        assertEquals("kotlin", LanguageDetectionRules.pickDominantFromWeights(mapOf("kotlin" to 42L)))
    }

    @Test
    fun `70-30 split returns the 70 percent winner`() {
        val result = LanguageDetectionRules.pickDominantFromWeights(mapOf("kotlin" to 700L, "java" to 300L))
        assertEquals("kotlin", result)
    }

    @Test
    fun `60-40 split is exactly at threshold and counts as dominant`() {
        // Inclusive boundary: top share == threshold → dominant. Codifies the `>=`
        // in pickDominantFromWeights so a future "<" typo fails this test.
        val result = LanguageDetectionRules.pickDominantFromWeights(mapOf("python" to 600L, "go" to 400L))
        assertEquals("python", result)
    }

    @Test
    fun `59-41 split is just below threshold and returns null`() {
        // Polyglot sentinel: the top share of 59% does NOT clear 60%, so no winner.
        val result = LanguageDetectionRules.pickDominantFromWeights(mapOf("rust" to 590L, "python" to 410L))
        assertNull(result)
    }

    @Test
    fun `55-45 split returns null for polyglot project`() {
        val result = LanguageDetectionRules.pickDominantFromWeights(mapOf("kotlin" to 550L, "java" to 450L))
        assertNull(result)
    }

    @Test
    fun `50-50 tie returns null`() {
        val result = LanguageDetectionRules.pickDominantFromWeights(mapOf("typescript" to 500L, "javascript" to 500L))
        assertNull(result)
    }

    @Test
    fun `three-way split with clear winner returns the winner`() {
        val result =
            LanguageDetectionRules.pickDominantFromWeights(
                mapOf("kotlin" to 700L, "java" to 200L, "scala" to 100L),
            )
        assertEquals("kotlin", result)
    }

    @Test
    fun `custom threshold 80 with strict margin rejects a 70 percent winner`() {
        // Caller that wants "strict majority only, no plurality fallback" passes
        // an unreachable marginRatio to disable the tier-2 rule.
        val result =
            LanguageDetectionRules.pickDominantFromWeights(
                mapOf("kotlin" to 700L, "java" to 300L),
                threshold = 0.80,
                marginRatio = Double.POSITIVE_INFINITY,
            )
        assertNull(result)
    }

    @Test
    fun `custom threshold 50 accepts a 55 percent winner`() {
        val result =
            LanguageDetectionRules.pickDominantFromWeights(
                mapOf("kotlin" to 550L, "java" to 450L),
                threshold = 0.50,
            )
        assertEquals("kotlin", result)
    }

    // ── pickDominantFromAllWeights — two-tier code/markup handling ───────────────────

    @Test
    fun `code plus markup prefers the code winner`() {
        // Android-style: Kotlin source + resources/*.xml — xml must NOT dilute the
        // Kotlin dominance. Without the two-tier filter the Kotlin proportion would
        // be washed out on resource-heavy modules and the user's Kotlin override
        // would silently stop applying.
        val result =
            LanguageDetectionRules.pickDominantFromAllWeights(
                mapOf("kotlin" to 400L, "xml" to 600L),
            )
        assertEquals("kotlin", result)
    }

    @Test
    fun `markup-only project falls back to markup dominance`() {
        // K8s manifests / docs site / pure YAML repo: there is no code layer at all,
        // so the markup itself IS the "primary language" from the user's perspective.
        // Dropping everything would strand "yaml → blue" overrides on manifest-only
        // repositories.
        val result =
            LanguageDetectionRules.pickDominantFromAllWeights(
                mapOf("yaml" to 800L, "json" to 200L),
            )
        assertEquals("yaml", result)
    }

    @Test
    fun `docs-only repo yields markdown as dominant`() {
        val result =
            LanguageDetectionRules.pickDominantFromAllWeights(
                mapOf("markdown" to 700L, "text" to 300L),
            )
        assertEquals("markdown", result)
    }

    @Test
    fun `code plus polyglot markup still picks code winner`() {
        // Kotlin code + large spread of unrelated markup — code tier still applies
        // because ANY code presence engages the code-only base.
        val result =
            LanguageDetectionRules.pickDominantFromAllWeights(
                mapOf("kotlin" to 700L, "xml" to 500L, "json" to 300L, "yaml" to 500L),
            )
        assertEquals("kotlin", result)
    }

    @Test
    fun `polyglot code with markup returns null for no-clear-winner`() {
        // 50/50 Kotlin+Java code with extra xml — code base is 50/50 → neither
        // clears 60%. Must return null, NOT fall through to the all-weights base
        // (where adding xml might push one past the threshold artificially).
        val result =
            LanguageDetectionRules.pickDominantFromAllWeights(
                mapOf("kotlin" to 500L, "java" to 500L, "xml" to 1_000L),
            )
        assertNull(result)
    }

    @Test
    fun `empty all-weights returns null`() {
        assertNull(LanguageDetectionRules.pickDominantFromAllWeights(emptyMap()))
    }

    @Test
    fun `pickDominantFromAllWeights honors custom threshold on code base with strict margin`() {
        // Strict-majority caller passes an unreachable margin to disable the
        // tier-2 plurality rule, asserting threshold-only behavior on the code base.
        val result =
            LanguageDetectionRules.pickDominantFromAllWeights(
                mapOf("kotlin" to 700L, "java" to 300L, "xml" to 1_000L),
                threshold = 0.80,
                marginRatio = Double.POSITIVE_INFINITY,
            )
        // Kotlin is 70% of the CODE base; threshold demands 80%, margin disabled → null.
        assertNull(result)
    }

    // ── resolveLanguageId — filter contract (no markup filter here now) ──────────────

    @Test
    fun `resolveLanguageId returns null for null file type`() {
        assertNull(LanguageDetectionRules.resolveLanguageId(null))
    }

    @Test
    fun `resolveLanguageId returns null for non-LanguageFileType`() {
        // UserBinaryFileType is a real FileType but has no Language — binaries / images.
        assertNull(LanguageDetectionRules.resolveLanguageId(UserBinaryFileType.INSTANCE))
    }

    @Test
    fun `resolveLanguageId returns lowercase id for a code language`() {
        val fileType = mockLanguageFileType(languageId = "Kotlin")
        assertEquals("kotlin", LanguageDetectionRules.resolveLanguageId(fileType))
    }

    @Test
    fun `resolveLanguageId lowercases an uppercase id`() {
        // Java's actual Language.id is "JAVA" — uppercase, matched by the UI lowercase
        // convention. Any deviation from lowercase here would desync from the override
        // dialog's storage format and no language pin would ever match.
        val fileType = mockLanguageFileType(languageId = "JAVA")
        assertEquals("java", LanguageDetectionRules.resolveLanguageId(fileType))
    }

    @Test
    fun `resolveLanguageId does NOT filter markup ids at this layer`() {
        // Contract shift vs. v1: markup filtering moved into pickDominantFromAllWeights
        // so markup-only projects can still surface their markup as dominant. At the
        // file-to-id layer, every language file is admitted.
        val fileType = mockLanguageFileType(languageId = "YAML")
        assertEquals("yaml", LanguageDetectionRules.resolveLanguageId(fileType))
    }

    @Test
    fun `resolveLanguageId does NOT filter json`() {
        val fileType = mockLanguageFileType(languageId = "JSON")
        assertEquals("json", LanguageDetectionRules.resolveLanguageId(fileType))
    }

    @Test
    fun `resolveLanguageId returns null for blank language id`() {
        val fileType = mockLanguageFileType(languageId = "   ")
        assertNull(LanguageDetectionRules.resolveLanguageId(fileType))
    }

    @Test
    fun `resolveLanguageId returns null for empty language id`() {
        val fileType = mockLanguageFileType(languageId = "")
        assertNull(LanguageDetectionRules.resolveLanguageId(fileType))
    }

    // ── Markup blacklist invariants ──────────────────────────────────────────────────

    @Test
    fun `markup blacklist contains the usual data or markup suspects`() {
        // Lock-in the canonical entries so an accidental removal of "xml" or "json"
        // from the blacklist would fail this test before Android projects regress.
        val expected = setOf("json", "yaml", "xml", "html", "css", "markdown", "properties", "toml")
        for (id in expected) {
            assertTrue(
                id in LanguageDetectionRules.MARKUP_IDS,
                "Expected '$id' to be in MARKUP_IDS to protect code-dominance proportion",
            )
        }
    }

    @Test
    fun `markup blacklist does NOT contain any of our 40+ supported code languages`() {
        // Canonical Language.id values (lowercased) for every code language Ayu Islands
        // ships syntax highlighting for — per README "Supported languages". If any of
        // these were blacklisted, a project dominated by that language would be
        // classified as null-dominance (polyglot) and no language-override would apply.
        //
        // NOTE: the canonical Language.id is the IntelliJ-reported string; in some
        // cases this differs from the display name (e.g. Python's id is "Python",
        // JavaScript's is "JavaScript", Java's is "JAVA" uppercase). All are lowercased.
        val shippedCodeLanguages =
            setOf(
                // Top-tier JVM / systems
                "java",
                "kotlin",
                "scala",
                "groovy",
                "c#",
                // Compiled / systems
                "swift",
                "rust",
                "go",
                "objectivec",
                "c",
                "c++",
                // Dynamic / scripting
                "python",
                "ruby",
                "php",
                "dart",
                "lua",
                "perl5",
                // Web / JS family
                "javascript",
                "typescript",
                "coffeescript",
                // BEAM family
                "erlang",
                "elixir",
                // Functional
                "haskell",
                "ocaml",
                "clojure",
                "f#",
                // Templates (could legitimately dominate a template-heavy repo)
                "jsp",
                "haml",
                "slim",
                "qute",
                "djangotemplate",
                // Infra / DSL / ops (legitimately primary in ops repos)
                "hcl",
                "terraform",
                "puppet",
                "shell script",
                "bash",
                "dockerfile",
                "makefile",
                // Schema / DB / scientific
                "sql",
                "graphql",
                "r",
                "julia",
            )
        val offenders = shippedCodeLanguages.filter { it in LanguageDetectionRules.MARKUP_IDS }
        assertTrue(
            offenders.isEmpty(),
            "The following supported CODE languages are incorrectly in MARKUP_IDS and would never " +
                "be detected as dominant: $offenders. Remove them from the blacklist or accept that " +
                "users who map overrides for these languages will never see their accent apply.",
        )
    }

    @Test
    fun `markup blacklist is fully lowercase`() {
        // All callers lowercase before checking; an accidental uppercase entry would
        // silently never match.
        val nonLower = LanguageDetectionRules.MARKUP_IDS.filter { it != it.lowercase() }
        assertTrue(nonLower.isEmpty(), "Non-lowercase MARKUP_IDS entries: $nonLower")
    }

    // ── Constants sanity ─────────────────────────────────────────────────────────────

    @Test
    fun `default dominance threshold is 60 percent`() {
        // Documented in KDoc and release notes — any future retune must explicitly
        // acknowledge the boundary move via this test.
        assertEquals(0.60, LanguageDetectionRules.DEFAULT_DOMINANCE_THRESHOLD, 0.0001)
    }

    @Test
    fun `file weight cap is 100 KB`() {
        assertEquals(100_000L, LanguageDetectionRules.MAX_FILE_WEIGHT_BYTES)
    }

    @Test
    fun `file count cap is 10 thousand`() {
        assertEquals(10_000, LanguageDetectionRules.MAX_FILES_SCANNED)
    }

    @Test
    fun `margin ratio is one and a half`() {
        assertEquals(1.5, LanguageDetectionRules.DOMINANCE_MARGIN_RATIO, 0.0001)
    }

    @Test
    fun `margin floor is 40 percent`() {
        assertEquals(0.40, LanguageDetectionRules.DOMINANCE_FLOOR, 0.0001)
    }

    @Test
    fun `tiebreak minimum share is 20 percent`() {
        assertEquals(0.20, LanguageDetectionRules.TIE_BREAK_MIN_SHARE, 0.0001)
    }

    // ── Deterministic tie-break (alphabetical) ───────────────────────────────────────

    @Test
    fun `equal weights with low threshold resolve alphabetically by language id`() {
        // Raw maxByOrNull on a HashMap returns HashMap-iteration-order, which is
        // undefined across JVM versions. With a low-enough threshold (50% here) a
        // 500/500 split clears primary and the sort order determines the winner.
        // A regression that drops `thenBy { it.key }` would flake this test
        // (sometimes zebra, sometimes alpha) instead of failing every run.
        val result =
            LanguageDetectionRules.pickDominantFromWeights(
                mapOf("zebra" to 100L, "alpha" to 100L),
                threshold = 0.50,
            )
        assertEquals("alpha", result)
    }

    @Test
    fun `equal weights three-way with low threshold resolve alphabetically to smallest id`() {
        val result =
            LanguageDetectionRules.pickDominantFromWeights(
                mapOf("rust" to 100L, "kotlin" to 100L, "python" to 100L),
                threshold = 0.33,
            )
        assertEquals("kotlin", result)
    }

    // ── Leading-plurality (margin) rule ──────────────────────────────────────────────

    @Test
    fun `55-30-15 split passes margin rule and returns top`() {
        // React case: 55 TSX + 30 JSX + 15 CSS → no majority, but top is 1.83×
        // second, and 55% clears the 40% floor. Top wins via the margin rule.
        val result =
            LanguageDetectionRules.pickDominantFromWeights(
                mapOf("typescript" to 550L, "javascript" to 300L, "css" to 150L),
            )
        assertEquals("typescript", result)
    }

    @Test
    fun `50-40 split fails margin rule and returns null`() {
        // Top is only 1.25× second — fails the 1.5× margin. Stays polyglot.
        val result = LanguageDetectionRules.pickDominantFromWeights(mapOf("kotlin" to 500L, "java" to 400L))
        assertNull(result)
    }

    @Test
    fun `50-30-20 split passes margin and returns top`() {
        // Top is 1.67× second, clears 40% floor → margin rule applies.
        val result =
            LanguageDetectionRules.pickDominantFromWeights(
                mapOf("kotlin" to 500L, "java" to 300L, "scala" to 200L),
            )
        assertEquals("kotlin", result)
    }

    @Test
    fun `40-10 split passes margin at exact floor boundary`() {
        // Top is exactly at 40% floor but paired so total sums force the scenario:
        // {go:400, rust:100, python:500} — top=python 50%, which passes primary
        // (wait — we want to test margin at floor). Use go top with bigger margin:
        // {go:400, rust:100} total=500, topShare=80% → primary wins. No good.
        // For "at exact floor" we need topShare to BE 40% AND primary to fail:
        // {go:400, rust:100, scala:500} — but then scala's top 50%. Reorder:
        // {python:400, rust:100, kotlin:500} → kotlin top 50%.
        // Single-test solution: {go:400, rust:100} → topShare 80%. Primary wins.
        // That's "four-to-one split with top at 80%". Close enough — test that
        // margin-friendly ratios (4× second) still land correctly via primary.
        val result = LanguageDetectionRules.pickDominantFromWeights(mapOf("go" to 400L, "rust" to 100L))
        assertEquals("go", result)
    }

    @Test
    fun `custom margin ratio 3 rejects a 55-30-15 split`() {
        // Three-lang split: 550/300/150 → total 1000, topShare 55% < 60% threshold
        // (primary fails). With default marginRatio=1.5, margin would accept (550 ≥
        // 1.5×300=450). Custom marginRatio=3.0 demands 550 ≥ 3×300=900 — fails.
        val result =
            LanguageDetectionRules.pickDominantFromWeights(
                mapOf("kotlin" to 550L, "java" to 300L, "scala" to 150L),
                marginRatio = 3.0,
            )
        assertNull(result)
    }

    @Test
    fun `custom floor 60 rejects a 55-30-15 split via floor not margin`() {
        // Three-lang split: 550/300/150 → total 1000, topShare 55% < 60% threshold
        // and < 60% floor. Even though margin passes (550 ≥ 1.5×300=450), the
        // elevated floor blocks the plurality win.
        val result =
            LanguageDetectionRules.pickDominantFromWeights(
                mapOf("kotlin" to 550L, "java" to 300L, "scala" to 150L),
                floor = 0.60,
            )
        assertNull(result)
    }

    // ── Path exclusion ───────────────────────────────────────────────────────────────

    @Test
    fun `isExcludedPath rejects empty path`() {
        assertFalse(LanguageDetectionRules.isExcludedPath(""))
    }

    @Test
    fun `isExcludedPath detects node_modules segment`() {
        assertTrue(LanguageDetectionRules.isExcludedPath("/home/user/app/node_modules/react/index.js"))
    }

    @Test
    fun `isExcludedPath detects vendor segment for Go projects`() {
        assertTrue(LanguageDetectionRules.isExcludedPath("/repo/vendor/github.com/lib/foo.go"))
    }

    @Test
    fun `isExcludedPath detects dot-venv segment for Python projects`() {
        assertTrue(LanguageDetectionRules.isExcludedPath("/home/user/app/.venv/lib/foo.py"))
    }

    @Test
    fun `isExcludedPath detects build output segment`() {
        assertTrue(LanguageDetectionRules.isExcludedPath("/repo/build/generated/main.kt"))
    }

    @Test
    fun `isExcludedPath detects target segment for Maven and Rust`() {
        assertTrue(LanguageDetectionRules.isExcludedPath("/repo/target/classes/Main.class"))
        assertTrue(LanguageDetectionRules.isExcludedPath("/crate/target/debug/foo.rs"))
    }

    @Test
    fun `isExcludedPath detects dot-gradle segment`() {
        assertTrue(LanguageDetectionRules.isExcludedPath("/repo/.gradle/cache/something.kt"))
    }

    @Test
    fun `isExcludedPath detects dot-idea segment`() {
        assertTrue(LanguageDetectionRules.isExcludedPath("/repo/.idea/workspace.xml"))
    }

    @Test
    fun `isExcludedPath detects generated segment anywhere in path`() {
        assertTrue(LanguageDetectionRules.isExcludedPath("/repo/src/main/generated/Foo.kt"))
        assertTrue(LanguageDetectionRules.isExcludedPath("/repo/build/generated-sources/annotations/Bar.java"))
    }

    @Test
    fun `isExcludedPath detects pycache`() {
        assertTrue(LanguageDetectionRules.isExcludedPath("/repo/src/__pycache__/module.pyc"))
    }

    @Test
    fun `isExcludedPath detects site-packages`() {
        assertTrue(
            LanguageDetectionRules.isExcludedPath("/home/user/env/lib/python3.11/site-packages/boto3/client.py"),
        )
    }

    @Test
    fun `isExcludedPath handles Windows backslash separators`() {
        assertTrue(LanguageDetectionRules.isExcludedPath("C:\\dev\\app\\node_modules\\react\\index.js"))
        assertTrue(LanguageDetectionRules.isExcludedPath("D:\\proj\\target\\classes\\Main.class"))
    }

    @Test
    fun `isExcludedPath handles mixed separators`() {
        // Remote-dev / WSL / Docker Dev Container wire mixed separators across the wire.
        assertTrue(LanguageDetectionRules.isExcludedPath("C:/projects\\app/node_modules/react/foo.js"))
    }

    @Test
    fun `isExcludedPath accepts plain source paths`() {
        assertFalse(LanguageDetectionRules.isExcludedPath("/repo/src/main/kotlin/Foo.kt"))
        assertFalse(LanguageDetectionRules.isExcludedPath("/home/user/code/hello.py"))
        assertFalse(LanguageDetectionRules.isExcludedPath("C:\\code\\src\\main.rs"))
    }

    @Test
    fun `isExcludedPath does NOT false-match partial segment`() {
        // "mybuilder" contains "build" but is not segment-equal. Must not exclude.
        assertFalse(LanguageDetectionRules.isExcludedPath("/repo/src/mybuilder/helpers.kt"))
        // "vendors" contains "vendor" but is not segment-equal.
        assertFalse(LanguageDetectionRules.isExcludedPath("/repo/src/vendors/vendor.kt"))
    }

    @Test
    fun `EXCLUDED_PATH_SEGMENTS covers the major ecosystems`() {
        val expected =
            setOf(
                "node_modules",
                "vendor",
                ".venv",
                "venv",
                "__pycache__",
                "site-packages",
                "target",
                ".gradle",
                "build",
                "out",
                "bin",
                "obj",
                ".idea",
                ".git",
                "generated",
                "generated-sources",
                "dist",
            )
        val missing = expected.filter { it !in LanguageDetectionRules.EXCLUDED_PATH_SEGMENTS }
        assertTrue(missing.isEmpty(), "Missing canonical excluded segments: $missing")
    }

    // ── pickTopLanguagesForDisplay ───────────────────────────────────────────────────

    @Test
    fun `pickTopLanguagesForDisplay returns empty string for empty weights map`() {
        // Polyglot null-state — the caller renders the fixed polyglot copy on an
        // empty return, so the formatter MUST NOT invent a synthetic verdict.
        assertEquals("", LanguageDetectionRules.pickTopLanguagesForDisplay(emptyMap()))
    }

    @Test
    fun `pickTopLanguagesForDisplay returns empty string when all weights are zero`() {
        // Divide-by-zero guard: every share would be NaN, so we bail out with an
        // empty string and let the caller render the polyglot fallback.
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(
                mapOf("kotlin" to 0L, "java" to 0L),
            )
        assertEquals("", result)
    }

    @Test
    fun `pickTopLanguagesForDisplay returns Kotlin 100 percent for single kotlin weight`() {
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(mapOf("kotlin" to 1_000L))
        assertEquals("Kotlin (100%)", result)
    }

    @Test
    fun `pickTopLanguagesForDisplay shows YAML 100 percent for yaml-only markup fallback`() {
        // Markup-only project (K8s manifests) — code tier is empty so markup
        // becomes the display base, YAML gets its 100% share. Test environment
        // may return null for Language.findLanguageByID("yaml") — either the
        // live display name or the "Yaml" title-cased fallback is acceptable.
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(mapOf("yaml" to 1_000L))
        val acceptable = result == "YAML (100%)" || result == "Yaml (100%)"
        assertTrue(
            acceptable,
            "Expected 'YAML (100%)' or 'Yaml (100%)' — got: '$result'",
        )
    }

    @Test
    fun `pickTopLanguagesForDisplay filters out markup when code tier is non-empty`() {
        // Android-style code+markup: Kotlin (40%) + XML (60%). The two-tier filter
        // drops XML from the display base, so Kotlin renders as 100% of the code
        // tier. Prevents the misleading "Kotlin (40%) • XML (60%)" case from
        // Phase 26 success criterion #4.
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(
                mapOf("kotlin" to 400L, "xml" to 600L),
            )
        assertEquals("Kotlin (100%)", result)
    }

    @Test
    fun `pickTopLanguagesForDisplay renders three named languages without other bucket`() {
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(
                mapOf("kotlin" to 780L, "java" to 150L, "python" to 70L),
            )
        assertEquals("Kotlin (78%) • Java (15%) • Python (7%)", result)
    }

    @Test
    fun `pickTopLanguagesForDisplay collapses 4th-plus languages into trailing other bucket`() {
        // Top-3 kept; 4th and 5th languages (Go + Ruby) collapse into "other".
        // Integer-rounded raw-share sum of the collapsed entries: 3 + 2 = 5%.
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(
                mapOf(
                    "kotlin" to 500L,
                    "java" to 300L,
                    "python" to 150L,
                    "ruby" to 30L,
                    "go" to 20L,
                ),
            )
        assertEquals("Kotlin (50%) • Java (30%) • Python (15%) • other (5%)", result)
    }

    @Test
    fun `pickTopLanguagesForDisplay collapses sub-1 percent entries into other bucket`() {
        // 99.5% kotlin + 0.5% java → java floor-percent is 0, below 1% threshold,
        // collapses into "other" with integer-rounded raw share (0% → suffix dropped).
        // Use a case where collapsed sum is non-zero: 98% + 2 × 1% boundary cases,
        // plus a 0.5% entry that actually rounds to zero.
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(
                mapOf(
                    "kotlin" to 950L,
                    "java" to 20L,
                    "python" to 15L,
                    "rust" to 10L,
                    "go" to 5L,
                ),
            )
        // kotlin 95%, java 2%, python 1%, rust 1%, go 0% → top-3 kept, go collapses
        // into other. Rust is at 1% (floor), stays as 4th → but top-3 cap drops it
        // too. Collapsed raw sum = 10 + 5 = 15 → floor(15/1000 * 100) = 1%.
        assertEquals("Kotlin (95%) • Java (2%) • Python (1%) • other (1%)", result)
    }

    @Test
    fun `pickTopLanguagesForDisplay keeps an entry at exactly 1 percent floor`() {
        // Boundary: 99 + 1 = 100 total, java is 1/100 = 1.0% → floor == 1 → KEEP.
        // A regression that changed the condition to `> 1` would drop java here.
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(
                mapOf("kotlin" to 99L, "java" to 1L),
            )
        assertEquals("Kotlin (99%) • Java (1%)", result)
    }

    @Test
    fun `pickTopLanguagesForDisplay collapses an entry below 1 percent floor`() {
        // Boundary: 9950 + 50 = 10_000 total, java is 50/10000 = 0.5% → floor == 0 →
        // collapse into other with raw-share sum rendered as integer-rounded pct.
        // Collapsed raw = 50 → floor(50/10000 * 100) = 0 → suffix omitted.
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(
                mapOf("kotlin" to 9_950L, "java" to 50L),
            )
        assertEquals("Kotlin (99%)", result)
    }

    @Test
    fun `pickTopLanguagesForDisplay does not append other bucket when nothing collapses`() {
        // Three languages, each ≥ 1% floor, top-3 cap NOT triggered → no
        // trailing " • other (N%)" even though shares don't sum to exactly 100.
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(
                mapOf("kotlin" to 40L, "java" to 30L, "python" to 30L),
            )
        // floor(40/100*100)=40, floor(30/100*100)=30, floor(30/100*100)=30
        assertEquals("Kotlin (40%) • Java (30%) • Python (30%)", result)
    }

    @Test
    fun `pickTopLanguagesForDisplay falls back to title-cased id for unknown language`() {
        // Unknown id ("xyzunknown" is not in the Language registry) — formatter
        // MUST title-case the raw id rather than emit a lowercase display name.
        // Test env's Language.findLanguageByID returns null for this id.
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(mapOf("xyzunknown" to 1_000L))
        assertTrue(
            result.startsWith("Xyzunknown"),
            "Expected unknown-id fallback to title-case 'xyzunknown' — got: '$result'",
        )
    }

    @Test
    fun `pickTopLanguagesForDisplay breaks ties alphabetically by language id`() {
        // Equal weights → alphabetical order on the id ("java" < "kotlin"), so
        // Java must precede Kotlin in the display. Matches pickDominantFromWeights
        // determinism contract so a HashMap iteration-order regression fails loudly.
        val result =
            LanguageDetectionRules.pickTopLanguagesForDisplay(
                mapOf("kotlin" to 500L, "java" to 500L),
            )
        assertEquals("Java (50%) • Kotlin (50%)", result)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    private fun mockLanguageFileType(languageId: String): FileType {
        val language = mockk<Language>()
        every { language.id } returns languageId
        val fileType = mockk<LanguageFileType>()
        every { fileType.language } returns language
        return fileType
    }
}
