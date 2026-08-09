package dev.ayuislands.settings.mappings

import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentMappingsView
import java.io.File
import java.util.Locale

internal data class AccentMappingsSnapshot(
    val projectMappings: List<ProjectMapping>,
    val languageMappings: List<LanguageMapping>,
    val projectFallbackAccents: Map<String, String>,
    val forcedLanguages: Map<String, String>,
    val languageFallbackAccent: String?,
) {
    companion object {
        fun empty(): AccentMappingsSnapshot =
            AccentMappingsSnapshot(
                projectMappings = emptyList(),
                languageMappings = emptyList(),
                projectFallbackAccents = emptyMap(),
                forcedLanguages = emptyMap(),
                languageFallbackAccent = null,
            )
    }
}

internal class AccentMappingsDraft : AccentMappingsView {
    private var stored = AccentMappingsSnapshot.empty()
    private var pending = stored.copyCollections()

    val projectMappings: List<ProjectMapping>
        get() = pending.projectMappings

    val languageMappings: List<LanguageMapping>
        get() = pending.languageMappings

    val isModified: Boolean
        get() = pending != stored

    override fun projectAccent(projectKey: String): String? =
        pending.projectMappings.lastOrNull { it.canonicalPath == projectKey }?.hex

    override fun forcedLanguageId(projectKey: String): String? = pending.forcedLanguages[projectKey]

    override fun hasForcedLanguageEntry(projectKey: String): Boolean = pending.forcedLanguages.containsKey(projectKey)

    override fun languageAccent(languageId: String): String? =
        pending.languageMappings.lastOrNull { it.languageId == languageId }?.hex

    override val hasLanguageAccents: Boolean
        get() = pending.languageMappings.isNotEmpty()

    override val languageFallbackAccent: String?
        get() = pending.languageFallbackAccent

    override fun projectFallbackAccent(projectKey: String): String? = pending.projectFallbackAccents[projectKey]

    override fun hasProjectFallbackCandidate(projectKey: String): Boolean =
        pending.projectFallbackAccents.containsKey(projectKey)

    fun load(
        state: AccentMappingsState,
        languageDisplayName: (String) -> String?,
        warn: (String) -> Unit = {},
    ) {
        val loaded =
            AccentMappingsSnapshot(
                projectMappings = normalizedProjects(state, warn),
                languageMappings = normalizedLanguages(state, languageDisplayName, warn),
                projectFallbackAccents = normalizedFallbackAccents(state.projectFallbackAccents, warn),
                forcedLanguages = normalizedForcedLanguages(state.forcedProjectLanguages, warn),
                languageFallbackAccent = normalizedLanguageFallbackAccent(state.languageFallbackAccent, warn),
            )
        stored = loaded.copyCollections()
        pending = loaded.copyCollections()
    }

    fun addProject(mapping: ProjectMapping): Int {
        val updated = pending.projectMappings + mapping
        replacePending(pending.copy(projectMappings = updated))
        return updated.lastIndex
    }

    fun removeProject(index: Int) {
        if (index !in pending.projectMappings.indices) return
        replacePending(
            pending.copy(
                projectMappings = pending.projectMappings.filterIndexed { row, _ -> row != index },
            ),
        )
    }

    fun updateProjectHex(
        index: Int,
        hex: String,
    ) {
        if (index !in pending.projectMappings.indices) return
        replacePending(
            pending.copy(
                projectMappings =
                    pending.projectMappings.mapIndexed { row, mapping ->
                        if (row == index) mapping.copy(hex = hex) else mapping
                    },
            ),
        )
    }

    fun addLanguage(mapping: LanguageMapping): Int {
        val updated = pending.languageMappings + mapping
        replacePending(pending.copy(languageMappings = updated))
        return updated.lastIndex
    }

    fun removeLanguage(index: Int) {
        if (index !in pending.languageMappings.indices) return
        replacePending(
            pending.copy(
                languageMappings = pending.languageMappings.filterIndexed { row, _ -> row != index },
            ),
        )
    }

    fun updateLanguageHex(
        index: Int,
        hex: String,
    ) {
        if (index !in pending.languageMappings.indices) return
        replacePending(
            pending.copy(
                languageMappings =
                    pending.languageMappings.mapIndexed { row, mapping ->
                        if (row == index) mapping.copy(hex = hex) else mapping
                    },
            ),
        )
    }

    fun setProjectFallbackAccent(
        projectKey: String,
        hex: String?,
    ) {
        val updated = pending.projectFallbackAccents.toMutableMap()
        if (hex == null) {
            updated.remove(projectKey)
        } else {
            normalizedFallbackAccent(projectKey, hex)?.let { (key, value) -> updated[key] = value }
        }
        replacePending(pending.copy(projectFallbackAccents = updated))
    }

    fun setForcedLanguage(
        projectKey: String,
        languageId: String?,
    ) {
        require(projectKey.isNotBlank()) { "projectKey must not be blank" }
        val updated = pending.forcedLanguages.toMutableMap()
        val normalized = normalizeLanguageId(languageId)
        if (normalized == null) {
            updated.remove(projectKey)
        } else {
            updated[projectKey] = normalized
        }
        replacePending(pending.copy(forcedLanguages = updated))
    }

