package dev.ayuislands.accent

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import java.util.Locale

/**
 * Pure, side-effect-free helpers for the proportional language detector.
 *
 * Split out from [ProjectLanguageDetector] so the threshold math and the
 * "is this file a code language?" filter can be unit-tested without mocking
 * Project / ProjectFileIndex / ReadAction. Every exported member is deterministic
 * and depends on no IntelliJ platform singletons.
 */
internal object LanguageDetectionRules {
    /**
     * Language ids (lowercased) that represent markup / config / data rather than
     * a project's "primary code language". Used by
     * [pickDominantFromAllWeights] to filter the dominance base — but only when
     * code-language weight is present at all. A markup-only project (K8s manifests,
     * docs site) still surfaces its markup as dominant so a user who mapped
     * "yaml → blue" gets the accent they asked for.
     *
     * All lookups lowercased with [Locale.ROOT]; IntelliJ's Language.id casing
     * varies ("JAVA", "kotlin", "JavaScript", "C#") and [resolveLanguageId]
     * normalizes before callers use the value.
     *
     * Intentionally NOT in the blacklist:
     *   - `sql`  — DB-tooling projects have SQL as the primary surface area
     *   - `graphql` — schema-first Node/Python services use GraphQL as code
     *   - `shell script` / `bash` — ops repos are legitimately shell-dominant
     *   - `dockerfile` / `makefile` — DevOps / C projects with these as primary
     *   - `groovy` — Gradle/Grails app code
     */
    val MARKUP_IDS: Set<String> =
        setOf(
            // Pure text / unclassified
            "text",
            "plaintext",
            "plain_text",
            // Data formats
            "json",
            "json5",
            "jsonl",
            "yaml",
            "yml",
            "xml",
            "xhtml",
            "csv",
            "tsv",
            "log",
            // Styling / markup
            "html",
            "css",
            "scss",
            "sass",
            "less",
            "postcss",
            "svg",
            "mermaid",
            // Docs
            "markdown",
            "rst",
            "asciidoc",
            "textmate",
            // Config
            "properties",
            "toml",
            "ini",
            "dotenv",
            "editorconfig",
            "gitignore",
            // Scratch / adjunct
            "http request",
            "regexp",
            "regular expression",
        )

    /**
     * Minimum share of counted weight the top language must clear to be called
     * "dominant". 60% is tuned to:
     *  - accept 70% / 30% splits (single clear winner → apply override)
     *  - reject 55% / 45% splits (genuinely mixed → fall through to global)
     *
     * Exposed as a constant so tests can document the boundary and a future
     * settings toggle can override it per-project.
     */
    const val DEFAULT_DOMINANCE_THRESHOLD: Double = 0.60

    /**
     * Per-file cap on weight contribution. Stops a single 50-MB generated Kotlin
     * file (e.g. a protobuf-generated model) from swallowing the entire proportion
     * and mis-labelling a Python-with-one-big-Kotlin-binding project as Kotlin.
     *
     * 100 KB is roughly 2–3k lines of human-written source — plenty of signal per
     * file without giving any single file disproportionate sway.
     */
    const val MAX_FILE_WEIGHT_BYTES: Long = 100_000L

    /**
     * Hard cap on files visited by a single scan. Keeps monster monorepos
     * (linux-kernel-sized) from blocking the EDT for seconds on first accent resolve.
     * At the cap, whatever sample we have is assumed representative of the rest.
     */
    const val MAX_FILES_SCANNED: Int = 10_000

    /**
     * "Leading plurality" ratio: when no language clears [DEFAULT_DOMINANCE_THRESHOLD],
     * the top can still win if it's this many times larger than #2. Handles the
     * React-style 55 / 30 / 15 TypeScript / JavaScript / other case where no
     * single language hits 60% but TypeScript is obviously the primary.
     */
    const val DOMINANCE_MARGIN_RATIO: Double = 1.5

    /**
     * Minimum share the margin-rule winner must still hold. Without this a 30 / 15 / 10
     * three-way split would award the 30% language by margin alone — which is a
     * confident answer to a question that has no clear answer.
     */
    const val DOMINANCE_FLOOR: Double = 0.40

