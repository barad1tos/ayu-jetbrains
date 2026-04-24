package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.WcagForeground

/**
 * Tints the Navigation Bar per CHROME-04.
 *
 * Phase 40.3c Refactor 1: migrated to [AbstractChromeElement]. Subclass declares only
 * the key lists + peer target; the base handles apply/revert.
 *
 * Writes WCAG-picked foregrounds to `NavBar.foreground` +
 * `NavBar.selectedItemForeground` — closes VERIFICATION Gap 2 (navbar
 * breadcrumb text was unreadable at saturated accents + intensity >= 40%).
 * Intensity is read directly from [dev.ayuislands.settings.AyuIslandsSettings.state]
 * per CONTEXT D-03.
 *
 * `revert()` nulls every touched key unconditionally so the LAF re-resolves
 * the stock theme value — CHROME-08 symmetry across bg + fg.
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
         * Fully-qualified class name of the New-UI navbar wrapper panel — package-private
         * so we cannot import the type. Runtime class-name string match is the supported
         * lookup (see 40-12 §B).
         */
        const val NAVBAR_PEER_CLASS = "com.intellij.platform.navbar.frontend.MyNavBarWrapperPanel"
    }
}
