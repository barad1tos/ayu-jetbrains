package dev.ayuislands.accent.conflict

import dev.ayuislands.accent.AccentElementId

enum class ConflictType { BLOCK, INTEGRATE }

data class ConflictEntry(
    val pluginDisplayName: String,
    val detectionClassName: String,
    val affectedElements: Set<AccentElementId>,
    val type: ConflictType,
)

object ConflictRegistry {
    private val entries =
        listOf(
            ConflictEntry(
                pluginDisplayName = "Atom Material Icons",
                detectionClassName = "com.mallowigi.config.AtomSettingsConfigurable",
                affectedElements = setOf(AccentElementId.CHECKBOXES),
                type = ConflictType.BLOCK,
            ),
            ConflictEntry(
                pluginDisplayName = "CodeGlance Pro",
                detectionClassName = "com.nasller.codeglance.config.CodeGlanceConfigService",
                affectedElements = emptySet(),
                type = ConflictType.INTEGRATE,
            ),
        )

    // Cached: installed plugins don't change during a session, so Class.forName()
    // lookups only need to happen once (was up to 16+ per accent change)
    private val cachedConflicts: List<ConflictEntry> by lazy {
        entries.filter { entry ->
            try {
                Class.forName(entry.detectionClassName)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }
    }

    fun detectConflicts(): List<ConflictEntry> = cachedConflicts

    fun getConflictFor(elementId: AccentElementId): ConflictEntry? =
        cachedConflicts
            .firstOrNull { elementId in it.affectedElements }

    fun isCodeGlanceProDetected(): Boolean = cachedConflicts.any { it.type == ConflictType.INTEGRATE }
}
