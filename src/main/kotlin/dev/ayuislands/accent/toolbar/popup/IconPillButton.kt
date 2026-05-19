package dev.ayuislands.accent.toolbar.popup

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent

/**
 * 28x28 icon-only pill that delegates to an [AnAction] when clicked. Used
 * inside the quick-switcher popup's quick-actions row.
 *
 * Custom `paintComponent` paints a rounded background ONLY on hover / pressed —
 * idle reads as a transparent surface so the parent section card shows through.
 * Tooltip text comes from the action's `templatePresentation.description` (or
 * `text` as fallback).
 *
 * Click handler uses the non-deprecated 6-arg event-factory form on
 * [AnActionEvent] and dispatches through [ActionUtil.invokeAction] rather
 * than calling the action's perform method directly: IntelliJ 2025.1+
 * marks `AnAction.actionPerformed` as `@ApiStatus.OverrideOnly`, so
 * direct invocation by callers bypasses platform plumbing
 * (beforeActionPerformed listeners, action-promoter chain, error
 * reporting). [ActionUtil.invokeAction] is the project-canonical
 * helper — see `LicenseChecker.kt` for prior art.
 * The dispatch is wrapped in
 * `try { ... } catch (exception: RuntimeException) { LOG.warn(...) }` per
 * Pattern B — a throwing action must NOT kill the EDT or crash the popup.
 *
 * Pattern A — mouse events run on EDT by Swing contract. Pattern Q — every
 * `Graphics.create()` block dismisses in `finally`.
 *
 * @param action the [AnAction] this pill delegates to on click.
 * @param anchor the popup's invoker [JComponent] — used as `CONTEXT_COMPONENT`
 *   in the synthetic [AnActionEvent].
 * @param icon the 16x16 [Icon] rendered inside the pill.
 */
internal class IconPillButton(
    internal val action: AnAction,
    private val anchor: JComponent,
    icon: Icon,
) : JButton() {
    private var isHovered: Boolean = false
    private var isPressed: Boolean = false

    init {
        setIcon(icon)
        text = ""
        border = JBUI.Borders.empty()
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(PILL_PX), JBUI.scale(PILL_PX))
        toolTipText = action.templatePresentation.description ?: action.templatePresentation.text ?: ""
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) {
                    isHovered = true
                    repaint()
                }

                override fun mouseExited(event: MouseEvent) {
                    isHovered = false
                    isPressed = false
                    repaint()
                }

                override fun mousePressed(event: MouseEvent) {
                    if (!isEnabled) return
                    isPressed = true
                    repaint()
                }

                override fun mouseReleased(event: MouseEvent) {
                    isPressed = false
                    repaint()
                }
            },
        )

        addActionListener { invokeAction() }
    }

    override fun getAccessibleContext(): AccessibleContext {
        val existing = super.getAccessibleContext()
        if (existing != null) {
            if (existing.accessibleName == null) existing.accessibleName = toolTipText
            return existing
        }
        val fresh =
            object : AccessibleJButton() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON
            }
        fresh.accessibleName = toolTipText
        accessibleContext = fresh
        return fresh
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            paintPillBackground(g2)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private fun paintPillBackground(g2: Graphics2D) {
        if (!isHovered && !isPressed) return
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val shape =
            RoundRectangle2D.Float(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                ARC,
                ARC,
            )
        val fill: Color =
            if (isPressed) {
                JBUI.CurrentTheme.ActionButton.pressedBackground()
            } else {
                JBUI.CurrentTheme.ActionButton.hoverBackground()
            }
        g2.color = fill
        g2.fill(shape)
    }

    private fun invokeAction() {
        try {
            val dataContext: DataContext =
                SimpleDataContext
                    .builder()
                    .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, anchor)
                    .build()
            val event =
                AnActionEvent.createEvent(
                    action,
                    dataContext,
                    Presentation(),
                    POPUP_PLACE,
                    ActionUiKind.POPUP,
                    null,
                )
            // Project-canonical dispatch — mirrors [LicenseChecker.invokeAction].
            // Direct `action.actionPerformed(event)` would bypass `@ApiStatus.OverrideOnly`
            // contract on 2025.1+ and miss the beforeActionPerformed plumbing.
            ActionUtil.invokeAction(action, event, null)
        } catch (exception: RuntimeException) {
            LOG.warn("IconPillButton action ${action.javaClass.simpleName} failed", exception)
        }
    }

    private companion object {
        const val PILL_PX: Int = 28
        const val ARC: Float = 4f
        const val POPUP_PLACE: String = "AyuQuickSwitcher.Popup"
        val LOG = logger<IconPillButton>()
    }
}
