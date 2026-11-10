package dev.ayuislands.theme

import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry

/** Immutable capture for encoding; mutable restoration progress never replaces these payloads. */
internal class SchemeEntryRecord(
    val entry: SchemeEntry,
    val before: SchemeAdmissionFacts,
    val planned: SchemeAdmissionFacts,
    val restored: SchemeAdmissionFacts,
    val released: SchemeAdmissionFacts?,
)
