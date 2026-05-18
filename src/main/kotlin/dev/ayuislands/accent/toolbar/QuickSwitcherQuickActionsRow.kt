package dev.ayuislands.accent.toolbar

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.toolbar.actions.CopyHexAction
import dev.ayuislands.accent.toolbar.actions.DarkerAccentAction
import dev.ayuislands.accent.toolbar.actions.LighterAccentAction
import dev.ayuislands.accent.toolbar.actions.PinAccentAction
import dev.ayuislands.accent.toolbar.actions.RandomAccentAction
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Premium-block sub-section: a horizontal row of five [JButton] instances that
 * delegate to the matching action classes under
 * `dev.ayuislands.accent.toolbar.actions.*`. Per D-14b, the popup's quick-actions
 * row and the chip's right-click context menu MUST consume the SAME action
 * classes — the action classes are the single source of behaviour. The button
 * surface here exists so licensed users get button-shaped affordances inside
 * the popup; the right-click menu uses native menu items via
 * `QuickSwitcherActionGroup.build()`.
 *
 * Order is fixed and matches `QuickSwitcherActionGroup.build()`:
 * `Pin -> Random -> Lighter -> Darker -> Copy Hex`. The
 * `QuickSwitcherActionParityTest` source-greps both files at test time to lock
 * the invariant — any future edit that diverges in class set or order trips
 * the parity assertion.
 *
 * Each button's onClick handler wraps the underlying
 * [AnAction.actionPerformed] call in a `try { ... } catch (exception:
 * RuntimeException) { LOG.warn(...) }` block per Pattern B — a throwing action
 * class must NOT crash the popup or kill the EDT.
 *
 * @param anchor the popup's invoker [JComponent] — used as the [DataContext]
 *   component for the synthetic [AnActionEvent] so the actions resolve focus
 *   context if they reach for `PlatformCoreDataKeys.CONTEXT_COMPONENT`.
 */
internal class QuickSwitcherQuickActionsRow(
    private val anchor: JComponent,
) {
    val component: JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, BUTTON_GAP, 0)).apply {
            add(buttonFor(PinAccentAction(), label = "Pin"))
            add(buttonFor(RandomAccentAction(), label = "Random"))
            add(buttonFor(LighterAccentAction(), label = "Lighter"))
            add(buttonFor(DarkerAccentAction(), label = "Darker"))
            add(buttonFor(CopyHexAction(), label = "Copy Hex"))
        }

    private fun buttonFor(
        action: AnAction,
        label: String,
    ): JButton =
        JButton(label).apply {
            toolTipText = action.templatePresentation.description ?: label
            addActionListener {
                try {
                    val dataContext: DataContext =
                        SimpleDataContext
                            .builder()
                            .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, anchor)
                            .build()
                    // 6-arg `createEvent` is the non-deprecated replacement
                    // for `createFromAnAction` per AnActionEvent javap on 2025.1.
                    val event =
                        AnActionEvent.createEvent(
                            action,
                            dataContext,
                            Presentation(),
                            POPUP_PLACE,
                            ActionUiKind.POPUP,
                            null,
                        )
                    action.actionPerformed(event)
                } catch (exception: RuntimeException) {
                    LOG.warn("Quick-action $label failed", exception)
                }
            }
        }

    private companion object {
        const val BUTTON_GAP = 4
        const val POPUP_PLACE = "AyuQuickSwitcher.Popup"
        val LOG = logger<QuickSwitcherQuickActionsRow>()
    }
}