    /**
     * Minimum share a legacy-SDK hint must have in the scan's code weights to serve
     * as a tie-breaker on polyglot scans. Without a floor the SDK heuristic would
     * rubber-stamp a wrong answer on a 50/50 Java/Kotlin project where the SDK says
     * "kotlin" but the scan has no strong signal either way.
     */
    const val TIE_BREAK_MIN_SHARE: Double = 0.20

    /**
     * Path segments whose presence in a file's canonical path always disqualifies
     * the file from dominance math — dependency vendoring, build outputs, IDE /
     * VCS metadata, generated-code roots. Matched exactly (no wildcards, no
     * substring-of-substring) against each `/`- or `\\`-separated segment.
     *
     * Rationale: `ProjectFileIndex.iterateContent` respects IDE-configured excludes,
     * but real-world projects routinely ship without `.gradle/` / `node_modules/` /
     * `.venv/` marked as excluded. A Python project with 200 hand-written `.py`
     * files plus a committed `.venv` of 15 000 library `.py` files would otherwise
     * be "dominant Python from the venv" instead of "dominant Python from user code"
     * — same verdict, yes, but the analogous Go case (50 user `.go` + 4000 vendored
     * `.go`) silently erases a genuine 50/50 Go+Python polyglot signal.
     *
     * Entries that overlap with legitimate user folder names (`build`, `target`,
     * `out`, `bin`, `obj`) are a deliberate trade-off — Maven/Rust/Gradle/.NET
     * conventions are far more common than users naming their source folder
     * "target". Accept the rare false positive to keep the common case correct.
     */
    val EXCLUDED_PATH_SEGMENTS: Set<String> =
        setOf(
            // JS / TS ecosystem
            "node_modules",
            "bower_components",
            ".yarn",
            ".pnp",
            ".pnpm-store",
            ".next",
            ".nuxt",
            ".svelte-kit",
            ".parcel-cache",
            ".cache",
            ".turbo",
            ".astro",
            // Python ecosystem
            ".venv",
            "venv",
            "env",
            "__pycache__",
            "site-packages",
            ".tox",
            ".mypy_cache",
            ".pytest_cache",
            ".ruff_cache",
            // Go / Rust / Ruby / PHP vendoring
            "vendor",
            // JVM / Gradle / Maven / SBT / .NET build outputs
            "target",
            ".gradle",
            "build",
            "out",
            "bin",
            "obj",
            // IDE / VCS metadata
            ".idea",
            ".vscode",
            ".fleet",
            ".git",
            ".hg",
            ".svn",
            // Code generation roots
            "generated",
            "generated-sources",
            "generated-src",
            "gensrc",
            "gen",
            // Coverage / reports
            "coverage",
            "htmlcov",
            "dist",
        )

    /**
     * True when [path] contains any [EXCLUDED_PATH_SEGMENTS] segment. Splits on
     * both Unix (`/`) and Windows (`\\`) separators so the same rules work on
     * either platform and on paths carried across the wire by remote-dev / WSL /
     * Docker Dev Container hosts (where path format can legitimately be either).
     */
    fun isExcludedPath(path: String): Boolean {
        if (path.isEmpty()) return false
        var start = 0
        val len = path.length
        while (start < len) {
            var end = start
            while (end < len && path[end] != '/' && path[end] != '\\') {
                end++
            }
            if (end > start) {
                val segment = path.substring(start, end)
                if (segment in EXCLUDED_PATH_SEGMENTS) return true
            }
            start = end + 1
        }
        return false
    }

