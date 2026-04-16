package dev.ayuislands.accent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/**
 * Detector for the dominant language of a project, used only when the language-accent
 * override map is non-empty (the [AccentResolver] gates this).
 *
 * Detection strategy (most-authoritative-first):
 *
 *  1. **Content scan** — [ProjectLanguageScanner] walks content roots, tallies per-id
 *     bytes (cap-limited per file, cap-limited by file count, vendored / generated
 *     / build-output dirs filtered by [LanguageDetectionRules.EXCLUDED_PATH_SEGMENTS]),
 *     and [LanguageDetectionRules.pickDominantFromAllWeights] returns the language
 *     whose share clears the primary threshold OR passes the leading-plurality rule.
 *  2. **Legacy SDK/module heuristic as polyglot tiebreaker** — only consulted when the
 *     scan produced weights but no winner, AND the legacy hint has ≥20% share in the
 *     scan's code weights. Protects against the v2.5 → v2.6 upgrade regression where
 *     a 50/50 Java/Kotlin JVM project would silently drop its "kotlin" override.
 *  3. **Legacy SDK/module heuristic as fallback** — used only when the scan found zero
 *     recognized source files: brand-new project, docs-only-after-filtering, or
 *     pre-indexing state.
 *
 * Concurrency: `dominant()` may be called from EDT (settings UI, focus-swap listener).
 * On EDT, first-call detection on a large monorepo is a ~300–500 ms freeze. To avoid
 * that, an EDT invocation with an empty cache kicks off a deduplicated background scan
 * via [ProjectLanguageScanAsync] and returns null immediately; the caller falls through
 * to the global accent. When the BG scan completes, if the detected id has an active
 * language-accent override, the detector re-applies the accent on EDT so the UI picks
 * up the winner without waiting for the next focus swap.
 *
 * Cache correctness: a detection whose scan threw is NOT cached — the next call
 * retries. A scan that ran cleanly but produced no winner (after both the proportional
 * rule AND the SDK tiebreak fail) IS cached as null, because the answer is definitive.
 * Legacy SDK/module lookups that throw are NOT cached; without this guard a single
 * transient failure would permanently poison the cache for that project.
 */
object ProjectLanguageDetector {
    private val LOG = logger<ProjectLanguageDetector>()

    // ConcurrentHashMap rejects null values, so we store an empty-string sentinel
    // whenever detection definitively returns null. AYU language ids are always
    // non-empty ("kotlin", "python", ...) so the sentinel cannot collide with a real id.
    private const val NULL_SENTINEL = ""
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Dominant language id for [project], cached per canonical path.
     *
     * On EDT with a cold cache, returns null and schedules a background scan; the
     * next invocation (or the post-scan UI refresh) serves the cached answer.
     * Off-EDT callers (StartupActivity's suspend coroutine) scan synchronously so
     * the first accent resolve after project open already has a warm cache.
     */
    fun dominant(project: Project): String? {
        val key = AccentResolver.projectKey(project) ?: return null
        cache[key]?.let { return it.takeIf { hit -> hit.isNotEmpty() } }

        if (isOnEdt()) {
            scheduleBackgroundDetection(project, key)
            return null
        }
        return detectAndCache(project, key)
    }

    /**
     * Clear the cached detection for [project]; call from project-close hooks
     * so a re-opened project can be re-analyzed and from [ModuleRootListener]
     * so content-root changes trigger a fresh scan.
     */
    fun invalidate(project: Project) {
        AccentResolver.projectKey(project)?.let { cache.remove(it) }
    }

    /** Drop the entire cache — useful for test isolation. */
    fun clear() {
        cache.clear()
    }

    /**
     * Result of a single detection attempt. `cacheable = false` signals a transient
     * failure (the underlying IntelliJ API threw) — the caller must NOT persist this
     * result, so the next invocation retries instead of serving a poisoned cache entry.
     */
    private data class DetectionResult(
        val languageId: String?,
        val cacheable: Boolean,
    )

    /**
     * EDT-safe detection wrapper. Off-EDT callers invoke this directly;
     * [dominant] routes EDT callers through [scheduleBackgroundDetection] instead.
     */
    private fun detectAndCache(
        project: Project,
        key: String,
    ): String? {
        val detection = detectInternal(project)
        if (detection.cacheable) {
            cache[key] = detection.languageId ?: NULL_SENTINEL
        }
        return detection.languageId
    }

    /**
     * Kick the scan onto the shared IDE executor so the EDT is not blocked. The
     * scheduler's dedup gate short-circuits duplicate calls for the same key, so
     * multiple simultaneous `dominant()` invocations from different UI components
     * coalesce into a single scan.
     */
    private fun scheduleBackgroundDetection(
        project: Project,
        key: String,
    ) {
        ProjectLanguageScanAsync.schedule(key) {
            if (project.isDisposed) return@schedule
            val detected = detectAndCache(project, key) ?: return@schedule
            tryRefreshAccentForDetected(project, detected)
        }
    }

    /**
     * After a background scan settles on an id, re-apply the accent if the user
     * has an override configured for it — otherwise the UI would stay on the
     * global accent until the next focus swap / IDE restart, even though
     * detection just proved the override should apply.
     */
    private fun tryRefreshAccentForDetected(
        project: Project,
        detectedId: String,
    ) {
        val mappings = AccentMappingsSettings.getInstance().state
        if (detectedId !in mappings.languageAccents) return
        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            val variant = AyuVariant.detect() ?: return@invokeLater
            val hex = AccentResolver.resolve(project, variant)
            AccentApplicator.apply(hex)
            ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
        }
    }

    private fun isOnEdt(): Boolean = ApplicationManager.getApplication()?.isDispatchThread == true

