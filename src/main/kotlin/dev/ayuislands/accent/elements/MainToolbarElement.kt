package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeDecorationsProbe
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.WcagForeground

/**
 * Tints the main toolbar / title bar per CHROME-02.
 *
 * Phase 40.3c Refactor 1: migrated to [AbstractChromeElement].
 *
 * ### Probe gate (D-13)
 *
 * `apply` short-circuits via the [isEnabled] override when
 * [ChromeDecorationsProbe.isCustomHeaderActive] returns `false` — native macOS title
 * bar, GNOME SSD, Windows without custom-header. In those setups the OS paints the
 * chrome and our UIManager writes would be cosmetic-only against invisible platform
 * theme keys. The user-visible "disabled with tooltip" behavior is enforced by the
 * Settings row (CHROME-02, D-09); this class only owns the element-level contract.
 *
 * `revert` stays unconditional (inherited from the base): if the probe flips between
 * apply and revert (user re-enables unified title bar mid-session and then disables
 * tinting), we still clean up every key so the stock theme value re-resolves
 * (CHROME-08).
 *
 * ### Key selection (javap-verified against platformVersion 2025.1)
 *
 * Only keys present in the bundled IntelliJ Platform metadata or string-interned in
 * `lib/app-client.jar` are listed:
 *  - `MainToolbar.background` — registered in `IntelliJPlatform.themeMetadata.json` (since 2022.3)
 *  - `MainToolbar.foreground` — live string literal in `app-client.jar`
 *
 * `MainToolbar.borderColor` was NOT found in 2025.1 platform metadata or JAR string tables and is
 * therefore intentionally excluded (VERIFY-BEFORE-ASSUMING rule; see 40-06-SUMMARY "Deviation").
 *
 * ### Deliberately excluded
 *
 *  - The dropdown translucent-hover key under `MainToolbar.Dropdown.*` — intentional
 *    translucency; tinting would solidify it and destroy the dropdown hover effect.
 *  - The per-project gradient palette under the Recent Projects feature's per-colour
 *    keys — owned by that feature, not chrome tinting.
 */
class MainToolbarElement : AbstractChromeElement() {
    override val id = AccentElementId.MAIN_TOOLBAR
    override val displayName = "Main toolbar"

    override val backgroundKeys =
        listOf(
            "MainToolbar.background",
        )

    override val foregroundKeys =
        listOf(
            "MainToolbar.foreground",
        )

    override val foregroundTextTarget = WcagForeground.TextTarget.PRIMARY_TEXT

    override val peerTarget: ChromeTarget = ChromeTarget.ByClassName(classFqn(MAIN_TOOLBAR_PEER_CLASS))

    /** D-13 probe gate — evaluated per `apply` so a mid-session LAF flip is honoured. */
    override val isEnabled: Boolean
        get() = ChromeDecorationsProbe.isCustomHeaderActive()

    private companion object {
        /**
         * FQN of the internal main-toolbar peer — imported as a literal string because the
         * class is package-private (see 40-12 §B).
         */
        const val MAIN_TOOLBAR_PEER_CLASS = "com.intellij.openapi.wm.impl.headertoolbar.MainToolbar"
    }
}
