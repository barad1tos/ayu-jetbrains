package dev.ayuislands.accent.elements

import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.ClassFqn
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean
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
        val intensity = AyuIslandsSettings.getInstance().state.effectiveChromeTintIntensity()
        var tintedStripeBackground: Color? = null
        var tintedSelectedBackground: Color? = null
        val keysSeen = mutableListOf<String>()
        val keysMissed = mutableListOf<String>()
        for (key in backgroundKeys) {
            val baseColor = ChromeBaseColors.get(key)
            if (baseColor == null) {
                keysMissed.add(key)
                continue
            }
            keysSeen.add(key)
            val tinted = ChromeTintBlender.blend(color, baseColor, intensity)
            UIManager.put(key, tinted)
            when (key) {
                STRIPE_BACKGROUND_KEY -> tintedStripeBackground = tinted
                SELECTED_BACKGROUND_KEY -> tintedSelectedBackground = tinted
            }
        }
        // Phase 40.2 M-3: first-apply INFO diagnostic so a future platform FQN
        // rename surfaces in idea.log (both the UIManager key resolution map
        // and the live peer-walk target class).
        if (firstApplyLogged.compareAndSet(false, true)) {
            LOG.info(
                "ToolWindowStripeElement first apply: keysSeen=$keysSeen keysMissed=$keysMissed " +
                    "walkTargets=[stripe=$STRIPE_PEER_CLASS]",
            )
        }
        // Round 2 review A-2 (CRITICAL correctness): stripe background and selected-button
        // background are DIFFERENT base colors — at non-trivial intensities their tinted
        // outputs diverge. Sampling the contrast foreground against only the selected-button
        // bg and reusing that pick for the stripe foreground can drop non-selected stripe
        // icons below the WCAG AA ratio. Pick each foreground against its own bg.
        if (tintedSelectedBackground != null) {
            val selectedForeground =
                WcagForeground.pickForeground(tintedSelectedBackground, WcagForeground.TextTarget.ICON)
            UIManager.put(SELECTED_FOREGROUND_KEY, selectedForeground)
        }
        if (tintedStripeBackground != null) {
            val stripeForeground =
                WcagForeground.pickForeground(tintedStripeBackground, WcagForeground.TextTarget.ICON)
            UIManager.put(STRIPE_FOREGROUND_KEY, stripeForeground)
        }
        // Level 2 Gap-4: push stripe bg to the live com.intellij.toolWindow.Stripe peer.
        tintedStripeBackground?.let { LiveChromeRefresher.refreshByClassName(ClassFqn.require(STRIPE_PEER_CLASS), it) }
    }

    override fun revert() {
        for (key in backgroundKeys + foregroundKeys) {
            UIManager.put(key, null)
        }
        // D-14 symmetry: hand the stripe peer back to LAF default.
        LiveChromeRefresher.clearByClassName(ClassFqn.require(STRIPE_PEER_CLASS))
    }

    private companion object {
        private val LOG = logger<ToolWindowStripeElement>()

        /**
         * One-shot gate for the per-session first-apply diagnostic log (Phase 40.2 M-3).
         * Logs which UIManager keys resolved vs missed and the peer-walk target class
         * so a future platform FQN rename is visible in idea.log.
         */
        private val firstApplyLogged = AtomicBoolean(false)

        /** Reference key for the selected-button contrast-foreground sample. */
        const val SELECTED_BACKGROUND_KEY = "ToolWindow.Button.selectedBackground"
        const val STRIPE_BACKGROUND_KEY = "ToolWindow.Stripe.background"

        /** Foreground key paired with [SELECTED_BACKGROUND_KEY] for the Round 2 A-2 split fg pick. */
        const val SELECTED_FOREGROUND_KEY = "ToolWindow.Button.selectedForeground"

        /** Foreground key paired with [STRIPE_BACKGROUND_KEY] for the Round 2 A-2 split fg pick. */
        const val STRIPE_FOREGROUND_KEY = "ToolWindow.Stripe.foreground"

        /**
         * Internal (package-private, final) tool-window stripe class — type not importable,
         * so runtime class-name string match is the supported lookup path (see 40-12 §B).
         */
        const val STRIPE_PEER_CLASS = "com.intellij.toolWindow.Stripe"
    }
}
