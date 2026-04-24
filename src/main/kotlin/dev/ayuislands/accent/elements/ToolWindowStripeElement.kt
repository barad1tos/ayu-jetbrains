package dev.ayuislands.accent.elements

import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.WcagForeground
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.UIManager

/**
 * Tints the tool window stripe and its active/selected stripe button per CHROME-03.
 *
 * Phase 40.3c Refactor 1: migrated to [AbstractChromeElement]. [writeForegrounds] is
 * overridden to implement the Round 2 A-2 split-fg pick (stripe bg and selected
 * bg sampled independently); [onBackgroundsTinted] wires the Phase 40.2 M-3
 * first-apply diagnostic.
 *
 * The three background keys are javap-verified against platformVersion 2025.1 — see
 * `40-06-SUMMARY.md` "Stripe Button Key Verification". `ToolWindow.Button.selectedBackground`
 * is the core New UI stripe-button key and is registered in the bundled
 * `IntelliJPlatform.themeMetadata.json` under `app-client.jar`, so every 2025.1 user has
 * a live LAF entry for it even when the project theme does not.
 *
 * Intensity is read from `AyuIslandsState.chromeTintIntensity` per D-03; the per-element
 * revert unconditionally nulls every key the element can write so a toggle flip leaves
 * the stock theme value to re-resolve (CHROME-08).
 */
class ToolWindowStripeElement : AbstractChromeElement() {
    override val id = AccentElementId.TOOL_WINDOW_STRIPE
    override val displayName = "Tool window stripe"

    override val backgroundKeys =
        listOf(
            STRIPE_BACKGROUND_KEY,
            "ToolWindow.Stripe.borderColor",
            SELECTED_BACKGROUND_KEY,
        )

    override val foregroundKeys =
        listOf(
            SELECTED_FOREGROUND_KEY,
            STRIPE_FOREGROUND_KEY,
        )

    override val foregroundTextTarget = WcagForeground.TextTarget.ICON

    override val peerTarget: ChromeTarget = ChromeTarget.ByClassName(classFqn(STRIPE_PEER_CLASS))

    override fun onBackgroundsTinted(tintedBackgrounds: Map<String, Color>) {
        // Phase 40.2 M-3: first-apply INFO diagnostic so a future platform FQN
        // rename surfaces in idea.log (both the UIManager key resolution map
        // and the live peer-walk target class).
        if (firstApplyLogged.compareAndSet(false, true)) {
            val keysSeen = tintedBackgrounds.keys.toList()
            val keysMissed = backgroundKeys.filter { it !in tintedBackgrounds }
            LOG.info(
                "ToolWindowStripeElement first apply: keysSeen=$keysSeen keysMissed=$keysMissed " +
                    "walkTargets=[stripe=$STRIPE_PEER_CLASS]",
            )
        }
    }

    /**
     * Round 2 A-2 split-fg pick: stripe background and selected-button background
     * are DIFFERENT base colors — at non-trivial intensities their tinted outputs
     * diverge. Sampling the contrast foreground against only one and reusing the
     * pick for the other can drop non-selected stripe icons below WCAG AA.
     */
    override fun writeForegrounds(tintedBackgrounds: Map<String, Color>) {
        val tintedSelectedBackground = tintedBackgrounds[SELECTED_BACKGROUND_KEY]
        if (tintedSelectedBackground != null) {
            val selectedForeground =
                WcagForeground.pickForeground(tintedSelectedBackground, foregroundTextTarget)
            UIManager.put(SELECTED_FOREGROUND_KEY, selectedForeground)
        }
        val tintedStripeBackground = tintedBackgrounds[STRIPE_BACKGROUND_KEY]
        if (tintedStripeBackground != null) {
            val stripeForeground =
                WcagForeground.pickForeground(tintedStripeBackground, foregroundTextTarget)
            UIManager.put(STRIPE_FOREGROUND_KEY, stripeForeground)
        }
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
