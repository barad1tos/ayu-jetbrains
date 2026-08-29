package dev.ayuislands.integration

import dev.ayuislands.syntax.PrimitiveCategory.KEYWORD
import dev.ayuislands.syntax.PrimitiveCategory.OPERATOR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntaxContractMatrixTest {
    @Test
    fun `matrix diagnoses each unsupported contract transition with an action`() {
        val matrix =
            SyntaxContractMatrix.build(
                listOf(
                    LanguageContractEvidence(
                        language = "Swift",
                        advertised = true,
                        existing = setOf(KEYWORD, OPERATOR),
                        declared = setOf(KEYWORD),
                        previewed = setOf(KEYWORD),
                        verified = setOf(KEYWORD),
                    ),
                    LanguageContractEvidence(
                        language = "Dart",
                        advertised = true,
                    ),
                ),
            )

        val swift = matrix.languages.single { it.language == "Swift" }
        assertEquals(setOf(OPERATOR), swift.undeclaredImplementations)
        assertEquals(
            listOf("Add language-owned native evidence for Operator before declaring Swift tuning."),
            swift.actions,
        )
        assertFalse(swift.hasStructuralGap)
        assertFalse(swift.isComplete)

        val dart = matrix.languages.single { it.language == "Dart" }
        assertTrue(dart.hasUnsupportedClaim)
        assertTrue(dart.actions.single().contains("remove the support claim"))
        assertFalse(dart.isComplete)
    }

    @Test
    fun `matrix keeps declaration preview and verification gaps independent`() {
        val matrix =
            SyntaxContractMatrix.build(
                listOf(
                    LanguageContractEvidence(
                        language = "Kotlin",
                        advertised = true,
                        existing = setOf(KEYWORD, OPERATOR),
                        declared = setOf(KEYWORD, OPERATOR),
                        previewed = setOf(KEYWORD),
                        verified = emptySet(),
                    ),
                ),
            )

        val kotlin = matrix.languages.single()
        assertEquals(setOf(OPERATOR), kotlin.unpreviewedDeclarations)
        assertEquals(setOf(KEYWORD), kotlin.unverifiedPreviews)
        assertEquals(
            listOf(
                "Add Operator to the Kotlin preview sample.",
                "Install the official Kotlin plugin and verify Keyword through the native preview contract.",
            ),
            kotlin.actions,
        )
    }

    @Test
    fun `matrix flags declared controls without effective implementation`() {
        val matrix =
            SyntaxContractMatrix.build(
                listOf(
                    LanguageContractEvidence(
                        language = "Java",
                        advertised = true,
                        existing = setOf(KEYWORD),
                        declared = setOf(KEYWORD, OPERATOR),
                        previewed = setOf(KEYWORD, OPERATOR),
                        verified = setOf(KEYWORD, OPERATOR),
                    ),
                ),
            )

        val java = matrix.languages.single()
        assertEquals(setOf(OPERATOR), java.unimplementedDeclarations)
        assertEquals(listOf("Add effective Operator actuation for Java."), java.actions)
        assertFalse(java.isComplete)
    }
}
