package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints the status bar background, border, and widget hover per CHROME-01 / CHROME-07.
 *
 * Intensity and the foreground-readability flag are read directly from
 * [AyuIslandsSettings.state] per CONTEXT D-03 — the `apply(color)` signature does
 * not carry either, so every Phase 40 chrome element pulls the same live settings.
 *
 * `revert()` nulls every touched UIManager key so the LAF re-resolves the stock
 * theme value (D-14 / CHROME-08). Both the background keys and the foreground keys
 * are nulled unconditionally so a previously-applied contrast color cannot leak
 * after the toggle is turned off.
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
        val state = AyuIslandsSettings.getInstance().state
        val intensity = state.chromeTintIntensity
        for (key in backgroundKeys) {
            val tinted = ChromeTintBlender.blend(color, key, intensity)
            UIManager.put(key, tinted)
        }
        if (state.chromeTintKeepForegroundReadable) {
            val tintedForContrast = ChromeTintBlender.blend(color, "StatusBar.background", intensity)
            val contrast = ChromeTintBlender.contrastForeground(tintedForContrast)
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
