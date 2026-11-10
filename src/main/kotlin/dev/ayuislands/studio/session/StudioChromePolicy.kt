package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.ChromeTintSnapshot
import dev.ayuislands.licensing.LicenseEntitlement

/** Detached candidate facts and already observed native context; no service or settings reader. */
internal data class StudioChromeInput(
    val context: AccentContext?,
    val settings: ChromeTintSnapshot,
    val externalAllowed: Boolean,
    val forceOverrides: List<String>,
    val entitlement: LicenseEntitlement,
)

internal enum class StudioChromeAction { TINT, CLEAR, SKIP }

/** Runtime gates leave every raw granular choice intact. Apply-only capabilities are observed by the surface. */
internal object StudioChromePolicy {
    fun action(
        input: StudioChromeInput,
        id: AccentElementId,
        hasConflict: Boolean,
        isOwned: Boolean,
    ): StudioChromeAction {
        require(id.group == AccentGroup.CHROME) { "Not a Chrome element: $id" }
        require(input.entitlement != LicenseEntitlement.UNKNOWN) { "Chrome entitlement is unconfirmed" }
        if (input.context == null) return StudioChromeAction.CLEAR
        val allowedContext =
            input.context is AccentContext.Ayu || (input.context == AccentContext.External && input.externalAllowed)
        val allowedPreference = input.settings.isToggleEnabled(id) && (!hasConflict || id.name in input.forceOverrides)
        if (allowedContext && allowedPreference && input.entitlement == LicenseEntitlement.LICENSED) {
            return StudioChromeAction.TINT
        }
        return if (input.context is AccentContext.Ayu || isOwned) StudioChromeAction.CLEAR else StudioChromeAction.SKIP
    }
}
