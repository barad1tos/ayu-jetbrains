package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Canonical [DefaultActionGroup] for the quick actions surface (D-14b). Both
 * the popup's quick-actions row (Wave 4) and the chip's right-click context
 * menu (Wave 3) MUST consume this group — D-14b forbids divergence between
 * the two surfaces; divergence is locked by
 * `QuickSwitcherActionParityTest`.
 *
 * Order is fixed: `Pin -> Random -> Lighter -> Darker -> Copy Hex`. Each
 * [build] call returns a NEW [DefaultActionGroup] instance so two surfaces
 * never share mutable group state (would otherwise cause double-fire when
 * the popup row and context menu both consumed the same group).
 */
object QuickSwitcherActionGroup {
    fun build(): DefaultActionGroup =
        DefaultActionGroup().apply {
            add(PinAccentAction())
            add(RandomAccentAction())
            add(LighterAccentAction())
            add(DarkerAccentAction())
            add(CopyHexAction())
        }
}
