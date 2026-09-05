package dev.ayuislands.glow

import java.awt.Color
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent
import javax.swing.border.Border

/** Owns a component's glow border and listener registrations. All callbacks run on the EDT. */
internal class GlowFocusListener(
    private val component: JComponent,
    private val glowColor: Color,
    private val glowStyle: GlowStyle,
    private val baseIntensity: Int,
    private val onDetached: () -> Unit,
) : FocusListener,
    HierarchyListener {
    private var isAttached = false
    private var originalBorder: Border? = null
    private var installedBorder: GlowFocusBorder? = null

    fun attach() {
        if (isAttached) return
        isAttached = true
        component.addFocusListener(this)
        component.addHierarchyListener(this)
        if (component.hasFocus()) applyBorder()
    }

    fun detach() {
        if (!isAttached) return
        isAttached = false
        component.removeFocusListener(this)
        component.removeHierarchyListener(this)
        restoreBorder()
        onDetached()
    }

    override fun focusGained(event: FocusEvent) {
        if (isAttached && event.component === component) applyBorder()
    }

    override fun focusLost(event: FocusEvent) {
        if (event.component === component) restoreBorder()
    }

    override fun hierarchyChanged(event: HierarchyEvent) {
        val displayabilityChanged =
            (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L
        if (displayabilityChanged && !component.isDisplayable) detach()
    }

    private fun applyBorder() {
        if (installedBorder != null && component.border === installedBorder) return
        originalBorder = component.border
        val border = GlowFocusBorder(originalBorder, glowColor, glowStyle, baseIntensity)
        installedBorder = border
        component.border = border
        component.repaint()
    }

    private fun restoreBorder() {
        val ownedBorder = installedBorder ?: return
        val previousBorder = originalBorder
        installedBorder = null
        originalBorder = null
        if (component.border === ownedBorder) {
            component.border = previousBorder
            component.repaint()
        }
    }
}
