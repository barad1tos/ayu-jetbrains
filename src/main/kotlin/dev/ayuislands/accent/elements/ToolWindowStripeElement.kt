package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.LiveChromeRefresher
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
        var tintedStripeBackground: Color? = null
        var tintedSelectedBackground: Color? = null
        for (key in backgroundKeys) {
            val baseColor = UIManager.getColor(key) ?: continue
            val tinted = ChromeTintBlender.blend(color, baseColor, intensity)
            UIManager.put(key, tinted)
            when (key) {
                STRIPE_BACKGROUND_KEY -> tintedStripeBackground = tinted
                SELECTED_BACKGROUND_KEY -> tintedSelectedBackground = tinted
            }
        }
        if (state.chromeTintKeepForegroundReadable && tintedSelectedBackground != null) {
            val foreground =
                WcagForeground.pickForeground(tintedSelectedBackground, WcagForeground.TextTarget.ICON)
            for (key in foregroundKeys) {
                UIManager.put(key, foreground)
            }
        }
        // Level 2 Gap-4: push stripe bg to the live com.intellij.toolWindow.Stripe peer.
        tintedStripeBackground?.let { LiveChromeRefresher.refreshByClassName(STRIPE_PEER_CLASS, it) }
    }

    override fun revert() {
        for (key in backgroundKeys + foregroundKeys) {
            UIManager.put(key, null)
        }
        // D-14 symmetry: hand the stripe peer back to LAF default.
        LiveChromeRefresher.clearByClassName(STRIPE_PEER_CLASS)
    }

    private companion object {
        /** Reference key for the contrast-foreground sample; same as the last entry in [backgroundKeys]. */
        const val SELECTED_BACKGROUND_KEY = "ToolWindow.Button.selectedBackground"
        const val STRIPE_BACKGROUND_KEY = "ToolWindow.Stripe.background"

        /**
         * Internal (package-private, final) tool-window stripe class — type not importable,
         * so runtime class-name string match is the supported lookup path (see 40-12 §B).
         */
        const val STRIPE_PEER_CLASS = "com.intellij.toolWindow.Stripe"
    }
}
