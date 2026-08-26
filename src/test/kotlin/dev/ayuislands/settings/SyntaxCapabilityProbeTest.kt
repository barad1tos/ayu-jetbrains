package dev.ayuislands.settings

import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.PrimitiveCategory.KEYWORD
import dev.ayuislands.syntax.PrimitiveCategory.OPERATOR
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SyntaxCapabilityProbeTest {
    @Test
    fun `assessment aggregates keys from every preview file into confirmed evidence`() {
        val specification = swiftSpecification()
        val evidence =
            listOf(
                highlightEvidence(KEYWORD, "SWIFT.KEYWORD"),
                highlightEvidence(OPERATOR, "SWIFT.OPERATION_SIGN"),
            )

        val result = SyntaxProbeAssessment.assess(specification, generation = 9, evidence)

        val confirmed = assertIs<SyntaxProbeResult.Confirmed>(result)
        assertEquals(setOf(KEYWORD, OPERATOR), confirmed.evidence.confirmedCells)
        assertEquals(setOf("SWIFT.KEYWORD"), confirmed.evidence.keysByPrimitive[KEYWORD])
        assertEquals(setOf("SWIFT.OPERATION_SIGN"), confirmed.evidence.keysByPrimitive[OPERATOR])
        assertEquals(
            SyntaxCapabilityEvent.ProbeConfirmed("Swift", 9, confirmed.evidence),
            confirmed.toEvent(),
        )
    }

    @Test
    fun `assessment exposes only confirmed subset when declared preview evidence is missing`() {
        val specification = swiftSpecification()

        val result =
            SyntaxProbeAssessment.assess(
                specification,
                generation = 3,
                evidence = listOf(highlightEvidence(KEYWORD, "SWIFT.KEYWORD")),
            )

        val mismatch = assertIs<SyntaxProbeResult.Mismatch>(result)
        assertEquals(setOf(KEYWORD), mismatch.confirmedCells)
        assertEquals(listOf(CapabilityMismatch(OPERATOR, "No native preview span")), mismatch.mismatches)
        assertEquals(
            SyntaxCapabilityEvent.ProbeMismatch(
                languageId = "Swift",
                generation = 3,
                confirmedCells = setOf(KEYWORD),
                mismatches = mismatch.mismatches,
            ),
            mismatch.toEvent(),
        )
    }

    @Test
    fun `assessment treats missing semantic-only cells as conditional availability`() {
        val specification =
            swiftSpecification().copy(
                semanticOnlyCategories = setOf(OPERATOR),
            )

        val result =
            SyntaxProbeAssessment.assess(
                specification,
                generation = 5,
                evidence = listOf(highlightEvidence(KEYWORD, "SWIFT.KEYWORD")),
            )

        val confirmed = assertIs<SyntaxProbeResult.Confirmed>(result)
        assertEquals(setOf(KEYWORD), confirmed.evidence.confirmedCells)
        assertEquals(
            listOf(ConditionalAbsence(OPERATOR, "Requires semantic highlighting")),
            confirmed.evidence.conditionalAbsences,
        )
    }

    @Test
    fun `assessment rejects global fallback keys as per-language capability evidence`() {
        val specification = swiftSpecification()

        val result =
            SyntaxProbeAssessment.assess(
                specification,
                generation = 7,
                evidence =
                    listOf(
                        highlightEvidence(KEYWORD, "SWIFT.KEYWORD"),
                        highlightEvidence(OPERATOR, "DEFAULT_OPERATION_SIGN"),
                    ),
            )

        val mismatch = assertIs<SyntaxProbeResult.Mismatch>(result)
        assertEquals(setOf(KEYWORD), mismatch.confirmedCells)
        assertEquals(listOf(CapabilityMismatch(OPERATOR, "No native preview span")), mismatch.mismatches)
    }

    @Test
    fun `missing plugin result carries exact generic recovery instruction`() {
        val result =
            SyntaxProbeResult.MissingPlugin(
                languageId = "Swift",
                generation = 4,
                recovery = PluginRecovery(swiftSpecification().pluginRequirement),
            )

        assertEquals(PLUGIN_INSTALL_INSTRUCTION, result.recovery.instruction)
        assertEquals(
            SyntaxCapabilityEvent.ProbeMissingPlugin("Swift", 4, result.recovery),
            result.toEvent(),
        )
    }

    private fun swiftSpecification(): LanguageSpecification {
        val base = requireNotNull(SyntaxLanguageRegistry.findByStorageId("Swift"))
        val file = base.preview.files.single()
        return base.copy(
            preview =
                base.preview.copy(
                    files =
                        listOf(
                            file.copy(demonstratedCategories = setOf(KEYWORD)),
                            file.copy(fileName = "Operators.swift", demonstratedCategories = setOf(OPERATOR)),
                        ),
                ),
        )
    }

    private fun highlightEvidence(
        primitive: dev.ayuislands.syntax.PrimitiveCategory,
        keyName: String,
    ): HighlightEvidence =
        HighlightEvidence(
            languageId = "Swift",
            lexicalKeys = setOf(keyName),
            supplementalKeys = emptySet(),
            keysByPrimitive = mapOf(primitive to setOf(keyName)),
        )
}