    private fun detectInternal(project: Project): DetectionResult {
        val weights = ProjectLanguageScanner.scan(project)
        if (weights == null) {
            // Scan can't give an authoritative answer right now (dumb mode,
            // disposal race, ReadAction failure). Don't cache — the next call
            // retries so detection catches up once the IDE stabilizes.
            return DetectionResult(languageId = null, cacheable = false)
        }
        if (weights.isNotEmpty()) {
            val scanWinner = LanguageDetectionRules.pickDominantFromAllWeights(weights)
            if (scanWinner != null) {
                return DetectionResult(languageId = scanWinner, cacheable = true)
            }
            // Scan found weights but no clear or plurality winner. Consult the
            // legacy heuristic as a tiebreaker — but only accept its answer when
            // that language is already represented in the scan's code weights at
            // TIE_BREAK_MIN_SHARE. Guards against the SDK confidently reporting
            // a language that the scan didn't even find.
            resolveTiebreakFromLegacy(project, weights)?.let {
                return DetectionResult(languageId = it, cacheable = true)
            }
            return DetectionResult(languageId = null, cacheable = true)
        }
        // Empty scan: brand-new project / everything-filtered-as-markup / newly
        // checked out. Fall back to the SDK + module heuristic; its result is
        // cached the same way the pre-scan implementation did.
        return legacySdkModuleDetection(project)
    }

    /**
     * When the proportional scan is polyglot, fall back to the legacy SDK / module
     * heuristic — but only if the language it names has a foothold (≥
     * [LanguageDetectionRules.TIE_BREAK_MIN_SHARE]) in the scan's code weights.
     * Returns null when the hint doesn't meet that bar.
     */
    private fun resolveTiebreakFromLegacy(
        project: Project,
        weights: Map<String, Long>,
    ): String? {
        val hint = legacySdkModuleDetection(project).languageId ?: return null
        val codeWeights = weights.filterKeys { it !in LanguageDetectionRules.MARKUP_IDS }
        val base = if (codeWeights.isNotEmpty()) codeWeights else weights
        val total = base.values.sum()
        if (total <= 0L) return null
        val hintWeight = base[hint] ?: 0L
        val share = hintWeight.toDouble() / total.toDouble()
        return if (share >= LanguageDetectionRules.TIE_BREAK_MIN_SHARE) hint else null
    }

    private fun legacySdkModuleDetection(project: Project): DetectionResult {
        // Both platform lookups wrap via runCatchingPreservingCancellation — `dominant`
        // is reachable from AyuIslandsStartupActivity.execute's coroutine body via
        // AccentResolver.findOverride, and plain `runCatching` would swallow
        // CancellationException and keep a cancelled coroutine alive.
        val sdkResult =
            runCatchingPreservingCancellation {
                ProjectRootManager
                    .getInstance(project)
                    .projectSdk
                    ?.sdkType
                    ?.name
            }
        if (sdkResult.isFailure) {
            LOG.warn(
                "SDK lookup failed during language detection; will retry on next call instead of caching null",
                sdkResult.exceptionOrNull(),
            )
            return DetectionResult(languageId = null, cacheable = false)
        }
        sdkTypeToLanguageId(sdkResult.getOrNull())?.let {
            return DetectionResult(languageId = it, cacheable = true)
        }

        val moduleResult =
            runCatchingPreservingCancellation {
                ModuleManager.getInstance(project).modules.map { it.name }
            }
        if (moduleResult.isFailure) {
            LOG.warn(
                "Module lookup failed during language detection; will retry on next call instead of caching null",
                moduleResult.exceptionOrNull(),
            )
            return DetectionResult(languageId = null, cacheable = false)
        }
        val moduleNames = moduleResult.getOrDefault(emptyList())
        for (moduleName in moduleNames) {
            moduleNameToLanguageId(moduleName.lowercase(Locale.ROOT))?.let {
                return DetectionResult(languageId = it, cacheable = true)
            }
        }
        // Definitive null — no SDK match, no module match, no scan weights. Safe to cache.
        return DetectionResult(languageId = null, cacheable = true)
    }

    private fun sdkTypeToLanguageId(sdkTypeName: String?): String? {
        if (sdkTypeName == null) return null
        // Locale.ROOT so Turkish locale's dotless-I rule doesn't desync lowered
        // SDK names from the ASCII substring checks below. "JDK" under Turkish
        // default locale would otherwise lowercase to "jdk" — safe here, but
        // a future name containing uppercase I would not — lock it in.
        val lowered = sdkTypeName.lowercase(Locale.ROOT)
        return sdkNameLookupPrimary(lowered) ?: sdkNameLookupSecondary(lowered)
    }

    private fun sdkNameLookupPrimary(lowered: String): String? =
        when {
            lowered.contains("kotlin") -> "kotlin"
            lowered.contains("python") -> "python"
            lowered.contains("node") -> "javascript"
            lowered.contains("typescript") -> "typescript"
            lowered.contains("rust") -> "rust"
            lowered.contains("go") && !lowered.contains("google") -> "go"
            else -> null
        }

    private fun sdkNameLookupSecondary(lowered: String): String? =
        when {
            lowered.contains("ruby") -> "ruby"
            lowered.contains("php") -> "php"
            lowered.contains("dart") -> "dart"
            lowered.contains("scala") -> "scala"
            lowered.contains("javasdk") || lowered.contains("jdk") -> "java"
            else -> null
        }

    private fun moduleNameToLanguageId(lowered: String): String? =
        when {
            lowered.contains("kotlin") -> "kotlin"
            lowered.contains("python") -> "python"
            lowered.contains("scala") -> "scala"
            lowered.contains("android") -> "kotlin"
            lowered.contains("flutter") || lowered.contains("dart") -> "dart"
            else -> null
        }
}
