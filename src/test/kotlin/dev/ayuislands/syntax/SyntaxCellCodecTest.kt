package dev.ayuislands.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyntaxCellCodecTest {
    @Test
    fun `known updates replace only exact composite keys and retain opaque cells`() {
        val original =
            linkedMapOf(
                "Swift|KEYWORD" to "70",
                "Swift|STATIC_FIELD" to "35",
                "Swift|FUTURE_PRIMITIVE" to "19",
                "Future Language|KEYWORD" to "81",
                "malformed" to "opaque",
            )
        val updates: Map<SyntaxCellId, Int?> =
            mapOf(
                SyntaxCellId("Swift", "KEYWORD") to 64,
                SyntaxCellId("Swift", "COMMENT") to 28,
                SyntaxCellId("Swift", "STATIC_FIELD") to null,
            )

        val updated = SyntaxCellCodec.updateKnownCells(original, updates, Int::toString)

        assertEquals("64", updated["Swift|KEYWORD"])
        assertEquals("28", updated["Swift|COMMENT"])
        assertEquals(null, updated["Swift|STATIC_FIELD"])
        assertEquals("19", updated["Swift|FUTURE_PRIMITIVE"])
        assertEquals("81", updated["Future Language|KEYWORD"])
        assertEquals("opaque", updated["malformed"])
    }

    @Test
    fun `empty updates preserve iteration order and exact contents`() {
        val original = linkedMapOf("Kotlin|KEYWORD" to "50", "opaque" to "future")

        assertEquals(
            original,
            SyntaxCellCodec.updateKnownCells(original, emptyMap<SyntaxCellId, Int?>(), Int::toString),
        )
    }

    @Test
    fun `known removal mutates only exact requested cells`() {
        val stored =
            linkedMapOf(
                "Swift|KEYWORD" to "70",
                "Swift|FUTURE_PRIMITIVE" to "19",
                "Kotlin|KEYWORD" to "55",
            )

        SyntaxCellCodec.removeKnownCells(stored, setOf(SyntaxCellId("Swift", "KEYWORD")))

        assertEquals(
            linkedMapOf(
                "Swift|FUTURE_PRIMITIVE" to "19",
                "Kotlin|KEYWORD" to "55",
            ),
            stored,
        )
    }

    @Test
    fun `malformed or blank composite keys remain opaque`() {
        assertNull(SyntaxCellId.parse("malformed"))
        assertNull(SyntaxCellId.parse(" |KEYWORD"))
        assertNull(SyntaxCellId.parse("Swift| "))
        assertNull(SyntaxCellId.parse("Swift|KEYWORD|future"))
    }
}
