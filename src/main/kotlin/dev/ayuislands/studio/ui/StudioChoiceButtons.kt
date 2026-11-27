package dev.ayuislands.studio.ui

import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.JToggleButton

/** Render a single choice with stable Swing buttons and explicit per-choice availability. */
internal class StudioChoiceButtons<T>(
    choices: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)) {
    private val buttons =
        choices.associateWith { choice ->
            JToggleButton(label(choice)).apply { addActionListener { onSelect(choice) } }
        }

    init {
        isOpaque = false
        val group = ButtonGroup()
        for (button in buttons.values) {
            group.add(button)
            add(button)
        }
    }

    fun render(
        selected: T,
        editable: Boolean,
    ) = render(selected, available = { editable })

    fun render(
        selected: T,
        available: (T) -> Boolean,
        tooltip: (T) -> String? = { null },
    ) {
        buttons.getValue(selected).isSelected = true
        for ((choice, button) in buttons) {
            button.isEnabled = available(choice)
            button.toolTipText = tooltip(choice)
        }
    }
}
