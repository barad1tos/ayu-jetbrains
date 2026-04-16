package dev.ayuislands.accent

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import java.util.Locale
import javax.swing.Icon

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
     * Max named language entries in the Settings status-line proportions display
     * before the remainder collapses into a trailing "other (N%)" bucket. Fixed at 3
     * per Phase 26 scope; not user-configurable.
     *
     * See [pickTopLanguagesForDisplay].
     */
    const val DEFAULT_DISPLAY_MAX_ENTRIES: Int = 3

    /**
     * Separator between entries in the Settings status-line proportions display.
     * The `•` glyph matches the existing Ayu Islands visual language for inline
     * bullet separators.
     */
    private const val DISPLAY_ENTRY_SEPARATOR: String = " • "

    /**
     * Suffix label for the collapsed "other" bucket at the end of the display line.
     */
    private const val DISPLAY_OTHER_LABEL: String = "other"

    /**
     * Multiplier for converting a fractional share (0.0–1.0) to an integer percent
     * (0–100). Extracted to a constant so the rounding math in
     * [pickTopLanguagesForDisplay] stays self-documenting and detekt's MagicNumber
     * rule doesn't trip on the literal.
     */
    private const val PERCENT_SCALE: Double = 100.0

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
        val base = codeWeights.ifEmpty { allWeights }
        return pickDominantFromWeights(base, threshold, marginRatio, floor)
    }

    /**
     * Human-readable top-N summary of language proportions for Settings display.
     *
     * Applies the same code/markup two-tier base as [pickDominantFromAllWeights]:
     * when any code language is present, only code weights contribute to the display;
     * markup-only projects (K8s manifest repo, docs site) display their markup. This
     * prevents the misleading "Kotlin (40%) • XML (60%)" rendering on Android-style
     * code+markup projects.
     *
     * Returns the concatenated entry text (without any prefix — the caller prepends
     * "Detected in this project: "). Returns an empty string when [weights] is empty,
     * all-zero, or the computed base sum is non-positive — the caller is expected to
     * render the polyglot copy on an empty return.
     *
     * Rounding: integer percent via truncation (`share × 100` cast to Int). Entries
     * whose floor-percent is 0 (below 1%) collapse into a trailing "other (N%)"
     * bucket whose N is the integer-rounded sum of the collapsed raw shares. An entry
     * at exactly 1% (floor == 1) does NOT collapse. The top [maxEntries] entries are
     * kept; the remainder also collapses into "other". The "other" suffix is omitted
     * when the collapsed percent would render as 0.
     *
     * Ordering: weight descending, then alphabetical language id ascending — the same
     * determinism guarantee as [pickDominantFromWeights]. A regression that dropped
     * the alphabetical tiebreak would flake across JVM versions depending on HashMap
     * iteration order.
     *
     * Display names: [Language.findLanguageByID] with a fallback to
     * `id.replaceFirstChar { it.titlecase(Locale.ROOT) }` when the language is not
     * registered (third-party language plugin uninstalled between scan and render).
     * Live lookup matches the existing pattern at `OverridesGroupBuilder.kt:237` and
     * keeps the display in sync with dynamic plugin load/unload.
     */
    fun pickTopLanguagesForDisplay(
        weights: Map<String, Long>,
        maxEntries: Int = DEFAULT_DISPLAY_MAX_ENTRIES,
    ): String {
        val entries = pickDisplayEntries(weights, maxEntries)
        if (entries.isEmpty()) return ""
        return entries.joinToString(DISPLAY_ENTRY_SEPARATOR) { entry ->
            "${entry.label} (${entry.percent}%)"
        }
    }

    /**
     * One entry in the structured proportions display — either a named language
     * (`id` non-null) or the trailing "other" bucket that aggregates sub-1%
     * entries and everything past [DEFAULT_DISPLAY_MAX_ENTRIES].
     *
     * Used by the Settings UI to render `[icon] Kotlin 78%  [icon] Java 15%  other 7%`
     * — each entry becomes one `JBLabel` with the icon resolved from [iconForLanguageId].
     * The string-projection [pickTopLanguagesForDisplay] is implemented on top of this
     * same data, so UI and text callers never disagree on what's displayed.
     */
    data class DisplayEntry(
        /** Lowercase AYU language id, or `null` for the "other" bucket. */
        val id: String?,
        /** Human-readable display name ("Kotlin", "JavaScript", "other"). */
        val label: String,
        /** Integer percentage share; always 1–100 for named entries, 1–99 for "other". */
        val percent: Int,
    )

    /**
     * Structured proportion breakdown — a list of top languages (up to
     * [maxEntries]) followed by an optional "other" bucket that aggregates
     * everything below 1% and everything past the top-N cutoff.
     *
     * Returns empty list when:
     *  - [weights] is empty
     *  - total weight is non-positive (defensive)
     *  - no entry survives the 1% threshold after sorting
     *
     * Invariants:
     *  - Entries sorted by weight descending; ties broken alphabetically by id
     *    (same determinism guarantee as [pickDominantFromWeights])
     *  - Two-tier filter: code languages take priority; markup-only projects
     *    fall back to the full weight map
     *  - "Other" bucket only appears when `collapsedPct >= 1`
     *  - All percentages are integer — `(raw/total * 100).toInt()` rounds toward zero
     *
     * Used directly by the Settings UI to produce (icon, label, percent) triples
     * for the proportions status row; also underlies the string-formatted
     * [pickTopLanguagesForDisplay] so both share one source of truth.
     */
    fun pickDisplayEntries(
        weights: Map<String, Long>,
        maxEntries: Int = DEFAULT_DISPLAY_MAX_ENTRIES,
    ): List<DisplayEntry> {
        if (weights.isEmpty()) return emptyList()
        val codeWeights = weights.filterKeys { it !in MARKUP_IDS }
        val base = codeWeights.ifEmpty { weights }
        val total = base.values.sum()
        if (total <= 0L) return emptyList()
        val sorted =
            base.entries.sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key },
            )
        val named = mutableListOf<DisplayEntry>()
        var collapsedRaw = 0L
        for ((index, entry) in sorted.withIndex()) {
            val pct = (entry.value.toDouble() / total.toDouble() * PERCENT_SCALE).toInt()
            if (index < maxEntries && pct >= 1) {
                named += DisplayEntry(id = entry.key, label = displayNameFor(entry.key), percent = pct)
            } else {
                collapsedRaw += entry.value
            }
        }
        if (named.isEmpty()) return emptyList()
        val collapsedPct = (collapsedRaw.toDouble() / total.toDouble() * PERCENT_SCALE).toInt()
        if (collapsedPct >= 1) {
            named += DisplayEntry(id = null, label = DISPLAY_OTHER_LABEL, percent = collapsedPct)
        }
        return named
    }

    /**
     * Resolve a lowercase AYU language id to the IntelliJ platform icon for
     * that language's [LanguageFileType]. Returns null when the id is blank,
     * the language isn't registered, the language has no associated file type,
     * or the file type isn't a [LanguageFileType] (shouldn't happen for code
     * languages but guarded for third-party plugins that register custom file
     * types).
     *
     * Used by the Settings proportions row to pick up the same icon the user
     * already sees beside source files in the Project View — zero new
     * dependencies, theme-aware, always available when the language plugin is
     * loaded.
     */
    fun iconForLanguageId(id: String): Icon? {
        if (id.isBlank()) return null
        val language = Language.findLanguageByID(id) ?: return null
        val fileType = language.associatedFileType ?: return null
        return (fileType as? LanguageFileType)?.icon
    }

    /**
     * Resolve a lowercase AYU language id to a human-readable display name.
     *
     * Uses [Language.findLanguageByID] for registered languages (returns
     * "JavaScript", "Kotlin", "Shell Script" etc. in the exact casing the
     * platform ships). Falls back to a title-cased form of the raw id when the
     * language is not registered in the current IDE — ensures a never-emit-lowercase
     * invariant for the UI.
     */
    private fun displayNameFor(id: String): String =
        Language.findLanguageByID(id)?.displayName
            ?: id.replaceFirstChar { it.titlecase(Locale.ROOT) }
}
