package dev.ayuislands.accent.toolbar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.settings.AyuIslandsSettings
import javax.swing.JComponent

/**
 * Toolbar widget that consolidates Ayu variant + accent + related toggles +
 * quick actions behind one chip in `MainToolbarRight`. The chip itself is FREE;
 * premium gating happens inside the popup body via
 * `.visibleIf { LicenseChecker.isLicensedOrGrace() }`.
 *
 * Visibility is gated by a two-conjunct predicate on every BGT [update] tick:
 *   1. Quick-switcher context must be active — [AccentContext.isQuickSwitcherActive].
 *   2. Settings toggle ON — `AyuIslandsState.quickSwitcherWidgetEnabled`
 *      (default ON).
 *
 * No license predicate at chip level: the chip surface itself stays free so
 * the user sees value before any paywall. Pattern J discipline —
 * single-source-of-truth predicate, no third state field consulted at this
 * layer.
 */
class QuickSwitcherWidgetAction :
    AnAction(),
    CustomComponentAction {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val state = AyuIslandsSettings.getInstance().state
        event.presentation.isEnabledAndVisible =
            AccentContext.isQuickSwitcherActive() &&
            state.quickSwitcherWidgetEnabled
    }

    override fun actionPerformed(event: AnActionEvent) {
        // No-op — the chip's MouseListener handles click routing directly. The platform
        // still calls this for keyboard activation; we leave it inert because there is no
        // meaningful "default" action (variant? accent? popup?). The popup must open from
        // a mouse coord anchor on the chip itself.
    }

    override fun createCustomComponent(
        presentation: Presentation,
        place: String,
    ): JComponent = QuickSwitcherChipComponent()

    override fun updateCustomComponent(
        component: JComponent,
        presentation: Presentation,
    ) {
        (component as? QuickSwitcherChipComponent)?.refreshFromFocusedProject()
    }
}
