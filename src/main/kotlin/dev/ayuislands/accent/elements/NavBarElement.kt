package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.ClassFqn
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints the Navigation Bar per CHROME-04.
 *
 * Writes WCAG-picked foregrounds to `NavBar.foreground` +
 * `NavBar.selectedItemForeground` — closes VERIFICATION Gap 2 (navbar
 * breadcrumb text was unreadable at saturated accents + intensity >= 40%).
 * Intensity is read directly from [AyuIslandsSettings.state] per CONTEXT D-03.
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
        val intensity = AyuIslandsSettings.getInstance().state.effectiveChromeTintIntensity()
        var tintedBackground: Color? = null
        for (key in backgroundKeys) {
            val baseColor = ChromeBaseColors.get(key) ?: continue
            val tinted = ChromeTintBlender.blend(color, baseColor, intensity)
            UIManager.put(key, tinted)
            if (key == "NavBar.background") tintedBackground = tinted
        }
        if (tintedBackground != null) {
            val contrast =
                WcagForeground.pickForeground(tintedBackground, WcagForeground.TextTarget.PRIMARY_TEXT)
            for (key in foregroundKeys) {
                UIManager.put(key, contrast)
            }
        }
        // Level 2 Gap-4: push tinted bg to the live MyNavBarWrapperPanel peer.
        tintedBackground?.let { LiveChromeRefresher.refreshByClassName(ClassFqn.require(NAVBAR_PEER_CLASS), it) }
    }

    override fun revert() {
        for (key in backgroundKeys + foregroundKeys) {
            UIManager.put(key, null)
        }
        // D-14 symmetry: hand the navbar peer back to LAF default.
        LiveChromeRefresher.clearByClassName(ClassFqn.require(NAVBAR_PEER_CLASS))
    }

    private companion object {
        /**
         * Fully-qualified class name of the New-UI navbar wrapper panel — package-private
         * so we cannot import the type. Runtime class-name string match is the supported
         * lookup (see 40-12 §B).
         */
        const val NAVBAR_PEER_CLASS = "com.intellij.platform.navbar.frontend.MyNavBarWrapperPanel"
    }
}
