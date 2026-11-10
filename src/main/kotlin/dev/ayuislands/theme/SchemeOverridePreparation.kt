package dev.ayuislands.theme

import dev.ayuislands.theme.EditorSchemeOverrides.OverrideState
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue

/** Captured owner facts; null comparison or baseline means the corresponding operation was not qualified. */
internal data class SchemeOverrideFacts(
    val owner: EditorSchemeOwner,
    val current: OverrideState?,
    val requested: SchemeValue?,
    val isManaged: Boolean,
    val baseline: SchemeValue?,
    val comparison: SchemeEquality?,
)

/** A null value preserves the captured native cell, including actual local absence. */
internal data class SchemeOverridePlan(
    val value: SchemeValue?,
    val state: OverrideState?,
    val result: OverrideWriteResult,
)

/** Detached native writes and the managed keys that retained foreign values. */
internal data class SchemeOverridePreparation(
    val batch: SchemeWriteBatch,
    val relinquished: Set<SchemeEntry>,
)
