package dev.ayuislands.accent

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

/**
 * Walks a project's content roots under a [ReadAction] and tallies per-language
 * bytes, cap-limited per file so generated monsters can't skew proportions and
 * cap-limited by total file count so the scan doesn't block the EDT on
 * linux-kernel-sized repos.
 *
 * Separate object (not a private fun inside [ProjectLanguageDetector]) so the
 * detector's caching / threshold logic can be unit-tested via
 * `mockkObject(ProjectLanguageScanner)` without needing a live IntelliJ fixture.
 *
 * Returned map semantics:
 *  - `null`  → scan is not authoritative right now (project disposed, dumb mode,
 *              or ReadAction threw). Caller must NOT cache this; the next call
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
     * the IDE stabilizes.
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
                ReadAction.compute<Map<String, Long>, RuntimeException> {
                    scanUnderReadAction(project)
                }
            }
        if (scanResult.isFailure) {
            LOG.warn(
                "Project content scan failed; caller will fall back to SDK/module heuristic",
                scanResult.exceptionOrNull(),
            )
            return null
        }
        return scanResult.getOrDefault(emptyMap())
    }

    private fun scanUnderReadAction(project: Project): Map<String, Long> {
        val weights = HashMap<String, Long>()
        val fileCount = IntArray(1)
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            visit(file, weights, fileCount)
        }
        return weights
    }

    /**
     * Per-file visitor. Returns `false` to stop iteration once
     * [LanguageDetectionRules.MAX_FILES_SCANNED] is reached — the existing
     * sample is assumed representative of the rest.
     *
     * Per-file exceptions (mid-delete race, a language plugin throwing from its
     * FileType.detect) must NOT abort the scan. [runCatchingPreservingCancellation]
     * captures them into a Result (logged at DEBUG and skipped) while still
     * letting [kotlin.coroutines.cancellation.CancellationException] propagate so
     * structured concurrency in coroutine-driven callers stays intact.
     */
    private fun visit(
        file: VirtualFile,
        weights: HashMap<String, Long>,
        fileCount: IntArray,
    ): Boolean {
        if (fileCount[0] >= LanguageDetectionRules.MAX_FILES_SCANNED) return false
        // Drop vendored / generated / build-output dirs before paying for VFS
        // metadata access. This is the single biggest real-world accuracy win:
        // a Python project with a committed .venv otherwise mis-reports as
        // "dominant dependency library" rather than "dominant user code".
        if (LanguageDetectionRules.isExcludedPath(file.path)) return true
        val sampleResult =
            runCatchingPreservingCancellation {
                if (file.isDirectory) return@runCatchingPreservingCancellation null
                val languageId =
                    LanguageDetectionRules.resolveLanguageId(file.fileType)
                        ?: return@runCatchingPreservingCancellation null
                val weight = file.length.coerceIn(0L, LanguageDetectionRules.MAX_FILE_WEIGHT_BYTES)
                languageId to weight
            }
        sampleResult.getOrNull()?.let { (languageId, weight) ->
            weights.merge(languageId, weight) { a, b -> a + b }
            fileCount[0]++
        }
        if (sampleResult.isFailure) {
            // AlreadyDisposedException, mid-VFS-change IOException, third-party
            // plugin exceptions from custom FileType.detect implementations all
            // land here. Individual files are not worth failing a whole scan.
            LOG.debug("Skipping file during language scan: ${file.path}", sampleResult.exceptionOrNull())
        }
        return true
    }
}
