package dev.ayuislands.accent.conflict

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import dev.ayuislands.accent.AccentElementId

enum class ConflictType { BLOCK, INTEGRATE }

data class ConflictEntry(
    val pluginDisplayName: String,
    val pluginId: String,
    val affectedElements: Set<AccentElementId>,
    val type: ConflictType,
)

object ConflictRegistry {
    private val entries =
        listOf(
            ConflictEntry(
                pluginDisplayName = "Atom Material Icons",
                pluginId = "com.mallowigi",
                affectedElements = setOf(AccentElementId.CHECKBOXES),
                type = ConflictType.BLOCK,
            ),
            ConflictEntry(
                pluginDisplayName = "CodeGlance Pro",
                pluginId = "com.nasller.CodeGlancePro",
                affectedElements = emptySet(),
                type = ConflictType.INTEGRATE,
            ),
            ConflictEntry(
                pluginDisplayName = "Indent Rainbow",
                pluginId = "indent-rainbow.indent-rainbow",
                affectedElements = emptySet(),
                type = ConflictType.INTEGRATE,
            ),
        )

    // Cached: installed plugins don't change during a session
    private val cachedConflicts: List<ConflictEntry> by lazy {
        entries.filter { entry ->
            val pluginId = PluginId.getId(entry.pluginId)
            PluginManagerCore.getPlugin(pluginId) != null && !PluginManagerCore.isDisabled(pluginId)
        }
    }

    fun detectConflicts(): List<ConflictEntry> = cachedConflicts

    fun getConflictFor(elementId: AccentElementId): ConflictEntry? =
        cachedConflicts
            .firstOrNull { elementId in it.affectedElements }

    fun isCodeGlanceProDetected(): Boolean = cachedConflicts.any { it.pluginId == "com.nasller.CodeGlancePro" }

    fun isIndentRainbowDetected(): Boolean = cachedConflicts.any { it.pluginId == "indent-rainbow.indent-rainbow" }
}
