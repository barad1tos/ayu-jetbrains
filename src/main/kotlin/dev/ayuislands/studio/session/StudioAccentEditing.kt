package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioField

/** Projects manual palette choices and edits only the selected variant in the draft. */
internal object StudioAccentEditing {
    fun stored(
        appearance: AppearanceSnapshot,
        variant: AyuVariant,
    ): String? = (field(appearance, variant).value as? RawValue.Text)?.value

    fun sample(
        appearance: AppearanceSnapshot,
        variant: AyuVariant,
    ): AccentHex = AccentHex.of(stored(appearance, variant)) ?: AccentHex.unsafeOf(variant.defaultAccent)

    fun choose(
        draft: DraftRecord,
        variant: AyuVariant,
        color: AccentHex,
    ): DraftRecord {
        if (matches(stored(draft.appearance, variant), color)) return draft
        val initial = field(draft.tracking.initial, variant)
        val originalColor = (initial.value as? RawValue.Text)?.value
        val replacement = if (matches(originalColor, color)) initial.value else RawValue.Text(color.value)
        return StudioDraftPolicy.edit(
            draft,
            FieldAddress(initial.key),
            FieldValue.Present(replacement),
            RotationAcceptanceOrigin.LOCAL_APPEARANCE_EDIT,
        )
    }

    private fun matches(
        stored: String?,
        color: AccentHex,
    ): Boolean = AccentHex.of(stored)?.value.equals(color.value, ignoreCase = true)

    private fun field(
        appearance: AppearanceSnapshot,
        variant: AyuVariant,
    ): StudioField {
        val name =
            when (variant) {
                AyuVariant.MIRAGE -> "mirageAccent"
                AyuVariant.DARK -> "darkAccent"
                AyuVariant.LIGHT -> "lightAccent"
            }
        return appearance.accent.records.single { it.key.name == name }
    }
}
