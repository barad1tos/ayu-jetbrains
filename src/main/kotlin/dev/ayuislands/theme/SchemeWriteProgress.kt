package dev.ayuislands.theme

internal enum class SchemeWritePhase { PREPARED, WRITING, APPLIED, RESTORING, RESTORED, FOREIGN }

/** Immutable component progress; exact captured payloads remain separate in SchemeEntryRecord. */
internal class SchemeWriteProgress(
    val phase: SchemeWritePhase,
    val expected: SchemeAdmissionFacts,
    val destination: SchemeAdmissionFacts,
)
