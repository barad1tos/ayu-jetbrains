package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeDecorationsProbe
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints the main toolbar / title bar per CHROME-02.
 *
 * ### Probe gate (D-13)
 *
 * `apply` short-circuits with a silent no-op when [ChromeDecorationsProbe.isCustomHeaderActive]
 * returns `false` — native macOS title bar, GNOME SSD, Windows without custom-header. In those
 * setups the OS paints the chrome and our UIManager writes would be cosmetic-only against
 * invisible platform theme keys. The user-visible "disabled with tooltip" behavior is enforced
 * by the Settings row (CHROME-02, D-09); this class only owns the element-level contract.
 *
 * `revert` stays **unconditional**: if the probe flips between apply and revert (user re-enables
 * unified title bar mid-session and then disables tinting), we still clean up every key so the
 * stock theme value re-resolves (CHROME-08).
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
class MainToolbarElement : AccentElement {
    override val id = AccentElementId.MAIN_TOOLBAR
    override val displayName = "Main toolbar"

    private val backgroundKeys =
        listOf(
            "MainToolbar.background",
        )

    private val foregroundKeys =
        listOf(
            "MainToolbar.foreground",
        )

    override fun apply(color: Color) {
        if (!ChromeDecorationsProbe.isCustomHeaderActive()) return
        val intensity = AyuIslandsSettings.getInstance().state.effectiveChromeTintIntensity()
        var tintedBackground: Color? = null
        for (key in backgroundKeys) {
            val baseColor = ChromeBaseColors.get(key) ?: continue
            val tinted = ChromeTintBlender.blend(color, baseColor, intensity)
            UIManager.put(key, tinted)
            if (key == BACKGROUND_KEY) tintedBackground = tinted
        }
        if (tintedBackground != null) {
            val foreground =
                WcagForeground.pickForeground(tintedBackground, WcagForeground.TextTarget.PRIMARY_TEXT)
            for (key in foregroundKeys) {
                UIManager.put(key, foreground)
            }
        }
        // Level 2 Gap-4: push tinted bg to live MainToolbar peer. Probe-gated per D-13
        // — we only walk when isCustomHeaderActive is true (early return above).
        tintedBackground?.let { LiveChromeRefresher.refreshByClassName(MAIN_TOOLBAR_PEER_CLASS, it) }
    }

    override fun revert() {
        for (key in backgroundKeys + foregroundKeys) {
            UIManager.put(key, null)
        }
        // D-14 symmetry: hand the toolbar peer back to LAF default. Unconditional like
        // the UIManager revert above — even if the probe flips between apply and revert,
        // the peer's lingering explicit background must be cleared.
        LiveChromeRefresher.clearByClassName(MAIN_TOOLBAR_PEER_CLASS)
    }

    private companion object {
        /** Reference key for the contrast-foreground sample; same as the only entry in [backgroundKeys]. */
        const val BACKGROUND_KEY = "MainToolbar.background"

        /**
         * FQN of the internal main-toolbar peer — imported as a literal string because the
         * class is package-private (see 40-12 §B).
         */
        const val MAIN_TOOLBAR_PEER_CLASS = "com.intellij.openapi.wm.impl.headertoolbar.MainToolbar"
    }
}
