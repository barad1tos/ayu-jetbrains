package dev.ayuislands.accent

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Heuristic detector for the dominant language of a project, used only when
 * the language-accent override map is non-empty (the [AccentResolver] gates this).
 *
 * Detection strategy:
 *  1. **Project SDK type name** — covers JVM, Python, Go, Rust, Node, etc.
 *  2. **Module names** — fallback when the SDK is JVM but the module name hints at the real
 *     language. Actual module keywords matched: `kotlin`, `python`, `scala`,
 *     `android` (→ kotlin), `flutter` / `dart` (→ dart).
 *
 * Empty / polyglot / no-SDK projects return `null` and fall through to the global accent.
 *
 * Cache correctness: a detection that threw (project mid-dispose, SDK list mutation race,
 * ModuleManager teardown) is **not** cached — the next call retries. Without that guard,
 * a single transient failure would permanently poison the cache for that project.
 */
object ProjectLanguageDetector {
    private val LOG = logger<ProjectLanguageDetector>()

    // ConcurrentHashMap rejects null values, so we store an empty-string sentinel
    // whenever detection definitively returns null (SDK absent AND no matching module).
    // AYU language ids are always non-empty ("kotlin", "python", ...) so the sentinel
    // cannot collide with a real id.
    private const val NULL_SENTINEL = ""
    private val cache = ConcurrentHashMap<String, String>()

    /** Dominant language id for [project], cached per canonical path. */
    fun dominant(project: Project): String? {
        val key = AccentResolver.projectKey(project) ?: return null
        cache[key]?.let { return it.takeIf { hit -> hit.isNotEmpty() } }

        val detection = detectInternal(project)
        if (detection.cacheable) {
            cache[key] = detection.languageId ?: NULL_SENTINEL
        }
        return detection.languageId
    }

    /**
     * Clear the cached detection for [project]; call from project-close hooks
     * so a re-opened project can be re-analyzed.
     */
    fun invalidate(project: Project) {
        AccentResolver.projectKey(project)?.let { cache.remove(it) }
    }

    /** Drop the entire cache — useful for test isolation. */
    fun clear() {
        cache.clear()
    }

    /**
     * Result of a single detection attempt. `cacheable = false` signals a transient failure
     * (the underlying IntelliJ API threw) — the caller must NOT persist this result, so the
     * next invocation retries instead of serving a poisoned cache entry.
     */
    private data class DetectionResult(
        val languageId: String?,
        val cacheable: Boolean,
    )

    private fun detectInternal(project: Project): DetectionResult {
        val sdkResult =
            runCatching {
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

        val moduleResult = runCatching { ModuleManager.getInstance(project).modules.map { it.name } }
        if (moduleResult.isFailure) {
            LOG.warn(
                "Module lookup failed during language detection; will retry on next call instead of caching null",
                moduleResult.exceptionOrNull(),
            )
            return DetectionResult(languageId = null, cacheable = false)
        }
        val moduleNames = moduleResult.getOrDefault(emptyList())
        for (moduleName in moduleNames) {
            moduleNameToLanguageId(moduleName.lowercase())?.let {
                return DetectionResult(languageId = it, cacheable = true)
            }
        }
        // Definitive null — no SDK match, no module match. Safe to cache.
        return DetectionResult(languageId = null, cacheable = true)
    }

    private fun sdkTypeToLanguageId(sdkTypeName: String?): String? {
        if (sdkTypeName == null) return null
        val lowered = sdkTypeName.lowercase()
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
