package dev.ayuislands.integration

import dev.ayuislands.syntax.PrimitiveCategory.FUNCTION_DECL
import dev.ayuislands.syntax.PrimitiveCategory.KEYWORD
import dev.ayuislands.syntax.PrimitiveCategory.PARAMETER
import kotlin.test.Test
import kotlin.test.assertEquals

class SyntaxEvidenceProvenanceTest {
    @Test
    fun `each primitive records only the evidence origins that observed it`() {
        val origins =
            SyntaxEvidenceProvenance.classify(
                lexicalKeys = setOf("SWIFT.KEYWORD"),
                previewOccurrenceKeys = setOf("SWIFT.FUNCTION_DECLARATION"),
                descriptorKeys = setOf("SWIFT.PARAMETER"),
            )

        assertEquals(setOf(SyntaxEvidenceOrigin.LEXICAL_TOKEN), origins.getValue(KEYWORD))
        assertEquals(setOf(SyntaxEvidenceOrigin.PREVIEW_OCCURRENCE), origins.getValue(FUNCTION_DECL))
        assertEquals(setOf(SyntaxEvidenceOrigin.SEMANTIC_DESCRIPTOR), origins.getValue(PARAMETER))
    }

    @Test
    fun `multiple observations preserve every origin without duplication`() {
        val origins =
            SyntaxEvidenceProvenance.classify(
                lexicalKeys = setOf("SWIFT.KEYWORD"),
                previewOccurrenceKeys = setOf("SWIFT.KEYWORD"),
                descriptorKeys = setOf("SWIFT.KEYWORD"),
            )

        assertEquals(SyntaxEvidenceOrigin.entries.toSet(), origins.getValue(KEYWORD))
    }
}
