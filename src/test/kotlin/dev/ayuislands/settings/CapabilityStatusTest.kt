package dev.ayuislands.settings

import dev.ayuislands.syntax.PrimitiveCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityStatusTest {
    @Test
    fun `semantic dependency copy stays conditional and names affected controls`() {
        val state =
            SyntaxCapabilityState.Confirmed(
                languageId = "Kotlin",
                evidence =
                    SyntaxCapabilityEvidence(
                        languageId = "Kotlin",
                        confirmedCells = setOf(PrimitiveCategory.KEYWORD),
                        conditionalAbsences =
                            listOf(
                                ConditionalAbsence(
                                    PrimitiveCategory.OPERATOR,
                                    "Requires semantic highlighting",
                                ),
                            ),
                    ),
            )

        val presentation = requireNotNull(state.presentation())

        assertTrue(presentation.message.contains("depend on semantic highlighting"))
        assertTrue(presentation.message.contains("Operator"))
        assertTrue(presentation.message.contains("current Kotlin configuration"))
        assertEquals("Open Highlighting Settings", presentation.action)
    }

    @Test
    fun `incompatible copy exposes mismatch reason and concrete next step`() {
        val state =
            SyntaxCapabilityState.Incompatible(
                languageId = "Swift",
                confirmedCells = emptySet(),
                mismatches =
                    listOf(
                        CapabilityMismatch(
                            PrimitiveCategory.GENERICS,
                            "No native preview span",
                        ),
                    ),
            )

        val presentation = requireNotNull(state.presentation())

        assertTrue(presentation.message.contains("Generics: No native preview span"))
        assertTrue(presentation.message.contains("Update or enable Swift language support"))
        assertEquals("Retry", presentation.action)
    }
}
