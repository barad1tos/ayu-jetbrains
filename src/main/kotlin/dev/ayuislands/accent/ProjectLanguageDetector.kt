package dev.ayuislands.accent

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
 *  2. **Module names** — fallback for Kotlin / Gradle / Android projects whose SDK
 *     is JVM but the module name hints at the real language.
 *
 * Empty / polyglot / no-SDK projects return `null` and fall through to the global accent.
 */
object ProjectLanguageDetector {
    // ConcurrentHashMap rejects null values, so we store an empty-string sentinel
    // whenever detection returns null. AYU language ids are always non-empty
    // ("kotlin", "python", ...) so the sentinel cannot collide with a real id.
    private const val NULL_SENTINEL = ""
    private val cache = ConcurrentHashMap<String, String>()

    /** Dominant language id for [project], cached per canonical path. */
    fun dominant(project: Project): String? {
        val key = AccentResolver.projectKey(project) ?: return null
        val cached = cache.getOrPut(key) { detectInternal(project) ?: NULL_SENTINEL }
        return cached.takeIf { it.isNotEmpty() }
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

    private fun detectInternal(project: Project): String? {
        val sdkName =
            runCatching {
                ProjectRootManager
                    .getInstance(project)
                    .projectSdk
                    ?.sdkType
                    ?.name
            }.getOrNull()
        sdkTypeToLanguageId(sdkName)?.let { return it }

        val moduleManager = runCatching { ModuleManager.getInstance(project) }.getOrNull() ?: return null
        for (module in moduleManager.modules) {
            moduleNameToLanguageId(module.name.lowercase())?.let { return it }
        }
        return null
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
