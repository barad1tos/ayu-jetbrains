package dev.ayuislands.settings.mappings

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.color.ProjectIconAccentExtractor
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Auto-assigns a per-project accent derived from the project icon.
 *
 * Producer for [AccentMappingsState.projectAccents]: when the premium
 * "accent from project icon" toggle is on and a project WITHOUT an existing
 * override opens, the icon's dominant color (clamped to the accent band) is
 * written into the same maps manual pins use — the resolver, settings table,
 * and quick switcher see an ordinary project override, so no resolution
 * ladder changes are needed. An existing mapping is user intent and is never
 * overwritten; deleting the row re-derives on the next project open.
 * `projectDisplayNames` is written in lockstep with `projectAccents` per the
 * [AccentMappingsSettings] invariant.
 */
internal object ProjectIconAccentAssigner {
    private val log = logger<ProjectIconAccentAssigner>()

    /** True when a fresh mapping was written for [project]. */
    suspend fun assignIfAbsent(project: Project): Boolean = assignIfAbsent(project, Dispatchers.EDT)

    @TestOnly
    internal suspend fun assignIfAbsent(
        project: Project,
        uiContext: CoroutineContext,
    ): Boolean {
        if (!withContext(uiContext) { canAutoAssign() }) return false

        val projectKey = withContext(Dispatchers.IO) { AccentResolver.projectKey(project) } ?: return false
        val shouldExtract =
            withContext(uiContext) {
                canAutoAssign() && !hasExistingMapping(projectKey)
            }
        if (!shouldExtract) return false

        val candidate =
            withContext(Dispatchers.IO) {
                val iconFile = ProjectIconAccentExtractor.projectIconFile(projectKey)
                if (iconFile == null) {
                    log.debug("Project icon accent: no usable icon for '$projectKey'")
                    return@withContext null
                }
                val accent = ProjectIconAccentExtractor.extract(iconFile) ?: return@withContext null
                IconAccentCandidate(accent, iconFile.name)
            } ?: return false

        return withContext(uiContext) {
            if (!canAutoAssign() || hasExistingMapping(projectKey)) return@withContext false

            val mappings = AccentMappingsSettings.getInstance().state
            val projectName = project.name
            mappings.projectAccents[projectKey] = candidate.accent.value
            mappings.projectDisplayNames[projectKey] = projectName.ifBlank { File(projectKey).name }
            log.info(
                "Project icon accent: assigned ${candidate.accent.value} to '$projectName' " +
                    "from ${candidate.iconName}",
            )
            true
        }
    }

    private fun canAutoAssign(): Boolean {
        if (!AyuIslandsSettings.getInstance().state.projectIconAccentEnabled) return false
        if (!LicenseChecker.isLicensedOrGrace()) {
            log.debug("Project icon accent: unlicensed, skipping auto-assign")
            return false
        }
        return true
    }

    private fun hasExistingMapping(projectKey: String): Boolean {
        val hasMapping =
            AccentMappingsSettings
                .getInstance()
                .state
                .projectAccents
                .containsKey(projectKey)
        if (hasMapping) {
            log.debug("Project icon accent: '$projectKey' already has an override, keeping it")
        }
        return hasMapping
    }

    private data class IconAccentCandidate(
        val accent: AccentHex,
        val iconName: String,
    )
}
