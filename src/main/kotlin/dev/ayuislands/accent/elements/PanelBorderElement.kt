package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints panel / tool-window borders per CHROME-05.
 *
 * NB: `OnePixelDivider.background` is deliberately excluded — it is already
 * managed by [dev.ayuislands.accent.AccentApplicator]'s `ALWAYS_ON_UI_KEYS`
 * list. Double-writing from two sources would make revert ambiguous (whose
 * null wins?), so this element owns only the tool-window-level border keys.
 *
 * Intensity is read directly from [AyuIslandsSettings.state] per CONTEXT D-03.
 */
class PanelBorderElement : AccentElement {
    override val id = AccentElementId.PANEL_BORDER
    override val displayName = "Panel borders"

    internal val uiKeys: List<String> =
        listOf(
            "ToolWindow.Header.borderColor",
            "ToolWindow.borderColor",
        )

    override fun apply(color: Color) {
        val intensity = AyuIslandsSettings.getInstance().state.chromeTintIntensity
        var tintedHeaderBorder: Color? = null
        for (key in uiKeys) {
            val baseColor = UIManager.getColor(key) ?: continue
            val tinted = ChromeTintBlender.blend(color, baseColor, intensity)
            UIManager.put(key, tinted)
            if (key == HEADER_BORDER_KEY) tintedHeaderBorder = tinted
        }
        // Level 2 Gap-4: push tinted tool-window header border to the live OnePixelDivider
        // peer. The divider caches its color at construction and re-renders on repaint;
        // UIManager writes alone don't refresh it (see VERIFICATION Gap 4, CHROME-05).
        tintedHeaderBorder?.let { LiveChromeRefresher.refreshByClassName(DIVIDER_PEER_CLASS, it) }
    }

    override fun revert() {
        for (key in uiKeys) {
            UIManager.put(key, null)
        }
        // D-14 symmetry: hand the divider peer back to LAF default.
        LiveChromeRefresher.clearByClassName(DIVIDER_PEER_CLASS)
    }

    private companion object {
        const val HEADER_BORDER_KEY = "ToolWindow.Header.borderColor"

        /**
         * FQN of the internal 1-pixel divider used for tool-window panel borders — package-private,
         * so runtime class-name string match is the supported lookup (see 40-12 §B).
         */
        const val DIVIDER_PEER_CLASS = "com.intellij.openapi.ui.OnePixelDivider"
    }
}
