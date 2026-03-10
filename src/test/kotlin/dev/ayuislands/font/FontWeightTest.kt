package dev.ayuislands.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FontWeightTest {
    @Test
    fun `fromName resolves all entries`() {
        for (weight in FontWeight.entries) {
            assertEquals(weight, FontWeight.fromName(weight.name))
        }
    }

    @Test
    fun `fromName with null falls back to REGULAR`() {
        assertEquals(FontWeight.REGULAR, FontWeight.fromName(null))
    }

    @Test
    fun `fromName with unknown string falls back to REGULAR`() {
        assertEquals(FontWeight.REGULAR, FontWeight.fromName("UNKNOWN"))
    }

    @Test
    fun `fromName with empty string falls back to REGULAR`() {
        assertEquals(FontWeight.REGULAR, FontWeight.fromName(""))
    }

    @Test
    fun `REGULAR has empty subFamily`() {
        assertEquals("", FontWeight.REGULAR.subFamily)
    }

    @Test
    fun `non-REGULAR weights have non-empty subFamily`() {
        for (weight in FontWeight.entries) {
            if (weight != FontWeight.REGULAR) {
                assertTrue(weight.subFamily.isNotEmpty(), "${weight.name} should have a subFamily")
            }
        }
    }

    @Test
    fun `all weights have positive textAttributeValue`() {
        for (weight in FontWeight.entries) {
            assertTrue(weight.textAttributeValue > 0f, "${weight.name} textAttributeValue should be positive")
        }
    }

    @Test
    fun `weights are ordered by textAttributeValue`() {
        val ordered = FontWeight.entries.map { it.textAttributeValue }
        for (i in 0 until ordered.size - 1) {
            val current = FontWeight.entries[i]
            val next = FontWeight.entries[i + 1]
            assertTrue(
                ordered[i] <= ordered[i + 1],
                "${current.name} (${ordered[i]}) should be <= ${next.name} (${ordered[i + 1]})",
            )
        }
    }

    @Test
    fun `entries count is 6`() {
        assertEquals(6, FontWeight.entries.size)
    }

    @Test
    fun `all weights have non-empty displayName`() {
        for (weight in FontWeight.entries) {
            assertTrue(weight.displayName.isNotEmpty(), "${weight.name} displayName should not be empty")
        }
    }
}
