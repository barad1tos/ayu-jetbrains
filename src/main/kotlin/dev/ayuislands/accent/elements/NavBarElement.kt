package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints the Navigation Bar per CHROME-04.
 *
 * Writes WCAG-picked foregrounds to `NavBar.foreground` +
 * `NavBar.selectedItemForeground` when
 * [AyuIslandsState.chromeTintKeepForegroundReadable] is true — closes
 * VERIFICATION Gap 2 (navbar breadcrumb text was unreadable at saturated
 * accents + intensity >= 40%). Intensity is read directly from
 * [AyuIslandsSettings.state] per CONTEXT D-03.
 *
 * `revert()` nulls every touched key unconditionally so the LAF re-resolves
 * the stock theme value — CHROME-08 symmetry across bg + fg.
 */
class NavBarElement : AccentElement {
    override val id = AccentElementId.NAV_BAR
    override val displayName = "Navigation bar"

    private val backgroundKeys =
        listOf(
            "NavBar.background",
            "NavBar.borderColor",
        )

    private val foregroundKeys =
        listOf(
            "NavBar.foreground",
            "NavBar.selectedItemForeground",
        )

    override fun apply(color: Color) {
        val state = AyuIslandsSettings.getInstance().state
        val intensity = state.chromeTintIntensity
        for (key in backgroundKeys) {
            val tinted = ChromeTintBlender.blend(color, key, intensity)
            UIManager.put(key, tinted)
        }
        if (state.chromeTintKeepForegroundReadable) {
            val tintedForContrast = ChromeTintBlender.blend(color, "NavBar.background", intensity)
            val contrast =
                WcagForeground.pickForeground(tintedForContrast, WcagForeground.TextTarget.PRIMARY_TEXT)
            for (key in foregroundKeys) {
                UIManager.put(key, contrast)
            }
        }
    }

    override fun revert() {
        for (key in backgroundKeys + foregroundKeys) {
            UIManager.put(key, null)
        }
    }
}