    /**
     * Map a [FileType] to its canonical AYU language id — the same lowercase
     * string the language-override dialog stores under. No markup filter is
     * applied here; filtering happens higher up in [pickDominantFromAllWeights]
     * so a pure-markup project can still surface its markup as dominant.
     *
     * Returns null only when the file should never count toward dominance:
     *  - null file type (deleted file, VFS race)
     *  - not a [LanguageFileType] (plain binary, archive, image)
     *  - blank language id (pathological plugin)
     *
     * Lowercase with [Locale.ROOT] is deliberate: default locale on Turkish
     * systems turns `"I".lowercase()` into a dotless `ı`, desyncing the detector
     * result from the UI-stored id (which also uses the default locale, but the
     * override dialog is in the same JVM so both agree — the danger is inside a
     * single machine comparing cached vs computed ids after locale changes).
     */
    fun resolveLanguageId(fileType: FileType?): String? {
        if (fileType == null) return null
        val language = (fileType as? LanguageFileType)?.language ?: return null
        val id = language.id.lowercase(Locale.ROOT)
        if (id.isBlank()) return null
        return id
    }

    /**
     * Pure threshold math: given pre-computed per-id weight totals, return the
     * top id if either (a) it clears [threshold] — clear majority — OR (b) it
     * holds at least [floor] AND is at least [marginRatio] × the second-place
     * weight — a "leading plurality" tiebreak for projects like React
     * (55% TSX / 30% JSX / 15% CSS) where no single language hits 60% but one
     * is obviously the primary.
     *
     * Does NOT apply the markup filter here; callers that want "code-first with
     * markup fallback" compose via [pickDominantFromAllWeights].
     *
     * Determinism: when two languages have identical weight, alphabetical order
     * on the language id breaks the tie. Raw [Map.maxByOrNull] would use
     * HashMap iteration order, which is insertion-order for [LinkedHashMap] but
     * undefined for hash-based variants — a test asserting "equal weights pick X"
     * would otherwise pass or fail based on insertion-order luck.
     *
     * Returns null when:
     *  - [weights] is empty or all-zero
     *  - neither the clear-majority nor the leading-plurality rule fires
     *    (genuinely polyglot — caller should fall through to a higher-level
     *    tiebreak or to the global accent).
     */
    fun pickDominantFromWeights(
        weights: Map<String, Long>,
        threshold: Double = DEFAULT_DOMINANCE_THRESHOLD,
        marginRatio: Double = DOMINANCE_MARGIN_RATIO,
        floor: Double = DOMINANCE_FLOOR,
    ): String? {
        if (weights.isEmpty()) return null
        val total = weights.values.sum()
        if (total <= 0L) return null
        val sorted =
            weights.entries.sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key },
            )
        val top = sorted.first()
        val topShare = top.value.toDouble() / total.toDouble()
        if (topShare >= threshold) return top.key
        // Leading-plurality fallback: top still meaningful (>= floor) AND clearly
        // ahead of the next contender (>= marginRatio × second).
        val second = sorted.getOrNull(1)?.value ?: 0L
        val marginThreshold = (marginRatio * second.toDouble()).toLong()
        return if (topShare >= floor && top.value >= marginThreshold) top.key else null
    }

    /**
     * Two-tier dominance: prefer code languages as the dominance base; fall back
     * to the full (code + markup) set only when the project has no code files at all.
     *
     * Rationale:
     *  - Android project (Kotlin source + xml under resources) → xml filtered out,
     *    Kotlin dominates by its proportion of the CODE weight. Correct.
     *  - K8s-manifests-only repo (nothing but yaml) → code base is empty, fall
     *    back to all weights, yaml wins. Correct — user who mapped "yaml" gets
     *    their accent.
     *  - Docs-only repo (only markdown) → same pattern, markdown wins.
     *  - Polyglot Java/Kotlin 50/50 (plus lots of xml) → code base is Java+Kotlin,
     *    neither clears 60%, return null (global accent). Correct.
     */
    fun pickDominantFromAllWeights(
        allWeights: Map<String, Long>,
        threshold: Double = DEFAULT_DOMINANCE_THRESHOLD,
        marginRatio: Double = DOMINANCE_MARGIN_RATIO,
        floor: Double = DOMINANCE_FLOOR,
    ): String? {
        if (allWeights.isEmpty()) return null
        val codeWeights = allWeights.filterKeys { it !in MARKUP_IDS }
        val base = if (codeWeights.isNotEmpty()) codeWeights else allWeights
        return pickDominantFromWeights(base, threshold, marginRatio, floor)
    }
}
