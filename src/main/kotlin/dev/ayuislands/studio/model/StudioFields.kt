package dev.ayuislands.studio.model

internal enum class SourcePresence { ABSENT, NULL, VALUE }

internal data class FieldOrigin(
    val presence: SourcePresence,
    val lexeme: StudioBytes? = null,
)

internal data class LegacyFieldKey(
    val source: LegacyStore,
    val name: String,
)

internal data class StudioField(
    val key: LegacyFieldKey,
    val value: RawValue,
    val origin: FieldOrigin,
)

internal data class OpaqueRecord(
    val key: String,
    val bytes: StudioBytes,
)

/** Ordered, complete values; source evidence is independent of the interpreted value. */
internal data class StudioFields(
    val records: StudioList<StudioField>,
    val extensions: StudioList<OpaqueRecord> = StudioList(emptyList()),
) {
    init {
        require(records.map { it.key }.toSet().size == records.size) { "Duplicate field identity" }
    }
}

internal data class AppearanceSnapshot(
    val accent: StudioFields,
    val font: StudioFields,
    val glow: StudioFields,
    val syntax: StudioFields,
    val vcs: StudioFields,
) {
    init {
        requireDomain(accent, StudioFieldOwner.ACCENT)
        requireDomain(font, StudioFieldOwner.FONT)
        requireDomain(glow, StudioFieldOwner.GLOW)
        requireDomain(syntax, StudioFieldOwner.SYNTAX)
        requireDomain(vcs, StudioFieldOwner.VCS)
    }
}

private fun requireDomain(
    fields: StudioFields,
    owner: StudioFieldOwner,
) {
    val expected =
        StudioFieldCatalog.fields
            .filter { it.owner == owner }
            .map { LegacyFieldKey(it.source, it.name) }
            .toSet()
    require(fields.records.map { it.key }.toSet() == expected) { "Incomplete or misplaced $owner fields" }
    fields.records.forEach(StudioField::requireKnownType)
}
