package dev.ayuislands.settings

import javax.swing.JLabel

internal class RuntimeStatus {
    val component = JLabel().apply { isVisible = false }

    fun applied() {
        component.isVisible = false
    }

    fun failed(failure: RuntimeException) {
        component.toolTipText = failure.message
        show(
            "Could not preview syntax changes. Your saved choices were not changed. " +
                "Adjust a control or click Apply to retry.",
        )
    }

    fun relinquished(keyId: String) {
        show("$keyId changed outside Ayu Islands and was left untouched. Change its control again to reapply it.")
    }

    fun foreignScheme() {
        show("Live editor preview requires an Ayu color scheme. Select an Ayu scheme to resume.")
    }

    private fun show(message: String) {
        component.text = message
        component.isVisible = true
        component.revalidate()
        component.parent?.revalidate()
        component.repaint()
    }
}
