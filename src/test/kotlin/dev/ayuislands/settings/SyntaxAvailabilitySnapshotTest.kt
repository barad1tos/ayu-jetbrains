package dev.ayuislands.settings

import dev.ayuislands.syntax.PrimitiveCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class SyntaxAvailabilitySnapshotTest {
    @Test
    fun `available categories intersect native evidence with actuation support`() {
        val snapshot =
            SyntaxAvailabilitySnapshot(
                nativeConfirmed = setOf(PrimitiveCategory.KEYWORD, PrimitiveCategory.OPERATOR),
                actuatedByLanguage = mapOf("Kotlin" to setOf(PrimitiveCategory.KEYWORD)),
            )

        assertEquals(setOf(PrimitiveCategory.KEYWORD), snapshot.availableFor("Kotlin"))
    }

    @Test
    fun `known empty actuation snapshot exposes no controls`() {
        val snapshot =
            SyntaxAvailabilitySnapshot(
                nativeConfirmed = setOf(PrimitiveCategory.KEYWORD),
                actuatedByLanguage = emptyMap(),
            )

        assertEquals(emptySet(), snapshot.availableFor("Kotlin"))
    }

    @Test
    fun `missing actuation snapshot preserves native evidence`() {
        val native = setOf(PrimitiveCategory.KEYWORD, PrimitiveCategory.OPERATOR)
        val snapshot = SyntaxAvailabilitySnapshot(nativeConfirmed = native)

        assertEquals(native, snapshot.availableFor("Kotlin"))
    }

    @Test
    fun `missing native and actuation snapshots fail open`() {
        val snapshot = SyntaxAvailabilitySnapshot()

        assertEquals(PrimitiveCategory.entries.toSet(), snapshot.availableFor("Kotlin"))
    }
}
