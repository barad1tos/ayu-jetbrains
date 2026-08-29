package dev.ayuislands.integration

import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxKeyRoleRegistry
import dev.ayuislands.syntax.effectivePrimitive

internal enum class SyntaxEvidenceOrigin {
    LEXICAL_TOKEN,
    PREVIEW_OCCURRENCE,
    SEMANTIC_DESCRIPTOR,
}

internal object SyntaxEvidenceProvenance {
    fun classify(
        lexicalKeys: Set<String>,
        previewOccurrenceKeys: Set<String>,
        descriptorKeys: Set<String>,
    ): Map<PrimitiveCategory, Set<SyntaxEvidenceOrigin>> {
        val origins = linkedMapOf<PrimitiveCategory, MutableSet<SyntaxEvidenceOrigin>>()
        origins.add(lexicalKeys, SyntaxEvidenceOrigin.LEXICAL_TOKEN)
        origins.add(previewOccurrenceKeys, SyntaxEvidenceOrigin.PREVIEW_OCCURRENCE)
        origins.add(descriptorKeys, SyntaxEvidenceOrigin.SEMANTIC_DESCRIPTOR)
        return origins.mapValues { (_, values) -> values.toSet() }
    }

    private fun MutableMap<PrimitiveCategory, MutableSet<SyntaxEvidenceOrigin>>.add(
        keyNames: Set<String>,
        origin: SyntaxEvidenceOrigin,
    ) {
        keyNames.forEach { keyName ->
            val primitive = SyntaxKeyRoleRegistry.classify(keyName).effectivePrimitive ?: return@forEach
            getOrPut(primitive, ::linkedSetOf).add(origin)
        }
    }
}
