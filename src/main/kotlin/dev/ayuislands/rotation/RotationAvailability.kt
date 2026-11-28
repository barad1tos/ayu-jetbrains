package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.StudioList

internal enum class RotationPauseReason { FOLLOW_SYSTEM, NO_PRO, EXTERNAL_THEME, TARGET_UNAVAILABLE, RECOVERY_PENDING }

/** Independent runtime observations. These gates never modify the accepted preferences. */
internal data class RotationAvailability(
    val entitlement: LicenseEntitlement,
    val effectiveFollowSystem: Boolean,
    val variant: AyuVariant?,
    val targetValid: Boolean,
    val recoveryPending: Boolean,
) {
    val pauseReasons: StudioList<RotationPauseReason> =
        StudioList(
            buildList {
                if (effectiveFollowSystem) add(RotationPauseReason.FOLLOW_SYSTEM)
                if (entitlement != LicenseEntitlement.LICENSED) add(RotationPauseReason.NO_PRO)
                if (variant == null) add(RotationPauseReason.EXTERNAL_THEME)
                if (!targetValid) add(RotationPauseReason.TARGET_UNAVAILABLE)
                if (recoveryPending) add(RotationPauseReason.RECOVERY_PENDING)
            },
        )

    fun retainDefinitiveEntitlement(previous: RotationAvailability): RotationAvailability =
        if (entitlement == LicenseEntitlement.UNKNOWN) copy(entitlement = previous.entitlement) else this
}
