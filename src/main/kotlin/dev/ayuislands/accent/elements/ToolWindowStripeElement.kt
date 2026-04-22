package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints the tool window stripe and its active/selected stripe button per CHROME-03.
 *
 * The three background keys are javap-verified against platformVersion 2025.1 — see
 * `40-06-SUMMARY.md` "Stripe Button Key Verification". `ToolWindow.Button.selectedBackground`
 * is the core New UI stripe-button key and is registered in the bundled
 * `IntelliJPlatform.themeMetadata.json` under `app-client.jar`, so every 2025.1 user has
 * a live LAF entry for it even when the project theme does not.
 *
 * Intensity is read from [AyuIslandsState.chromeTintIntensity] per D-03; the per-element
 * revert unconditionally nulls every key the element can write so a toggle flip leaves
 * the stock theme value to re-resolve (CHROME-08).
 */
class ToolWindowStripeElement : AccentElement {
    override val id = AccentElementId.TOOL_WINDOW_STRIPE
    override val displayName = "Tool window stripe"

    private val backgroundKeys =
        listOf(
            "ToolWindow.Stripe.background",
            "ToolWindow.Stripe.borderColor",
            "ToolWindow.Button.selectedBackground",
        )

    private val foregroundKeys =
        listOf(
            "ToolWindow.Button.selectedForeground",
            "ToolWindow.Stripe.foreground",
        )

    override fun apply(color: Color) {
        val state = AyuIslandsSettings.getInstance().state
        val intensity = state.chromeTintIntensity
        for (key in backgroundKeys) {
            val tinted = ChromeTintBlender.blend(color, key, intensity)
            UIManager.put(key, tinted)
        }
        if (state.chromeTintKeepForegroundReadable) {
            val tintedForContrast =
                ChromeTintBlender.blend(color, SELECTED_BACKGROUND_KEY, intensity)
            val foreground =
                WcagForeground.pickForeground(tintedForContrast, WcagForeground.TextTarget.ICON)
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
        /** Reference key for the contrast-foreground sample; same as the last entry in [backgroundKeys]. */
        const val SELECTED_BACKGROUND_KEY = "ToolWindow.Button.selectedBackground"
    }
}
