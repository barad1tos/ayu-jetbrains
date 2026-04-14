package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import java.io.File

/**
 * Resolves the effective accent hex for a given [project] and [variant] in priority order:
 *
 *  1. **Project override** — `AccentMappingsState.projectAccents[canonicalPath]`
 *  2. **Language override** — dominant language of the project via [ProjectLanguageDetector]
 *  3. **Global** — [AyuIslandsSettings.getAccentForVariant] (which itself honors
 *     follow-system-accent and per-variant stored hex)
 *
 * Zero-cost path: if both override maps are empty, the resolver does not touch
 * [ProjectLanguageDetector] and short-circuits to the global accent. See [Source]
 * for UI marker ordering.
 */
object AccentResolver {
    enum class Source { PROJECT_OVERRIDE, LANGUAGE_OVERRIDE, GLOBAL }

    fun resolve(
        project: Project?,
        variant: AyuVariant,
    ): String {
        val mappings = AccentMappingsSettings.getInstance().state
        if (project != null && !project.isDefault && !project.isDisposed) {
            projectKey(project)?.let { mappings.projectAccents[it] }?.let { return it }
            if (mappings.languageAccents.isNotEmpty()) {
                ProjectLanguageDetector.dominant(project)?.let { languageId ->
                    mappings.languageAccents[languageId]?.let { return it }
                }
            }
        }
        return AyuIslandsSettings.getInstance().getAccentForVariant(variant)
    }

    /**
     * Returns which layer of the resolution chain produced the accent for [project].
     * Used by the settings UI to surface "Currently active: ... (project override)" context.
     */
    fun source(
        project: Project?,
        @Suppress("UNUSED_PARAMETER") variant: AyuVariant,
    ): Source {
        val mappings = AccentMappingsSettings.getInstance().state
        if (project != null && !project.isDefault && !project.isDisposed) {
            projectKey(project)?.let { mappings.projectAccents[it] }?.let { return Source.PROJECT_OVERRIDE }
            if (mappings.languageAccents.isNotEmpty()) {
                ProjectLanguageDetector.dominant(project)?.let { languageId ->
                    if (mappings.languageAccents[languageId] != null) return Source.LANGUAGE_OVERRIDE
                }
            }
        }
        return Source.GLOBAL
    }

    /**
     * Stable key for a [project]: the canonicalized absolute path of `basePath`.
     * Returns `null` when the project has no base path or canonicalization throws.
     */
    fun projectKey(project: Project): String? =
        project.basePath?.let { raw ->
            runCatching { File(raw).canonicalPath }.getOrNull()
        }
}
