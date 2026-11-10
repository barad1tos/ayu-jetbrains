package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SystemAccentPolicy

/** Global compatibility policy gates the profile choice without replacing it. */
internal object StudioAccentPolicy {
    fun followsSystem(
        appearance: AppearanceSnapshot,
        policy: SystemAccentPolicy,
    ): Boolean =
        when (policy) {
            SystemAccentPolicy.FORCE_ON -> true
            SystemAccentPolicy.FORCE_OFF -> false
            SystemAccentPolicy.BY_PROFILE ->
                appearance.accent.records
                    .single { it.key.name == "followSystemAccent" }
                    .value == RawValue.BooleanValue(true)
        }
}
