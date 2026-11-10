package dev.ayuislands.studio.session

import dev.ayuislands.font.FontApplyOrigin
import dev.ayuislands.font.FontOwnershipPolicy
import dev.ayuislands.font.FontOwnershipRecord
import dev.ayuislands.font.FontOwnershipStatus
import dev.ayuislands.font.FontOwnershipTransition
import dev.ayuislands.font.FontPreferencePolicy
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.NativeResolution
import dev.ayuislands.studio.model.NativeSurfaceChange

/** Managed consent follows whole editor/console surfaces; attempt rollback remains field-specific. */
internal object StudioFontPolicy {
    fun change(
        identity: String,
        record: FontOwnershipRecord?,
        current: FontSnapshot,
        target: FontSnapshot,
    ): NativeSurfaceChange? {
        require(identity.isNotBlank())
        val owned = record ?: return null
        val needsReview =
            owned.status == FontOwnershipStatus.SUSPENDED ||
                owned.status == FontOwnershipStatus.OWNED &&
                !FontPreferencePolicy.matches(current, owned.applied)
        if (!needsReview) return null
        return NativeSurfaceChange(
            EffectOwner.FONT,
            identity,
            FieldValue.Present(StudioFontCodec.encode(owned.applied)),
            FieldValue.Present(StudioFontCodec.encode(current)),
            FieldValue.Present(StudioFontCodec.encode(target)),
        )
    }

    fun apply(
        identity: String,
        record: FontOwnershipRecord?,
        current: FontSnapshot,
        target: FontSnapshot,
        choices: List<NativeResolution>,
    ): FontOwnershipTransition {
        val conflict = change(identity, record, current, target)
        val reviewed =
            if (conflict == null) {
                null
            } else {
                checkNotNull(choices.singleOrNull { it.change == conflict }) { "Font surface needs a fresh review" }
            }
        val authorized =
            if (reviewed?.choice == ConflictChoice.USE_MINE) {
                // Explicit reacquisition follows v2 release/re-enable without changing any stored preferences.
                requireNotNull(record).copy(status = FontOwnershipStatus.RELEASED)
            } else {
                record
            }
        return FontOwnershipPolicy.apply(authorized, current, target, FontApplyOrigin.EXPLICIT)
    }
}
