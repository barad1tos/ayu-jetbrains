package dev.ayuislands.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyleAxisTest {
    @Test
    fun `enum has exactly four entries`() {
        assertEquals(4, StyleAxis.entries.size)
    }

    @Test
    fun `entries match D-06 spec`() {
        val names = StyleAxis.entries.map { it.name }.toSet()
        assertEquals(
            setOf(
                "ITALIC_DECLARATIONS",
                "BOLD_TYPE_REFERENCES",
                "DIMMED_COMMENTS",
                "ITALIC_DOC_TAGS",
            ),
            names,
        )
    }

    @Test
    fun `displayName values are present and non-empty`() {
        StyleAxis.entries.forEach { axis ->
            assertTrue(axis.displayName.isNotBlank(), "displayName for $axis must be non-blank")
        }
    }

    @Test
    fun `description values are present and non-empty`() {
        StyleAxis.entries.forEach { axis ->
            assertTrue(axis.description.isNotBlank(), "description for $axis must be non-blank")
        }
    }

    @Test
    fun `displayName matches SYNTAX-04 labels exactly`() {
        assertEquals("Italic declarations", StyleAxis.ITALIC_DECLARATIONS.displayName)
        assertEquals("Bold type references", StyleAxis.BOLD_TYPE_REFERENCES.displayName)
        assertEquals("Dimmed comments", StyleAxis.DIMMED_COMMENTS.displayName)
        assertEquals("Italic doc tags", StyleAxis.ITALIC_DOC_TAGS.displayName)
    }
}
