package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints the status bar background, border, and widget hover per CHROME-01 / CHROME-07.
 *
 * Intensity is read directly from [AyuIslandsSettings.state] per CONTEXT D-03 — the
 * `apply(color)` signature does not carry it, so every Phase 40 chrome element pulls
 * the same live settings. WCAG-aware foreground contrast is always applied — the
 * earlier opt-out toggle was retired because saturated tints without readable
 * foregrounds failed the user-space quality bar.
 *
 * `revert()` nulls every touched UIManager key so the LAF re-resolves the stock
 * theme value (D-14 / CHROME-08). Both the background keys and the foreground keys
 * are nulled unconditionally so a previously-applied contrast color cannot leak
 * after the user disables chrome tinting.
 */
class StatusBarElement : AccentElement {
    override val id = AccentElementId.STATUS_BAR
    override val displayName = "Status bar"

    private val backgroundKeys =
        listOf(
            "StatusBar.background",
            "StatusBar.borderColor",
            "StatusBar.Widget.hoverBackground",
        )

    private val foregroundKeys =
        listOf(
            "StatusBar.Widget.foreground",
            "StatusBar.Widget.hoverForeground",
        )

    override fun apply(color: Color) {
        val intensity = AyuIslandsSettings.getInstance().state.chromeTintIntensity
        var tintedBackground: Color? = null
        for (key in backgroundKeys) {
            val baseColor = ChromeBaseColors.get(key) ?: continue
            val tinted = ChromeTintBlender.blend(color, baseColor, intensity)
            UIManager.put(key, tinted)
            if (key == "StatusBar.background") tintedBackground = tinted
        }
        if (tintedBackground != null) {
            val contrast = WcagForeground.pickForeground(tintedBackground, WcagForeground.TextTarget.PRIMARY_TEXT)
            for (key in foregroundKeys) {
                UIManager.put(key, contrast)
            }
        }
        // Level 2 Gap-4: push the tinted bg to the live IdeStatusBarImpl peer because
        // UIManager writes don't propagate to already-rendered chrome (CHROME-07).
        tintedBackground?.let { LiveChromeRefresher.refreshStatusBar(it) }
    }

    override fun revert() {
        for (key in backgroundKeys + foregroundKeys) {
            UIManager.put(key, null)
        }
        // D-14 symmetry: hand the status bar peer back to LAF default.
        LiveChromeRefresher.clearStatusBar()
    }
}
