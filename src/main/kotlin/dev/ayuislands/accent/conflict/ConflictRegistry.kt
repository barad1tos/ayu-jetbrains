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

    fun detectConflicts(): List<ConflictEntry> =
        entries.filter { entry ->
            try {
                Class.forName(entry.detectionClassName)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }

    fun getConflictFor(elementId: AccentElementId): ConflictEntry? =
        detectConflicts()
            .firstOrNull { elementId in it.affectedElements }

    fun isCodeGlanceProDetected(): Boolean = detectConflicts().any { it.type == ConflictType.INTEGRATE }
}
