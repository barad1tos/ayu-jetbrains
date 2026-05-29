package dev.ayuislands.accent.conflict

import com.intellij.openapi.extensions.PluginId
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AccentElementId
import org.jetbrains.annotations.TestOnly

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

    // Installed plugins don't change during a session, so the first detection is cached.
    // Volatile nullable `var` instead of `by lazy` to let tests reset the cache via
    // `resetCachedConflictsForTesting()` without reflection or Unsafe tricks.
    @Volatile
    private var cachedConflicts: List<ConflictEntry>? = null

    private fun getCachedConflicts(): List<ConflictEntry> =
        cachedConflicts ?: synchronized(this) {
            cachedConflicts ?: computeConflicts().also { cachedConflicts = it }
        }

    private fun computeConflicts(): List<ConflictEntry> =
        entries.filter { entry ->
            val pluginId = PluginId.getId(entry.pluginId)
            // `findLoadedPlugin` returns null for both not-installed and
            // installed-but-disabled plugins — exactly the conflict semantics
            // we want (a disabled plugin is not active and is not a conflict).
            AyuPlugin.findLoadedPlugin(pluginId) != null
        }

    fun detectConflicts(): List<ConflictEntry> = getCachedConflicts()

    fun getConflictFor(elementId: AccentElementId): ConflictEntry? =
        getCachedConflicts()
            .firstOrNull { elementId in it.affectedElements }

    fun isCodeGlanceProDetected(): Boolean = getCachedConflicts().any { it.pluginId == "com.nasller.CodeGlancePro" }

    fun isIndentRainbowDetected(): Boolean = getCachedConflicts().any { it.pluginId == "indent-rainbow.indent-rainbow" }

    @TestOnly
    internal fun resetCachedConflictsForTesting() {
        cachedConflicts = null
    }
}
