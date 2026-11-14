package dev.ayuislands.studio.fields

import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList

internal object StudioAppearanceFields {
    fun fields(appearance: AppearanceSnapshot): List<StudioField> =
        listOf(
            appearance.accent,
            appearance.font,
            appearance.glow,
            appearance.syntax,
            appearance.vcs,
        ).flatMap { it.records }

    fun field(
        appearance: AppearanceSnapshot,
        key: LegacyFieldKey,
    ): StudioField = requireNotNull(fields(appearance).singleOrNull { it.key == key }) { "Unknown appearance field" }

    fun replace(
        appearance: AppearanceSnapshot,
        replacement: StudioField,
    ): AppearanceSnapshot =
        appearance.copy(
            accent = replace(appearance.accent, replacement),
            font = replace(appearance.font, replacement),
            glow = replace(appearance.glow, replacement),
            syntax = replace(appearance.syntax, replacement),
            vcs = replace(appearance.vcs, replacement),
        )

    fun value(
        appearance: AppearanceSnapshot,
        address: FieldAddress,
    ): FieldValue = StudioFieldValues.value(field(appearance, address.field), address.part)

    private fun replace(
        fields: StudioFields,
        replacement: StudioField,
    ): StudioFields =
        if (fields.records.none { it.key == replacement.key }) {
            fields
        } else {
            fields.copy(
                records =
                    StudioList(
                        fields.records.map {
                            if (it.key ==
                                replacement.key
                            ) {
                                replacement
                            } else {
                                it
                            }
                        },
                    ),
            )
        }
}
