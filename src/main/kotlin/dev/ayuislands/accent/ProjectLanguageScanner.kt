package dev.ayuislands.accent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.TestOnly

/**
 * Walks a project's content roots under a read action and tallies per-language
 * bytes, cap-limited per file so generated monsters can't skew proportions,
 * cap-limited by total file count so traversal stays bounded, and sample-limited
 * so language/file-type work stays cheap on linux-kernel-sized repos.
 *
 * Separate object (not a private fun inside [ProjectLanguageDetector]) so the
 * detector's caching / threshold logic can be unit-tested via
 * `mockkObject(ProjectLanguageScanner)` without needing a live IntelliJ fixture.
 *
 * Returned map semantics:
 *  - `null`  → scan is not authoritative right now (project disposed, dumb mode,
 *              or the read action threw). Caller must NOT cache this; the next call
 *              retries — critical so a "scan during indexing" doesn't freeze a
 *              pre-indexing fallback into the cache forever.
 *  - empty map → scan ran cleanly and found zero recognized source files. Caller
 *                may fall through to the SDK/module legacy heuristic (brand-new
 *                project, docs-only repo that happened to filter out).
 *  - non-empty map → authoritative per-language byte totals. Caller decides
 *                    dominance via [LanguageDetectionRules.pickDominantFromAllWeights];
 *                    if no language clears the threshold the project is genuinely
 *                    polyglot and the override-chain falls through to global —
 *                    the SDK/module heuristic is deliberately NOT consulted,
 *                    because it would give a confident wrong answer.
 */
internal object ProjectLanguageScanner {
    private val LOG = logger<ProjectLanguageScanner>()

    /**
     * Scan [project]'s content roots for per-language byte totals.
     *
     * Returns null (not cacheable) when the IDE cannot give a trustworthy answer
     * right now — disposal race, dumb mode (indexing), or a ReadAction failure.
     * The caller re-attempts on the next invocation so detection catches up once
     * the IDE stabilizes. Platform cancellation is not a scan failure and is
     * deliberately propagated so coroutine callers keep structured cancellation.
     */
    fun scan(project: Project): Map<String, Long>? {
        if (project.isDisposed) return null
        // Dumb-mode skip: ProjectFileIndex.iterateContent itself is safe, but
        // FileType detection on freshly-opened projects can mis-classify before
        // indexes settle. Returning null (not emptyMap) ensures the detector
        // doesn't cache the pre-indexing fallback as a permanent answer.
        if (DumbService.isDumb(project)) return null

        val scanResult =
            runCatchingPreservingCancellation {
                ApplicationManager.getApplication().runReadAction<Map<String, Long>> {
                    scanUnderReadAction(project)
                }
            }
        val failure = scanResult.exceptionOrNull()
        if (failure != null) {
            LOG.warn(
                "Project content scan failed; caller will fall back to SDK/module heuristic",
                failure,
            )
            return null
        }
        if (project.isDisposed) return null
        return scanResult.getOrDefault(emptyMap())
    }

    private fun scanUnderReadAction(project: Project): Map<String, Long> {
        val weights = HashMap<String, Long>()
        val fileCount = IntArray(1)
        val sampledFileCount = IntArray(1)
        ProgressManager.checkCanceled()
        if (project.isDisposed) return emptyMap()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            ProgressManager.checkCanceled()
            if (project.isDisposed) return@iterateContent false
            val shouldContinue = visit(file, weights, fileCount, sampledFileCount)
            shouldContinue
        }
        return weights
    }

    /**
     * Per-file visitor. Returns `false` to stop iteration once
     * [LanguageDetectionRules.MAX_FILES_SCANNED] is reached.
     *
     * The cap counts every non-directory, non-path-excluded file the visitor
     * touches, NOT only files with a resolved language id. Without that the
     * cap became invisible on binary- / image- / archive-heavy repos: a game
     * asset tree with 50 000 PNGs and 200 Kotlin files would iterate all
     * 50 200 entries before stopping, defeating the whole "keep the EDT
     * responsive on monorepos" purpose of the cap.
     *
     * Per-file exceptions (mid-delete race, a language plugin throwing from its
     * FileType.detect) must NOT abort the scan. Non-cancellation failures are
     * logged at DEBUG and skipped, while IntelliJ and Kotlin cancellation
     * signals still propagate so long-running scans can stop promptly.
     */
    private fun visit(
        file: VirtualFile,
        weights: HashMap<String, Long>,
        fileCount: IntArray,
        sampledFileCount: IntArray,
    ): Boolean {
        if (fileCount[0] >= LanguageDetectionRules.MAX_FILES_SCANNED) return false
        // Drop vendored / generated / build-output dirs before paying for VFS
        // metadata access. This is the single biggest real-world accuracy win:
        // a Python project with a committed .venv otherwise mis-reports as
        // "dominant dependency library" rather than "dominant user code".
        if (LanguageDetectionRules.isExcludedPath(file.path)) return true
        if (file.isDirectory) return true
        // Count every non-directory, non-excluded file toward the cap so binary-
        // heavy repos don't bypass it (see KDoc rationale).
        fileCount[0]++
        if (!shouldSampleFile(fileCount[0], sampledFileCount[0])) return true
        sampledFileCount[0]++
        sampleLanguageWeight(file)?.let { (languageId, weight) ->
            weights.merge(languageId, weight) { a, b -> a + b }
        }
        return true
    }

    private fun shouldSampleFile(
        traversedFileCount: Int,
        sampledFileCount: Int,
    ): Boolean {
        if (sampledFileCount >= LanguageDetectionRules.PROJECT_LANGUAGE_MAX_SAMPLED_FILES) return false
        if (traversedFileCount <= LanguageDetectionRules.PROJECT_LANGUAGE_WARMUP_SAMPLE_FILES) return true
        val postWarmupOffset = traversedFileCount - LanguageDetectionRules.PROJECT_LANGUAGE_WARMUP_SAMPLE_FILES
        return postWarmupOffset % LanguageDetectionRules.PROJECT_LANGUAGE_SAMPLE_STRIDE == 0
    }

    @TestOnly
    internal fun sampleLanguageWeightForTest(file: VirtualFile): Pair<String, Long>? = sampleLanguageWeight(file)

    private fun sampleLanguageWeight(file: VirtualFile): Pair<String, Long>? {
        val sample =
            runCatchingPreservingCancellation {
                val languageId =
                    LanguageDetectionRules.resolveLanguageId(file.fileType)
                        ?: return@runCatchingPreservingCancellation null
                val weight = file.length.coerceIn(0L, LanguageDetectionRules.MAX_FILE_WEIGHT_BYTES)
                languageId to weight
            }
        val failure = sample.exceptionOrNull()
        if (failure != null) {
            // AlreadyDisposedException, mid-VFS-change IOException, third-party
            // plugin exceptions from custom FileType.detect implementations all
            // land here. Individual files are not worth failing a whole scan.
            LOG.debug("Skipping file during language scan: ${file.path}", failure)
            return null
        }
        return sample.getOrNull()
    }
}
