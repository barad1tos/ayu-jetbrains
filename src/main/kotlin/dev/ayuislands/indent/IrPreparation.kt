package dev.ayuislands.indent

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.IntegrationAction
import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.settings.AyuIslandsState

/** Computes the existing palette policy without reading services or changing native settings. */
internal data class IrPreparation(
    val before: IrPalette,
    val after: IrPalette?,
    val baseline: IrPalette?,
    val action: IntegrationAction,
) {
    fun update(state: AyuIslandsState) {
        when (action) {
            IntegrationAction.KEEP -> Unit

            IntegrationAction.SUSPEND -> {
                state.irOwnership = IntegrationOwnership.SUSPENDED.name
            }

            IntegrationAction.RESTORE -> {
                clear(state)
            }

            IntegrationAction.APPLY -> {
                baseline?.let(state::storeIrBase)
                state.storeIrApplied(checkNotNull(after))
                state.irOwnership = IntegrationOwnership.OWNED.name
            }
        }
    }

    companion object {
        fun isEnabled(
            state: AyuIslandsState,
            context: AccentContext?,
        ): Boolean =
            state.irIntegrationEnabled &&
                (context != AccentContext.External || state.isExternalIndentRainbowAllowed())

        fun target(
            state: AyuIslandsState,
            palette: IndentPalette,
            type: String = "CUSTOM",
        ): IrPalette {
            val colors =
                palette.toColorStrings(
                    IndentPreset.resolveAlpha(state.indentPresetName, state.indentCustomAlpha),
                    state.irErrorHighlightEnabled,
                )
            return IrPalette(type, colors.joinToString(", "), colors.size)
        }

        fun capture(
            state: AyuIslandsState,
            current: IrPalette,
            requested: IrPalette?,
        ): IrPreparation {
            val ownership = IntegrationOwnership.fromName(state.irOwnership)
            val base = state.irBasePalette()
            if (ownership == IntegrationOwnership.SUSPENDED) {
                return IrPreparation(current, null, base, IntegrationAction.KEEP)
            }
            if (ownership == IntegrationOwnership.UNOWNED) {
                val action = if (requested == null) IntegrationAction.KEEP else IntegrationAction.APPLY
                return IrPreparation(current, requested, current, action)
            }
            val applied =
                if (ownership == IntegrationOwnership.RECOVERY_PENDING) {
                    state.irPendingPalette()
                } else {
                    state.irAppliedPalette()
                }
            val invalidBase =
                base == null && (requested == null || ownership == IntegrationOwnership.RECOVERY_PENDING)
            if (current != applied || invalidBase) {
                return IrPreparation(current, null, base, IntegrationAction.SUSPEND)
            }
            val action = if (requested == null) IntegrationAction.RESTORE else IntegrationAction.APPLY
            return IrPreparation(current, requested ?: base, base, action)
        }

        fun migrate(state: AyuIslandsState): Boolean {
            if (state.isIrOwnershipMigrated) return false
            state.isIrOwnershipMigrated = true
            if (
                !state.irIntegrationEnabled ||
                IntegrationOwnership.fromName(state.irOwnership) != IntegrationOwnership.UNOWNED
            ) {
                return false
            }
            state.irOwnership = IntegrationOwnership.SUSPENDED.name
            return true
        }

        fun prepareExplicitEnable(state: AyuIslandsState) {
            state.isIrOwnershipMigrated = true
            if (IntegrationOwnership.fromName(state.irOwnership) == IntegrationOwnership.SUSPENDED) {
                clear(state)
            }
        }

        fun clear(state: AyuIslandsState) {
            state.irOwnership = IntegrationOwnership.UNOWNED.name
            state.irBaseType = null
            state.irBasePalette = null
            state.irBaseColorCount = 0
            state.irAppliedType = null
            state.irAppliedPalette = null
            state.irAppliedColorCount = 0
        }
    }
}
