package dev.ayuislands.theme

import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry

/** Both payloads are captured before acknowledgement; progress never replaces this evidence. */
internal class SchemeSeedRecord(
    val entry: SchemeEntry,
    val before: SchemeCellFacts,
    val planned: SchemeCellFacts,
)

internal enum class SchemeSeedState { PENDING, WRITING, SETTLED, FOREIGN }

/** A settled seed is intermediate owned state, not proof that rollback has completed. */
internal class SchemeSeedObservation(
    val state: SchemeSeedState,
    val expected: SchemeCellFacts,
    val hasReturnedNormally: Boolean,
)
