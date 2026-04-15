package dev.ayuislands.settings.mappings

import com.intellij.openapi.components.BaseState

/**
 * Persisted application-level accent overrides resolved in priority order:
 * project (by canonical path) > language (by lowercase Language.id) > global.
 *
 * Display names are kept alongside project paths so orphaned rows (path no longer
 * exists on disk) can still render meaningfully in the settings table.
 */
class AccentMappingsState : BaseState() {
    var projectAccents by map<String, String>()
    var languageAccents by map<String, String>()
    var projectDisplayNames by map<String, String>()
}
