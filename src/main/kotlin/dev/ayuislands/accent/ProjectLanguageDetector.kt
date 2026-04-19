package dev.ayuislands.accent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
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
     * Parallel per-language byte-weight cache, written alongside [cache] inside
     * [detectAndCache] when the scan produced non-empty weights. Keyed by the same
     * canonical project path as [cache] (see [AccentResolver.projectKey]) so a
     * single [invalidate] call evicts both and the two caches never drift.
     *
     * Absence of a key means "no warm weights" — callers of [proportions] treat the
     * missing key as the polyglot / no-winner signal and render the fallback copy.
     * The legacy SDK/module fallback path ([legacySdkModuleDetection]) produces no
     * weights and deliberately does NOT populate this cache — proportions() for a
     * legacy-detected project stays null so the Settings display shows the polyglot
     * copy rather than a misleading "(100%)" single-language breakdown derived from
     * an empty scan.
     *
     * In-memory only (like [cache]); re-warms on the next successful scan. Not
     * persisted across IDE restarts — the project-open startup activity re-warms
     * via the next `dominant()` call it issues.
     */
    private val weightsCache = ConcurrentHashMap<String, Map<String, Long>>()

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
     * Per-language byte-weight map for [project], read strictly from the warm cache.
     *
     * Returns null when the cache is cold (never scanned), when the scan produced no
     * meaningful weights (legacy SDK fallback path, empty-scan path — caller renders
     * the polyglot copy), when the scan failed due to dumb mode or disposal race,
     * or when the project's canonical path cannot be resolved.
     *
     * Never triggers a scan or schedules background work. The caller is responsible
     * for rendering a fallback (polyglot copy) on null. Cache is warmed via
     * [dominant] (called from [dev.ayuislands.AyuIslandsStartupActivity] and the
     * focus-swap path in [AccentResolver]) and invalidated atomically with the
     * dominant-id cache via [invalidate].
     *
     * Phase 26 contract: this is a read-only projection of existing detector state.
     * Phase 29 supplies a manual rescan trigger; Phase 31 may extend this API with
     * a scan-status discriminant. Neither should weaken the "no scan on miss"
     * invariant established here.
     */
    fun proportions(project: Project): Map<String, Long>? {
        val key = AccentResolver.projectKey(project) ?: return null
        // Cross-cache coherence guard: `invalidate()` clears both `cache` and
        // `weightsCache`, and `detectAndCache` writes them in a specific order
        // (`weightsCache` first, `cache` last). If a concurrent `invalidate()`
        // interleaves between those two writes the `weightsCache` entry would
        // already be gone, but reading the raw `weightsCache[key]` here would
        // let a stale entry slip through in the opposite race window (write
        // happened, then invalidate ran between the two writes, leaving the
        // dominant-id cache empty and weightsCache populated with stale data).
        // Gate the weights read on the dominant-id cache being present — if the
        // evictor already ran, serve null so the UI re-reads after the next
        // `dominant()` completes instead of painting a breakdown for a layout
        // the rest of the detector has already forgotten.
        if (cache[key] == null) return null
        return weightsCache[key]
    }

    /**
     * Clear the cached detection for [project]. Called from the project-close
     * listener ([ProjectLanguageCacheInvalidator]) so a re-opened project can
     * be re-analyzed, and from the `ModuleRootListener` subscription registered
     * in [dev.ayuislands.AyuIslandsStartupActivity] so mid-session content-root
     * changes (gradle sync, module add/remove) trigger a fresh scan.
     *
     * Evicts BOTH [cache] and [weightsCache] under the same key so `dominant()` and
     * `proportions()` never drift — a stale weights entry served after
     * `dominant()` re-scanned would show the user a proportion breakdown for the
     * pre-invalidate layout.
     */
    fun invalidate(project: Project) {
        val key = AccentResolver.projectKey(project) ?: return
        cache.remove(key)
        weightsCache.remove(key)
    }

    /**
     * Manual entry point that forces a fresh scan: clears the cache for [project]
     * and kicks [scheduleBackgroundDetection]. Bound to the Tools-menu
     * `RescanLanguageAction` and to the inline "Rescan" affordance in the
     * Settings → Accent → Overrides proportions row; both reuse a single public
     * surface so the scheduler's dedup gate (keyed by canonical path inside
     * [ProjectLanguageScanAsync]) coalesces rapid-fire spam from either source
     * into one actual scan.
     *
     * Side effects:
     *  - Evicts both dominant-id and weights caches for [project].
     *  - Schedules a background scan on the shared IDE pool (no-op in dumb
     *    mode — the user must re-click after indexing finishes, or wait for
     *    the next `ModuleRootListener.rootsChanged` to re-trigger detection
     *    on a Gradle sync / module change).
     *  - On scan completion, [ProjectLanguageDetectionListener.scanCompleted]
     *    fires on EDT via [publishScanCompleted], so UI subscribers (the
     *    Settings proportions row, the action's balloon) receive the new
     *    state without polling.
     *  - When [project] has no canonical path (disposal race, default
     *    project), publishes `scanCompleted(null)` immediately so one-shot
     *    subscribers (notably the [dev.ayuislands.actions.RescanLanguageAction]
     *    balloon) fire and disconnect instead of silently hanging on the
     *    MessageBus until project close.
     *
     * Safe to call from EDT: invalidate is a two-`ConcurrentHashMap.remove`
     * pair, and `scheduleBackgroundDetection` immediately returns after
     * enqueuing the task on `AppExecutorUtil.getAppExecutorService()`.
     */
    fun rescan(project: Project) {
        val key =
            AccentResolver.projectKey(project) ?: run {
                // No canonical path: bail out of the cache+schedule work, but
                // still publish a null scanCompleted so subscribers (especially
                // the one-shot action balloon) fire and release their
                // MessageBus connection instead of silently leaking until
                // project close. `AccentResolver.projectKey` already logged the
                // reason it returned null (base-path throw, canonicalization
                // failure) — this branch is about the *user-facing* symptom.
                LOG.info("rescan: projectKey unavailable; publishing Unavailable so subscribers unblock")
                publishScanCompleted(project, ScanOutcome.Unavailable)
                return
            }
        cache.remove(key)
        weightsCache.remove(key)
        scheduleBackgroundDetection(project, key)
    }

    /** Drop the entire cache — useful for test isolation. Empties both maps. */
    fun clear() {
        cache.clear()
        weightsCache.clear()
    }

    /**
     * Result of a single detection attempt. `cacheable = false` signals a transient
     * failure (the underlying IntelliJ API threw) — the caller must NOT persist this
     * result, so the next invocation retries instead of serving a poisoned cache entry.
     *
     * [weights] carries the raw per-language byte map produced by the scan path; it
     * is null on the legacy SDK/module fallback path (no scan produced weights) and
     * also when the scan ran but produced no winner and no margin-plurality tiebreak
     * (the polyglot null verdict). [detectAndCache] only writes [weightsCache] when
     * [weights] is present and non-empty — see `weightsCache` KDoc for rationale.
     */
    private data class DetectionResult(
        val languageId: String?,
        val cacheable: Boolean,
        val weights: Map<String, Long>? = null,
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
            // Write order is load-bearing: `weightsCache` first, `cache`
            // (dominant-id) second, paired with the `cache[key] == null` guard
            // in `proportions()`. An interleaved `invalidate()` that lands
            // between the two writes is observed as "no cache entry yet" by
            // both readers instead of "proportions populated, dominant gone" —
            // the latter would render a stale breakdown for a layout the
            // detector has already forgotten.
            if (!detection.weights.isNullOrEmpty()) {
                weightsCache[key] = detection.weights
            }
            cache[key] = detection.languageId ?: NULL_SENTINEL
        } else {
            // Forensic breadcrumb: a non-cacheable result means the scan hit
            // dumb mode, a disposed project, or the scanner threw. The caller
            // sees the same `null` the polyglot/legacy paths emit, so the
            // Settings row silently renders the polyglot copy — without this
            // log there is no trace for "proportions never show up" reports.
            // DEBUG severity keeps the normal indexing-warmup path quiet
            // (every fresh IDE window hits dumb mode once) while still leaving
            // a paper trail in idea.log.
            LOG.debug("Scan for $key returned non-cacheable result; caller will re-scan on next call")
        }
        return detection.languageId
    }

    /**
     * Kick the scan onto the shared IDE executor so the EDT is not blocked. The
     * scheduler's dedup gate short-circuits duplicate calls for the same key, so
     * multiple simultaneous `dominant()` invocations from different UI components
     * coalesce into a single scan.
     *
     * Skips entirely in dumb mode so a long-running gradle sync or initial index
     * doesn't pile up doomed tasks on the shared executor — every EDT-path
     * `dominant()` call during indexing would otherwise enqueue a scan that the
     * scanner's own dumb-mode check immediately bails out of. The next post-index
     * resolve (e.g., `ModuleRootListener.rootsChanged` fires an invalidate, or
     * focus swap calls `dominant` again) re-schedules cleanly.
     */
    private fun scheduleBackgroundDetection(
        project: Project,
        key: String,
    ) {
        if (DumbService.isDumb(project)) {
            // Breadcrumb for the "user clicked Rescan, nothing happened"
            // triage path: the dumb-mode gate deliberately drops the task,
            // and without this log a Tools-menu click during indexing
            // produces no balloon and no log trace.
            LOG.debug("scheduleBackgroundDetection skipped for $key: DumbService.isDumb == true")
            return
        }
        ProjectLanguageScanAsync.schedule(key) {
            if (project.isDisposed) {
                LOG.debug("scheduleBackgroundDetection task aborted for $key: project disposed before scan body ran")
                return@schedule
            }
            val detected = detectAndCache(project, key)
            // Classify into a typed ScanOutcome so subscribers can
            // distinguish polyglot (definitive no-winner) from
            // Unavailable (transient — scanner threw, dumb-mode race).
            // Discriminator: `detectAndCache` writes `cache[key]` only
            // when the underlying DetectionResult is cacheable. After
            // the call:
            //   - detected != null → cache holds the id → Detected
            //   - detected == null && cache[key] != null → cache holds
            //     NULL_SENTINEL → definitive polyglot verdict
            //   - detected == null && cache[key] == null → non-cacheable
            //     transient failure → Unavailable
            val outcome: ScanOutcome =
                when {
                    detected != null -> ScanOutcome.Detected(detected)
                    cache[key] != null -> ScanOutcome.Polyglot
                    else -> ScanOutcome.Unavailable
                }
            if (detected != null) {
                tryRefreshAccentForDetected(project, detected)
            }
            publishScanCompleted(project, outcome)
        }
    }

    /**
     * Dispatch [ProjectLanguageDetectionListener.scanCompleted] on EDT so
     * subscribers can touch Swing directly. Separate from the scan task body
     * so the scheduler's executor thread never reaches the MessageBus
     * `syncPublisher` machinery — syncPublisher's invocation handler can run
     * arbitrary subscriber code, and keeping that off the scan pool prevents
     * a misbehaving subscriber from stalling the shared executor. Double
     * dispose-guard (before and after invokeLater) because project disposal
     * can race with a late-arriving scan completion.
     */
    private fun publishScanCompleted(
        project: Project,
        outcome: ScanOutcome,
    ) {
        if (project.isDisposed) {
            LOG.debug("publishScanCompleted dropped before invokeLater: project already disposed")
            return
        }
        SwingUtilities.invokeLater {
            if (project.isDisposed) {
                LOG.debug("publishScanCompleted dropped inside invokeLater: project disposed during EDT hop")
                return@invokeLater
            }
            runCatchingPreservingCancellation {
                project.messageBus
                    .syncPublisher(ProjectLanguageDetectionListener.TOPIC)
                    .scanCompleted(outcome)
            }.onFailure { exception ->
                LOG.warn("scanCompleted publish failed; subscribers will not refresh for this scan", exception)
            }
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
        SwingUtilities.invokeLater { refreshAccentOnEdt(project, detectedId) }
    }

    /**
     * EDT body extracted from [tryRefreshAccentForDetected] so the membership
     * check + apply chain can be red/green tested synchronously without having
     * to pump a Swing event loop. The caller is expected to already be on the
     * EDT (production: wrapped in `SwingUtilities.invokeLater`; tests: called
     * directly on the test thread). Returns early on disposal or on a
     * language-override miss, logs and swallows any downstream apply or
     * settings-read failure.
     *
     * `internal` (no `@TestOnly`) because [tryRefreshAccentForDetected] — the
     * production caller — reaches this helper through the `SwingUtilities.invokeLater`
     * boundary; marking it test-only would misrepresent the call graph and
     * any `@TestOnly` inspection would either miss real misuse or flag this
     * legitimate production path.
     */
    internal fun refreshAccentOnEdt(
        project: Project,
        detectedId: String,
    ) {
        if (project.isDisposed) return
        // Widen the runCatching to cover the settings read AND the apply
        // chain. `AccentMappingsSettings.getInstance()` can fail during a
        // plugin-unload race or a corrupt persistent-state XML read on the
        // EDT — without the wrap, that throw would bubble out of the
        // invokeLater callback as an uncaught EDT exception, exactly the
        // failure mode the inner block already contains for the apply chain.
        runCatchingPreservingCancellation {
            // Re-read the mappings ON the EDT so membership reflects the
            // same state the apply chain is about to resolve against — the
            // Settings UI mutates `languageAccents` on EDT, and an off-EDT
            // membership check could observe a stale map between scan
            // completion and this callback's scheduling.
            val mappings = AccentMappingsSettings.getInstance().state
            if (detectedId !in mappings.languageAccents) return@runCatchingPreservingCancellation
            // Best-effort refresh: the cache already has the detected id,
            // so `dominant()` behavior is unaffected by failures here.
            // Containing exceptions keeps a regression in any of the
            // downstream apply paths (variant detection, UIManager writes,
            // focus-swap notification) from surfacing as an uncaught EDT
            // exception and risking the UI.
            val variant = AyuVariant.detect() ?: return@runCatchingPreservingCancellation
            val hex = AccentResolver.resolve(project, variant)
            AccentApplicator.apply(hex)
            ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
        }.onFailure { exception ->
            LOG.warn("Post-scan accent refresh failed; cache is still warm", exception)
        }
    }

    /**
     * `@TestOnly` seam for the `proportions()` cross-cache coherence guard —
     * lets tests simulate the race window where `weightsCache` is written for
     * a key before the matching `cache` (dominant-id) entry lands, by evicting
     * just the dominant-id side of the pair. Without this seam the guard at
     * [proportions] is unreachable from any black-box test because the sole
     * production write path ([detectAndCache]) writes both entries
     * back-to-back on the same thread.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun evictDominantCacheForTest(project: Project) {
        val key = AccentResolver.projectKey(project) ?: return
        cache.remove(key)
    }

    private fun isOnEdt(): Boolean = ApplicationManager.getApplication()?.isDispatchThread == true

    private fun detectInternal(project: Project): DetectionResult {
        // Scan can't give an authoritative answer right now (dumb mode, disposal
        // race, ReadAction failure). Don't cache — the next call retries so
        // detection catches up once the IDE stabilizes.
        val weights =
            ProjectLanguageScanner.scan(project)
                ?: return DetectionResult(languageId = null, cacheable = false)
        if (weights.isNotEmpty()) {
            val scanWinner = LanguageDetectionRules.pickDominantFromAllWeights(weights)
            if (scanWinner != null) {
                return DetectionResult(languageId = scanWinner, cacheable = true, weights = weights)
            }
            // Scan found weights but no clear or plurality winner. Consult the
            // legacy heuristic as a tiebreaker — but only accept its answer when
            // that language is already represented in the scan's code weights at
            // TIE_BREAK_MIN_SHARE. Guards against the SDK confidently reporting
            // a language that the scan didn't even find.
            resolveTiebreakFromLegacy(project, weights)?.let {
                return DetectionResult(languageId = it, cacheable = true, weights = weights)
            }
            // Polyglot no-winner verdict: cache the null id (definitive) but NOT
            // the weights — Phase 26 shows the polyglot copy on this state rather
            // than a weights breakdown, so leaving weightsCache empty keeps
            // proportions() returning null for a clean caller dispatch.
            return DetectionResult(languageId = null, cacheable = true, weights = null)
        }
        // Empty scan: brand-new project / everything-filtered-as-markup / newly
        // checked out. Fall back to the SDK + module heuristic; its result is
        // cached the same way the pre-scan implementation did. Legacy path produces
        // no weights — weightsCache stays empty and proportions() stays null.
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
        val base = codeWeights.ifEmpty { weights }
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
            // "jdk" covers "OpenJDK", "AdoptOpenJDK", "Amazon Corretto JDK"; the
            // conjunction handles "Java SDK" / "JavaSDK" SDK type names where
            // lowercasing drops the word boundary and the letters never form
            // a "jdk" substring (j-a-v-a-s-d-k).
            lowered.contains("jdk") ||
                (lowered.contains("java") && lowered.contains("sdk")) -> "java"
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
