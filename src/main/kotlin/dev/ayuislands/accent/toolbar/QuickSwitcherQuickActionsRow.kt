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
 * Quick-actions row inside the quick-switcher popup: five 28x28 icon-only
 * [IconPillButton] pills with tooltips, in the canonical order
 * (Pin → Random → Lighter → Darker → Copy Hex).
 *
 * Icon choices (primary form, all verified present in 2025.1 `AllIcons.*`):
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
