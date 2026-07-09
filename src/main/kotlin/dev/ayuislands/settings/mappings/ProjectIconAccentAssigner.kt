package dev.ayuislands.settings.mappings

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.color.ProjectIconAccentExtractor
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import java.io.File

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
object ProjectIconAccentAssigner {
    private val log = logger<ProjectIconAccentAssigner>()

    /** True when a fresh mapping was written for [project]. */
    fun assignIfAbsent(project: Project): Boolean {
        val state = AyuIslandsSettings.getInstance().state
        if (!state.projectIconAccentEnabled) return false
        if (!LicenseChecker.isLicensedOrGrace()) {
            log.debug("Project icon accent: unlicensed, skipping auto-assign")
            return false
        }
        val projectKey = AccentResolver.projectKey(project) ?: return false
        val mappings = AccentMappingsSettings.getInstance().state
        if (mappings.projectAccents.containsKey(projectKey)) {
            log.debug("Project icon accent: '$projectKey' already has an override, keeping it")
            return false
        }
        val iconFile = ProjectIconAccentExtractor.projectIconFile(project.basePath)
        if (iconFile == null) {
            log.debug("Project icon accent: no usable icon for '${project.name}'")
            return false
        }
        val accent = ProjectIconAccentExtractor.extract(iconFile) ?: return false

        mappings.projectAccents[projectKey] = accent.value
        mappings.projectDisplayNames[projectKey] = project.name.ifBlank { File(projectKey).name }
        log.info("Project icon accent: assigned ${accent.value} to '${project.name}' from ${iconFile.name}")
        return true
    }
}
