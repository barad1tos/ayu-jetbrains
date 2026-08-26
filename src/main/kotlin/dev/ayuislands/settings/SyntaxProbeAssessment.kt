package dev.ayuislands.settings

import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.PrimitiveCategory

internal object SyntaxProbeAssessment {
    fun assess(
        specification: LanguageSpecification,
        generation: Long,
        evidence: List<HighlightEvidence>,
    ): SyntaxProbeResult {
        val keysByPrimitive = mergeKeys(specification.storageId, evidence)
        val declaredCells =
            specification.preview.files
                .flatMapTo(linkedSetOf()) { it.demonstratedCategories }
        val confirmedCells = declaredCells.intersect(keysByPrimitive.keys)
        val missingCells = declaredCells - confirmedCells
        val conditionalCells = missingCells.intersect(specification.semanticOnlyCategories)
        val mismatchedCells = missingCells - conditionalCells
        if (mismatchedCells.isNotEmpty()) {
            return SyntaxProbeResult.Mismatch(
                languageId = specification.storageId,
                generation = generation,
                confirmedCells = confirmedCells,
                mismatches = mismatchedCells.map { CapabilityMismatch(it, "No native preview span") },
            )
        }
        return SyntaxProbeResult.Confirmed(
            languageId = specification.storageId,
            generation = generation,
            evidence =
                SyntaxCapabilityEvidence(
                    languageId = specification.storageId,
                    confirmedCells = confirmedCells,
                    keysByPrimitive = keysByPrimitive,
                    conditionalAbsences =
                        conditionalCells.map {
                            ConditionalAbsence(it, "Requires semantic highlighting")
                        },
                ),
        )
    }

    private fun mergeKeys(
        languageId: String,
        evidence: List<HighlightEvidence>,
    ): Map<PrimitiveCategory, Set<String>> =
        evidence
            .asSequence()
            .filter { it.languageId == languageId }
            .flatMap { it.keysByPrimitive.asSequence() }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, keySets) -> keySets.flatten().toSet() }
}
