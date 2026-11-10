package dev.ayuislands.studio.migration

import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList

internal object StudioFieldMapping {
    private val owners = StudioFieldCatalog.fields.associate { LegacyFieldKey(it.source, it.name) to it.owner }

    fun select(
        fields: StudioFields,
        owner: StudioFieldOwner,
    ): StudioFields = StudioFields(StudioList(fields.records.filter { owners[it.key] == owner }))

    fun appearance(fields: StudioFields): AppearanceSnapshot =
        AppearanceSnapshot(
            accent = select(fields, StudioFieldOwner.ACCENT),
            font = select(fields, StudioFieldOwner.FONT),
            glow = select(fields, StudioFieldOwner.GLOW),
            syntax = select(fields, StudioFieldOwner.SYNTAX),
            vcs = select(fields, StudioFieldOwner.VCS),
        )

    fun copyAppearance(appearance: AppearanceSnapshot): AppearanceSnapshot =
        appearance.copy(
            accent = copyFields(appearance.accent),
            font = copyFields(appearance.font),
            glow = copyFields(appearance.glow),
            syntax = copyFields(appearance.syntax),
            vcs = copyFields(appearance.vcs),
        )

    private fun copyFields(fields: StudioFields): StudioFields =
        fields.copy(
            records = StudioList(fields.records),
            extensions = StudioList(fields.extensions),
        )
}
