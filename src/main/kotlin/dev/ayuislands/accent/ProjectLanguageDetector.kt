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
 * Detector for the dominant language of a project. Resolver calls are gated to
 * exact language overrides, language fallback, or project fallback work; Settings
 * diagnostics and explicit rescans may read or refresh the cached verdict directly.
 *
 * Detection strategy (most-authoritative-first):
 *
 *  1. **Content scan** — [ProjectLanguageScanner] walks content roots, tallies per-id
 *     bytes (cap-limited per file, cap-limited by file count, vendored / generated
 *     / build-output dirs filtered by [LanguageDetectionRules.EXCLUDED_PATH_SEGMENTS]),
 *     and [LanguageDetectionRules.pickDominantFromAllWeights] returns the language
 *     whose share clears the primary threshold OR passes the leading-plurality rule.
 *  2. **Legacy SDK/module heuristic as polyglot tiebreaker** — only consulted when the
 *     scan produced weights but no winner, AND the legacy hint clears the configured
 *     minimum share in the scan's code weights. Protects against the v2.5 → v2.6
 *     upgrade regression where a 50/50 Java/Kotlin JVM project would silently drop its
 *     "kotlin" override.
 *  3. **Legacy SDK/module heuristic as fallback** — used only when the scan found zero
 *     recognized source files: brand-new project, docs-only-after-filtering, or
 *     pre-indexing state.
 *
 * Concurrency: `dominant()` may be called from EDT (settings UI, focus-swap listener).
 * On EDT, first-call detection on a large monorepo is a ~300–500 ms freeze. To avoid
 * that, an EDT invocation with an empty cache kicks off a deduplicated background scan
 * via [ProjectLanguageScanAsync] and returns null immediately; the caller falls through
 * to the global accent. When the BG scan completes with a cacheable verdict, the
 * detector re-applies the resolver result on EDT so the UI picks up either the new
 * winner or the fallback without waiting for the next focus swap.
 *
 * Cache correctness: a detection whose scan threw is NOT cached — the next call
 * retries. A scan that ran cleanly but produced no winner (after both the proportional
 * rule AND the SDK tiebreak fail) IS cached as [ProjectLanguageVerdict.NoWinner],
 * because the answer is definitive.
 * Legacy SDK/module lookups that throw are NOT cached; without this guard a single
 * transient failure would permanently poison the cache for that project.
 */
object ProjectLanguageDetector {
    private val LOG = logger<ProjectLanguageDetector>()

    private val verdictCache = ConcurrentHashMap<String, ProjectLanguageVerdict>()

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
        AccentMappingsSettings
            .getInstance()
            .state
            .forcedProjectLanguages[key]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
            ?.let { return it }
        verdictCache[key]?.let { cached ->
            return (cached as? ProjectLanguageVerdict.Detected)?.languageId
        }

