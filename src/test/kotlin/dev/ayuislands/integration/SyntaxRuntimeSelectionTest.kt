package dev.ayuislands.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyntaxRuntimeSelectionTest {
    @Test
    fun `selection resolves the requested catalog runtime`() {
        val selected =
            SyntaxRuntimeSelection.select { property ->
                assertEquals("syntaxRuntimeId", property)
                "idea-community"
            }

        assertEquals("idea-community", selected.id)
    }

    @Test
    fun `missing and unknown runtime ids fail with accepted ids`() {
        val missing = assertFailsWith<IllegalArgumentException> { SyntaxRuntimeSelection.select { null } }
        val unknown = assertFailsWith<IllegalArgumentException> { SyntaxRuntimeSelection.select { "missing" } }

        assertTrue(missing.message.orEmpty().contains("syntaxRuntimeId"))
        assertTrue(unknown.message.orEmpty().contains("idea-community"))
        assertTrue(unknown.message.orEmpty().contains("noctule-swift"))
    }
}
