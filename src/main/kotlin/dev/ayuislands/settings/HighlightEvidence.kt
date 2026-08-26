package dev.ayuislands.settings

import dev.ayuislands.syntax.PrimitiveCategory

internal data class HighlightEvidence(
    val languageId: String,
    val lexicalKeys: Set<String>,
    val supplementalKeys: Set<String>,
    val keysByPrimitive: Map<PrimitiveCategory, Set<String>>,
) {
    val categories: Set<PrimitiveCategory>
        get() = keysByPrimitive.keys
}