        if (isOnEdt()) {
            scheduleBackgroundDetection(project, key)
            return null
        }
        return (detectAndCache(project, key) as? ProjectLanguageVerdict.Detected)?.languageId
    }

    /**
     * Per-language byte-weight map for [project], read strictly from the warm cache.
     *
     * Returns null when the cached verdict is cold (never scanned), when the scan
     * did not settle on a UI-visible dominant breakdown (definitive no-winner,
     * legacy SDK fallback path, empty-scan path, or current-attempt unavailable
     * path — caller renders the polyglot copy), or when the project's canonical
     * path cannot be resolved.
     *
     * Never triggers a scan or schedules background work. The caller is responsible
     * for rendering a fallback (polyglot copy) on null. The single [verdictCache]
     * entry is warmed via [dominant] (called from
     * [dev.ayuislands.AyuIslandsStartupActivity] and the focus-swap path in
     * [AccentResolver]) and invalidated via [invalidate].
     *
     * Phase 26 contract: this is a read-only projection of existing detector state.
     * Phase 29 supplies a manual rescan trigger; Phase 31 may extend this API with
     * a scan-status discriminant. Neither should weaken the "no scan on miss"
     * invariant established here.
     */
    fun proportions(project: Project): Map<String, Long>? =
        when (val cached = verdict(project)) {
            is ProjectLanguageVerdict.Detected -> cached.weights
            is ProjectLanguageVerdict.NoWinner,
            ProjectLanguageVerdict.Cold,
            ProjectLanguageVerdict.Empty,
            ProjectLanguageVerdict.Unavailable,
            -> null
        }

    internal fun verdict(
        project: Project,
        warmCache: Boolean = false,
    ): ProjectLanguageVerdict {
        val key = AccentResolver.projectKey(project) ?: return ProjectLanguageVerdict.Unavailable
        verdictCache[key]?.let { return it }
        if (!warmCache) return ProjectLanguageVerdict.Cold
        if (isOnEdt()) {
            scheduleBackgroundDetection(project, key)
            return ProjectLanguageVerdict.Cold
        }
        return detectAndCache(project, key)
    }

    /**
     * Clear the cached detection for [project]. Called from the project-close
     * listener ([ProjectLanguageCacheInvalidator]) so a re-opened project can
     * be re-analyzed, and from the `ModuleRootListener` subscription registered
     * in [dev.ayuislands.AyuIslandsStartupActivity] so mid-session content-root
     * changes (gradle sync, module add/remove) trigger a fresh scan.
     *
     * Evicts the single cached verdict under that key so `dominant()`,
     * `verdict()`, and `proportions()` all re-read a consistent cold state on
     * the next access.
     */
    fun invalidate(project: Project) {
        val key = AccentResolver.projectKey(project) ?: return
        verdictCache.remove(key)
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
     *  - Evicts the cached verdict for [project].
     *  - Schedules a background scan on the shared IDE pool (no-op in dumb
     *    mode — the user must re-click after indexing finishes, or wait for
     *    the next `ModuleRootListener.rootsChanged` to re-trigger detection
     *    on a Gradle sync / module change).
     *  - On scan completion, [ProjectLanguageDetectionListener.scanCompleted]
     *    fires on EDT via [publishScanCompleted], so UI subscribers (the
     *    Settings proportions row, the action's balloon) receive the new
     *    state without polling.
     *  - When [project] has no canonical path (disposal race, default
     *    project), publishes [ScanOutcome.Unavailable] immediately so one-shot
     *    subscribers (notably the [dev.ayuislands.actions.RescanLanguageAction]
     *    balloon) fire and disconnect instead of silently hanging on the
     *    MessageBus until project close.
     *
     * Safe to call from EDT: invalidate is a single
     * `ConcurrentHashMap.remove`, and `scheduleBackgroundDetection` immediately returns after
     * enqueuing the task on `AppExecutorUtil.getAppExecutorService()`.
     */
    fun rescan(project: Project) {
        val key =
            AccentResolver.projectKey(project) ?: run {
                // No canonical path: bail out of the cache+schedule work, but
                // still publish Unavailable so subscribers (especially the
                // one-shot action balloon) fire and release their
                // MessageBus connection instead of silently leaking until
                // project close. `AccentResolver.projectKey` already logged the
                // reason it returned null (base-path throw, canonicalization
                // failure) — this branch is about the *user-facing* symptom.
                LOG.info("rescan: projectKey unavailable; publishing Unavailable so subscribers unblock")
                publishScanCompleted(project, ScanOutcome.Unavailable)
                return
            }
        verdictCache.remove(key)
        scheduleBackgroundDetection(project, key)
    }

    /** Drop the entire cache — useful for test isolation. */
    fun clear() {
        verdictCache.clear()
    }

    /**
     * Result of a single detection attempt. `cacheable = false` signals a transient
     * failure (the underlying IntelliJ API threw) — the caller must NOT persist this
     * result, so the next invocation retries instead of serving a poisoned cache entry.
     *
     * [verdict] carries the fully-typed post-detection state so later resolver and
     * settings layers can distinguish "cold", "empty", "unavailable", "winner", and
     * definitive "no winner" cases without re-interpreting ad hoc null/sentinel
     * combinations.
     */
    private data class DetectionResult(
        val verdict: ProjectLanguageVerdict,
        val cacheable: Boolean,
    )

    /**
     * EDT-safe detection wrapper. Off-EDT callers invoke this directly;
     * [dominant] routes EDT callers through [scheduleBackgroundDetection] instead.
     */
    private fun detectAndCache(
        project: Project,
        key: String,
    ): ProjectLanguageVerdict {
        val detection = detectInternal(project)
        if (detection.cacheable) {
            verdictCache[key] = detection.verdict
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
        return detection.verdict
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
            val verdict = detectAndCache(project, key)
            val outcome: ScanOutcome =
                when (verdict) {
                    is ProjectLanguageVerdict.Detected -> ScanOutcome.Detected(verdict.languageId)
                    is ProjectLanguageVerdict.NoWinner,
                    ProjectLanguageVerdict.Empty,
                    -> ScanOutcome.Polyglot
                    ProjectLanguageVerdict.Cold,
                    ProjectLanguageVerdict.Unavailable,
                    -> ScanOutcome.Unavailable
                }
            when (verdict) {
                is ProjectLanguageVerdict.Detected,
                is ProjectLanguageVerdict.NoWinner,
                ProjectLanguageVerdict.Empty,
                -> tryRefreshAccentAfterCacheableScan(project)

                ProjectLanguageVerdict.Cold,
                ProjectLanguageVerdict.Unavailable,
                -> Unit
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
     * After a background scan reaches a cacheable verdict, re-apply the current
     * resolver result. Both positive hits and definitive polyglot/no-match
     * outcomes can change the visible accent: a hit may enable a language
     * override, while a fallback verdict may remove one.
     */
    private fun tryRefreshAccentAfterCacheableScan(project: Project) {
        SwingUtilities.invokeLater { refreshAccentOnEdt(project) }
    }

    /**
     * EDT body extracted from [tryRefreshAccentAfterCacheableScan] so the
     * resolver + apply chain can be red/green tested synchronously without
     * having to pump a Swing event loop. The caller is expected to already be
     * on the EDT (production: wrapped in `SwingUtilities.invokeLater`; tests:
     * called directly on the test thread). Returns early on disposal, logs and
     * swallows any downstream apply failure.
     *
     * `internal` (no `@TestOnly`) because [tryRefreshAccentAfterCacheableScan]
     * — the production caller — reaches this helper through the
     * `SwingUtilities.invokeLater` boundary; marking it test-only would
     * misrepresent the call graph and any `@TestOnly` inspection would either
     * miss real misuse or flag this legitimate production path.
     */
    internal fun refreshAccentOnEdt(project: Project) {
        if (project.isDisposed) return
        runCatchingPreservingCancellation {
            // Best-effort refresh: the cache already has the cacheable scan
            // verdict, so `dominant()` behavior is unaffected by failures here.
            // Containing exceptions keeps a regression in any of the
            // downstream apply paths (variant detection, UIManager writes,
            // focus-swap notification) from surfacing as an uncaught EDT
            // exception and risking the UI.
            if (AccentApplicator.resolveFocusedProject() !== project) {
                LOG.debug("Post-scan accent refresh skipped because focused project changed")
                return@runCatchingPreservingCancellation
            }
            val variant = AyuVariant.detect() ?: return@runCatchingPreservingCancellation
            val hex = AccentResolver.resolve(project, variant)
            val applied = AccentApplicator.applyFromHexString(hex)
            if (applied) {
                ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
            } else {
                LOG.warn("Skipping swap publish: applyFromHexString rejected '$hex'")
            }
        }.onFailure { exception ->
            LOG.warn("Post-scan accent refresh failed; cache is still warm", exception)
        }
    }

    /**
     * `@TestOnly` seam for forcing a warmed verdict back to the cold state.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun evictVerdictCacheForTest(project: Project) {
        val key = AccentResolver.projectKey(project) ?: return
        verdictCache.remove(key)
    }

    private fun detectInternal(project: Project): DetectionResult {
        // Scan can't give an authoritative answer right now (dumb mode, disposal
        // race, ReadAction failure). Don't cache — the next call retries so
        // detection catches up once the IDE stabilizes.
        val weights =
            ProjectLanguageScanner.scan(project)
                ?: return DetectionResult(ProjectLanguageVerdict.Unavailable, cacheable = false)
        val mappings = AccentMappingsSettings.getInstance().state
        val policy = mappings.languageResolutionPolicy()
        if (weights.isNotEmpty()) {
            val scanWinner = LanguageDetectionRules.pickDominantFromAllWeights(weights, policy)
            if (scanWinner != null) {
                return DetectionResult(
                    ProjectLanguageVerdict.Detected(scanWinner, weights),
                    cacheable = true,
                )
            }
            LanguageDetectionRules
                .pickTopMappedLanguage(weights, mappings.languageAccents.keys)
                ?.let { mappedWinner ->
                    return DetectionResult(
                        ProjectLanguageVerdict.Detected(mappedWinner, weights),
                        cacheable = true,
                    )
                }
            // Scan found weights but no clear or plurality winner. Consult the
            // legacy heuristic as a tiebreaker — but only accept its answer when
            // that language is already represented in the scan's code weights at the
            // configured tiebreak floor. Guards against the SDK confidently reporting
            // a language that the scan didn't even find.
            resolveTiebreakFromLegacy(project, weights, policy)?.let {
                return DetectionResult(
                    ProjectLanguageVerdict.Detected(it, weights),
                    cacheable = true,
                )
            }
            return DetectionResult(ProjectLanguageVerdict.NoWinner(weights), cacheable = true)
        }
        return legacySdkModuleDetection(project)
    }

    /**
     * When the proportional scan is polyglot, fall back to the legacy SDK / module
     * heuristic — but only if the language it names has a foothold in the scan's
     * code weights according to [LanguageDetectionRules.ResolutionPolicy.tiebreakMinShare].
     * Returns null when the hint doesn't meet that bar.
     */
    private fun resolveTiebreakFromLegacy(
        project: Project,
        weights: Map<String, Long>,
        policy: LanguageDetectionRules.ResolutionPolicy,
    ): String? {
        val hint =
            (legacySdkModuleDetection(project).verdict as? ProjectLanguageVerdict.Detected)
                ?.languageId
                ?: return null
        val codeWeights = weights.filterKeys { it !in LanguageDetectionRules.MARKUP_IDS }
        val base = codeWeights.ifEmpty { weights }
        val total = base.values.sum()
        if (total <= 0L) return null
        val hintWeight = base[hint] ?: 0L
        if (hintWeight <= 0L) return null
        val share = hintWeight.toDouble() / total.toDouble()
        return if (share >= policy.tiebreakMinShare) hint else null
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
            return DetectionResult(ProjectLanguageVerdict.Unavailable, cacheable = false)
        }
        sdkTypeToLanguageId(sdkResult.getOrNull())?.let {
            return DetectionResult(
                ProjectLanguageVerdict.Detected(it, weights = null),
                cacheable = true,
            )
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
            return DetectionResult(ProjectLanguageVerdict.Unavailable, cacheable = false)
        }
        val moduleNames = moduleResult.getOrDefault(emptyList())
        for (moduleName in moduleNames) {
            moduleNameToLanguageId(moduleName.lowercase(Locale.ROOT))?.let {
                return DetectionResult(
                    ProjectLanguageVerdict.Detected(it, weights = null),
                    cacheable = true,
                )
            }
        }
        return DetectionResult(ProjectLanguageVerdict.Empty, cacheable = true)
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

private fun isOnEdt(): Boolean = ApplicationManager.getApplication()?.isDispatchThread == true
