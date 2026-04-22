package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTintBlender
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
        for (key in uiKeys) {
            val tinted = ChromeTintBlender.blend(color, key, intensity)
            UIManager.put(key, tinted)
        }
    }

    override fun revert() {
        for (key in uiKeys) {
            UIManager.put(key, null)
        }
    }
}