    fun setLanguageFallbackAccent(hex: String?) {
        replacePending(
            pending.copy(languageFallbackAccent = normalizedLanguageFallbackAccent(hex)),
        )
    }

    fun reset() {
        pending = stored.copyCollections()
    }

    fun writeTo(state: AccentMappingsState) {
        state.projectAccents.clear()
        state.projectDisplayNames.clear()
        pending.projectMappings.forEach { mapping ->
            state.projectAccents[mapping.canonicalPath] = mapping.hex
            state.projectDisplayNames[mapping.canonicalPath] = mapping.displayName
        }
        state.languageAccents.clear()
        pending.languageMappings.forEach { mapping ->
            state.languageAccents[mapping.languageId] = mapping.hex
        }
        state.projectFallbackAccents.clear()
        state.projectFallbackAccents.putAll(pending.projectFallbackAccents)
        state.forcedProjectLanguages.clear()
        state.forcedProjectLanguages.putAll(pending.forcedLanguages)
        state.languageFallbackAccent = pending.languageFallbackAccent
    }

    fun markCommitted() {
        stored = pending.copyCollections()
    }

    private fun replacePending(snapshot: AccentMappingsSnapshot) {
        pending = snapshot.copyCollections()
    }
}

private fun AccentMappingsSnapshot.copyCollections(): AccentMappingsSnapshot =
    copy(
        projectMappings = projectMappings.toList(),
        languageMappings = languageMappings.toList(),
        projectFallbackAccents = projectFallbackAccents.toMap(),
        forcedLanguages = forcedLanguages.toMap(),
    )

private fun normalizedProjects(
    state: AccentMappingsState,
    warn: (String) -> Unit,
): List<ProjectMapping> =
    state.projectAccents.mapNotNull { (path, hex) ->
        try {
            ProjectMapping(
                canonicalPath = path,
                displayName = state.projectDisplayNames[path] ?: File(path).name,
                hex = hex,
            )
        } catch (exception: IllegalArgumentException) {
            warn("Dropping malformed project override row (path='$path', hex='$hex'): ${exception.message}")
            null
        }
    }

private fun normalizedLanguages(
    state: AccentMappingsState,
    languageDisplayName: (String) -> String?,
    warn: (String) -> Unit,
): List<LanguageMapping> =
    state.languageAccents.mapNotNull { (languageId, hex) ->
        try {
            val displayName =
                try {
                    languageDisplayName(languageId)
                } catch (exception: RuntimeException) {
                    warn("Language display-name lookup failed for id='$languageId': ${exception.message}")
                    null
                }?.takeIf { it.isNotBlank() } ?: languageId
            LanguageMapping(
                languageId = languageId,
                displayName = displayName,
                hex = hex,
            )
        } catch (exception: IllegalArgumentException) {
            warn("Dropping malformed language override row (id='$languageId', hex='$hex'): ${exception.message}")
            null
        }
    }

private fun normalizedFallbackAccents(
    entries: Map<String, String>,
    warn: (String) -> Unit = {},
): Map<String, String> =
    entries
        .mapNotNull { (projectKey, hex) -> normalizedFallbackAccent(projectKey, hex, warn) }
        .toMap()

private fun normalizedLanguageFallbackAccent(
    hex: String?,
    warn: (String) -> Unit = {},
): String? =
    hex
        ?.takeIf { it.isNotBlank() }
        ?.let { rawHex ->
            AccentHex.of(rawHex)?.value ?: run {
                warn("Dropping malformed language fallback override accent")
                null
            }
        }

private fun normalizedFallbackAccent(
    projectKey: String,
    hex: String,
    warn: (String) -> Unit = {},
): Pair<String, String>? {
    if (projectKey.isBlank()) {
        warn("Dropping malformed project fallback override row: blank project key")
        return null
    }
    val normalizedHex =
        AccentHex.of(hex)?.value ?: run {
            warn("Dropping malformed project fallback override row (key='$projectKey')")
            return null
        }
    return projectKey to normalizedHex
}

private fun normalizedForcedLanguages(
    entries: Map<String, String>,
    warn: (String) -> Unit = {},
): Map<String, String> =
    entries
        .mapNotNull { (projectKey, languageId) ->
            normalizedForcedLanguage(projectKey, languageId, warn)
        }.toMap()

private fun normalizedForcedLanguage(
    projectKey: String,
    languageId: String,
    warn: (String) -> Unit = {},
): Pair<String, String>? {
    if (projectKey.isBlank()) {
        warn("Dropping malformed forced language override row: blank project key")
        return null
    }
    val normalizedLanguageId =
        normalizeLanguageId(languageId) ?: run {
            warn("Dropping malformed forced language override row (key='$projectKey')")
            return null
        }
    return projectKey to normalizedLanguageId
}

private fun normalizeLanguageId(languageId: String?): String? =
    languageId?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
