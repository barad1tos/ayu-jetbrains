package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints the Navigation Bar per CHROME-04.
 *
 * No foreground tinting — navbar breadcrumbs inherit from the editor scheme and
 * are not covered by CHROME-07's contract. Intensity is read directly from
 * [AyuIslandsSettings.state] per CONTEXT D-03.
 */
class NavBarElement : AccentElement {
    override val id = AccentElementId.NAV_BAR
    override val displayName = "Navigation bar"

    private val uiKeys =
        listOf(
            "NavBar.background",
            "NavBar.borderColor",
        )

    override fun apply(color: Color) {
        val intensity = AyuIslandsSettings.getInstance().state.chromeTintIntensity
        for (key in uiKeys) {
            val tinted = ChromeTintBlender.blend(color, key, intensity)
            UIManager.put(key, tinted)
        }
    }

    override fun revert() {
        for (key in uiKeys) {
            UIManager.put(key, null)
        }
    }
}
