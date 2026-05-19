package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.WcagForeground

/**
 * Tints the Navigation Bar.
 *
 * Writes WCAG-picked foregrounds to `NavBar.foreground` +
 * `NavBar.selectedItemForeground` so navbar breadcrumb text stays readable
 * at saturated accents + high intensity. Intensity is read directly from
 * [dev.ayuislands.settings.AyuIslandsSettings.state].
 *
 * `revert()` nulls every touched key unconditionally so the LAF re-resolves
 * the stock theme value — Pattern G symmetry across bg + fg.
 */
class NavBarElement : AbstractChromeElement() {
    override val id = AccentElementId.NAV_BAR
    override val displayName = "Navigation bar"

    override val backgroundKeys =
        listOf(
            "NavBar.background",
            "NavBar.borderColor",
        )

    override val foregroundKeys =
        listOf(
            "NavBar.foreground",
            "NavBar.selectedItemForeground",
        )

    override val foregroundTextTarget = WcagForeground.TextTarget.PRIMARY_TEXT

    override val peerTarget: ChromeTarget = ChromeTarget.ByClassName(classFqn(NAVBAR_PEER_CLASS))

    private companion object {
        /**
         * Fully-qualified class name of the New-UI navbar wrapper panel — the
         * type is package-private so we cannot import it. Runtime class-name
         * string match is the supported lookup pattern.
         */
        const val NAVBAR_PEER_CLASS = "com.intellij.platform.navbar.frontend.MyNavBarWrapperPanel"
    }
}
