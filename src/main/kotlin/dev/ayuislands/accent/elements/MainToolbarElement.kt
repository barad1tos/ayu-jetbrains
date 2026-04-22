package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeDecorationsProbe
import dev.ayuislands.accent.ChromeTintBlender
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
 *  - `MainToolbar.Dropdown.transparentHoverBackground` — intentional translucency; tinting would
 *    solidify it and destroy the dropdown hover effect.
 *  - `RecentProject.Color*.MainToolbarGradientStart` / `...GradientEnd` — per-project IntelliJ
 *    gradient palette, owned by the Recent Projects feature.
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
        val state = AyuIslandsSettings.getInstance().state
        val intensity = state.chromeTintIntensity
        for (key in backgroundKeys) {
            val tinted = ChromeTintBlender.blend(color, key, intensity)
            UIManager.put(key, tinted)
        }
        if (state.chromeTintKeepForegroundReadable) {
            val tintedForContrast =
                ChromeTintBlender.blend(color, BACKGROUND_KEY, intensity)
            val foreground = ChromeTintBlender.contrastForeground(tintedForContrast)
            for (key in foregroundKeys) {
                UIManager.put(key, foreground)
            }
        }
    }

    override fun revert() {
        for (key in backgroundKeys + foregroundKeys) {
            UIManager.put(key, null)
        }
    }

    private companion object {
        /** Reference key for the contrast-foreground sample; same as the only entry in [backgroundKeys]. */
        const val BACKGROUND_KEY = "MainToolbar.background"
    }
}
