package dev.ayuislands.accent.toolbar

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.toolbar.actions.CopyHexAction
import dev.ayuislands.accent.toolbar.actions.DarkerAccentAction
import dev.ayuislands.accent.toolbar.actions.LighterAccentAction
import dev.ayuislands.accent.toolbar.actions.PinAccentAction
import dev.ayuislands.accent.toolbar.actions.RandomAccentAction
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.IconPillButton
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Wave-7 redesign of the quick-actions row per 48-REDESIGN-SPEC §3.6: five 28x28
 * icon-only [IconPillButton] pills with tooltips, in the canonical D-14b order
 * (Pin → Random → Lighter → Darker → Copy Hex). Replaces the Wave-4 wide
 * `JButton(label)` row.
 *
 * Action class invariant is unchanged — [QuickSwitcherActionParityTest] still
 * source-greps this file for the same constructor count + order. Only the
 * rendering surface (label-button → icon pill) changes.
 *
 * Icon choices per spec §3.6 table (primary form, all verified present in
 * 2025.1 `AllIcons.*`):
 *   - Pin     → `AllIcons.Actions.PinTab`
 *   - Random  → `AllIcons.Actions.Refresh`
 *   - Lighter → `AllIcons.General.ChevronUp`
 *   - Darker  → `AllIcons.General.ChevronDown`
 *   - CopyHex → `AllIcons.Actions.Copy`
 */
internal class QuickSwitcherQuickActionsRow(
    private val anchor: JComponent,
) {
    val component: JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(Density.ACTION_GAP), 0)).apply {
            isOpaque = false
            add(IconPillButton(PinAccentAction(), anchor, AllIcons.Actions.PinTab))
            add(IconPillButton(RandomAccentAction(), anchor, AllIcons.Actions.Refresh))
            add(IconPillButton(LighterAccentAction(), anchor, AllIcons.General.ChevronUp))
            add(IconPillButton(DarkerAccentAction(), anchor, AllIcons.General.ChevronDown))
            add(IconPillButton(CopyHexAction(), anchor, AllIcons.Actions.Copy))
        }
}
