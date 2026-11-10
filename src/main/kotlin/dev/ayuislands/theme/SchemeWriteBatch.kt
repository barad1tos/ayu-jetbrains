package dev.ayuislands.theme

import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry

/** One scheme's retained writes; the caller journals each preparation before running its effects. */
internal interface SchemeWriteBatch {
    val records: List<SchemeEntryRecord>
    val seeds: List<SchemeSeedRecord>
    val seedProgress: List<SchemeSeedObservation>
    val deletions: Map<SchemeEntry, SchemeCellFacts>
    val deletionProgress: Map<SchemeEntry, SchemeDeletionState>
    val states: Map<SchemeEntry, AccentWriteState>
    val progress: Map<SchemeEntry, Map<SchemeComponent, SchemeWriteProgress>>

    fun apply(): Boolean

    /** Blockers belong to this exact scheme. They defer recovery without relinquishing ownership. */
    fun prepareRestore(blocked: Set<SchemeEntry> = emptySet())

    /** Returns false only when the next captured deletion stage needs its own acknowledgement. */
    fun restore(blocked: Set<SchemeEntry> = emptySet()): Boolean

    /** Called only after the enclosing appearance transaction commits. */
    fun accept()
}
