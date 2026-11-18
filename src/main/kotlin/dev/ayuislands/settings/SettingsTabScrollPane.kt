package dev.ayuislands.settings

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingConstants

internal fun createScrollableTabContent(content: JComponent): JScrollPane =
    JBScrollPane(WidthTrackingTabContent(content)).apply {
        border = JBUI.Borders.empty()
        viewportBorder = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        minimumSize = Dimension(0, 0)
    }

private class WidthTrackingTabContent(
    content: JComponent,
) : JPanel(BorderLayout()),
    Scrollable {
    init {
        isOpaque = false
        minimumSize = Dimension(0, 0)
        add(content, BorderLayout.CENTER)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = Dimension(0, preferredSize.height)

    override fun getScrollableUnitIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = JBUI.scale(SCROLL_UNIT_INCREMENT)

    override fun getScrollableBlockIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int =
        if (orientation == SwingConstants.VERTICAL) {
            (visibleRect.height - JBUI.scale(SCROLL_UNIT_INCREMENT))
                .coerceAtLeast(JBUI.scale(SCROLL_UNIT_INCREMENT))
        } else {
            (visibleRect.width - JBUI.scale(SCROLL_UNIT_INCREMENT))
                .coerceAtLeast(JBUI.scale(SCROLL_UNIT_INCREMENT))
        }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    private companion object {
        const val SCROLL_UNIT_INCREMENT = 16
    }
}
