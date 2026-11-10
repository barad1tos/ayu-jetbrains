package dev.ayuislands.accent

import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.settings.AyuIslandsState

/**
 * Decides viewport changes from explicit observations; no service lookup or native setter occurs
 * here.
 */
internal data class CgpPreparation(
    val before: CgpViewport,
    val after: CgpViewport?,
    val baseline: CgpViewport?,
    val action: IntegrationAction,
) {
    fun update(state: AyuIslandsState) {
        when (action) {
            IntegrationAction.KEEP -> Unit

            IntegrationAction.SUSPEND -> {
                state.cgpOwnership = IntegrationOwnership.SUSPENDED.name
            }

            IntegrationAction.RESTORE -> {
                clear(state)
            }

            IntegrationAction.APPLY -> {
                baseline?.let {
                    state.cgpBaseColor = it.color
                    state.cgpBaseBorder = it.border
                    state.cgpBaseThickness = it.thickness
                }
                val target = checkNotNull(after)
                state.cgpAppliedColor = target.color
                state.cgpAppliedBorder = target.border
                state.cgpAppliedThickness = target.thickness
                state.cgpOwnership = IntegrationOwnership.OWNED.name
            }
        }
    }

    companion object {
        private const val BORDER_THICKNESS = 1

        fun isEnabled(
            state: AyuIslandsState,
            context: AccentContext?,
        ): Boolean =
            state.cgpIntegrationEnabled &&
                (context != AccentContext.External || state.isExternalCodeGlanceProAllowed())

        fun target(accentHex: String): CgpViewport {
            val color = accentHex.removePrefix("#")
            return CgpViewport(color, color, BORDER_THICKNESS)
        }

        fun capture(
            state: AyuIslandsState,
            current: CgpViewport,
            requested: CgpViewport?,
        ): CgpPreparation {
            val ownership = IntegrationOwnership.fromName(state.cgpOwnership)
            val base = state.cgpBaseViewport()
            if (ownership == IntegrationOwnership.SUSPENDED) {
                return CgpPreparation(current, null, base, IntegrationAction.KEEP)
            }
            if (ownership == IntegrationOwnership.UNOWNED) {
                val action = if (requested == null) IntegrationAction.KEEP else IntegrationAction.APPLY
                return CgpPreparation(current, requested, current, action)
            }
            val invalidBase =
                base == null && (requested == null || ownership == IntegrationOwnership.RECOVERY_PENDING)
            if (current != applied(state) || invalidBase) {
                return CgpPreparation(current, null, base, IntegrationAction.SUSPEND)
            }
            val action = if (requested == null) IntegrationAction.RESTORE else IntegrationAction.APPLY
            return CgpPreparation(current, requested ?: base, base, action)
        }

        fun applied(state: AyuIslandsState): CgpViewport? =
            CgpViewport(
                state.cgpAppliedColor ?: return null,
                state.cgpAppliedBorder ?: return null,
                state.cgpAppliedThickness,
            )

        fun migrate(state: AyuIslandsState): Boolean {
            if (state.isCgpOwnershipMigrated) return false
            state.isCgpOwnershipMigrated = true
            if (
                !state.cgpIntegrationEnabled ||
                IntegrationOwnership.fromName(state.cgpOwnership) != IntegrationOwnership.UNOWNED
            ) {
                return false
            }
            state.cgpOwnership = IntegrationOwnership.SUSPENDED.name
            return true
        }

        fun prepareExplicitEnable(state: AyuIslandsState) {
            state.isCgpOwnershipMigrated = true
            if (IntegrationOwnership.fromName(state.cgpOwnership) == IntegrationOwnership.SUSPENDED) {
                clear(state)
            }
        }

        fun clear(state: AyuIslandsState) {
            state.cgpOwnership = IntegrationOwnership.UNOWNED.name
            state.cgpBaseColor = null
            state.cgpBaseBorder = null
            state.cgpBaseThickness = 0
            state.cgpAppliedColor = null
            state.cgpAppliedBorder = null
            state.cgpAppliedThickness = 0
        }
    }
}
