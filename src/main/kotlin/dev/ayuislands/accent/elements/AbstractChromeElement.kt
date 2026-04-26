package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.ChromeTintContext
import dev.ayuislands.accent.ClassFqn
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.UIManager

/**
 * Base class for chrome tinting [AccentElement]s (Phase 40.3c Refactor 1).
 *
 * Before this extraction, `StatusBarElement`, `MainToolbarElement`, `NavBarElement`,
 * `PanelBorderElement`, and `ToolWindowStripeElement` each replicated the same
 * ~40-line recipe: read intensity → for each bg key → [ChromeBaseColors.get] →
 * [ChromeTintBlender.blend] → [UIManager.put] → sample WCAG foreground → write to
 * fg keys → push tinted background to the live peer via [LiveChromeRefresher].
 * Revert mirrors it: null every key, clear the peer.
 *
 * Subclasses declare *what* to tint (keys, peer target) and the base handles *how*:
 *
 *  - [backgroundKeys] — every UIManager key that receives a tinted color on apply
 *    and is nulled on revert.
 *  - [foregroundKeys] — every UIManager key that receives a WCAG-picked contrast
 *    color on apply and is nulled on revert. May be empty (e.g. panel borders).
 *  - [foregroundTextTarget] — WCAG minimum-ratio band the picker must satisfy.
 *    `PRIMARY_TEXT` for widget text (4.5:1), `ICON` for stripe buttons (3.0:1).
 *  - [peerTarget] — typed description of the Swing peer to refresh. `null` means
 *    no live peer walk (element only writes UIManager keys).
 *  - [isEnabled] — optional gate. `MainToolbarElement` overrides this to short-circuit
 *    `apply` when JBR custom decorations are inactive (D-13). `revert` ignores it so
 *    keys written before the gate flipped are still cleaned up.
 *
 * Elements with split-fg pick requirements (StatusBar's normal vs hover fg from
 * Phase 40.2 M-1; ToolWindowStripe's stripe vs selected fg from Round 2 A-2)
 * override [writeForegrounds] to pick the contrast per sampling-bg; the default
 * implementation picks one contrast color against the first tinted background
 * key and writes it to every entry in [foregroundKeys].
 *
 * Elements with first-apply diagnostic logging (PanelBorder, ToolWindowStripe —
 * Phase 40.2 M-3) stay in their own subclass because the one-shot gate is
 * per-element state and the logged fields (keysSeen / keysMissed / walk target
 * class) differ per element.
 *
 * EDT: callers are already on EDT (AccentApplicator dispatches through
 * `invokeLaterSafe`), so [UIManager.put] + peer mutation are safe.
 */
abstract class AbstractChromeElement : AccentElement {
    /** UIManager keys that receive a tinted color on apply, nulled on revert. */
    protected abstract val backgroundKeys: List<String>

    /** UIManager keys that receive a WCAG-picked contrast color on apply, nulled on revert. May be empty. */
    protected abstract val foregroundKeys: List<String>

    /** WCAG target band for the contrast pick. [foregroundKeys] empty → ignored. */
    protected abstract val foregroundTextTarget: WcagForeground.TextTarget

    /** Typed peer descriptor for [LiveChromeRefresher]. `null` skips the live peer walk. */
    protected abstract val peerTarget: ChromeTarget?

    /** Gate for `apply` (MainToolbar uses this for the probe). Always `true` by default. `revert` ignores it. */
    protected open val isEnabled: Boolean
        get() = true

    override fun apply(color: Color) {
        if (!isEnabled) return
        val state = AyuIslandsSettings.getInstance().state
        val intensity = ChromeTintContext.currentIntensity(state)
        val tintedBackgrounds = LinkedHashMap<String, Color>()
        for (key in backgroundKeys) {
            val baseColor = ChromeBaseColors.get(key) ?: continue
            val tinted = ChromeTintBlender.blend(color, baseColor, intensity)
            UIManager.put(key, tinted)
            tintedBackgrounds[key] = tinted
        }
        onBackgroundsTinted(tintedBackgrounds)
        writeForegrounds(tintedBackgrounds)
        val samplingBg = tintedBackgrounds.values.firstOrNull()
        if (samplingBg != null) {
            refreshPeer(samplingBg)
        }
    }

    override fun revert() {
        for (key in backgroundKeys) {
            UIManager.put(key, null)
        }
        for (key in foregroundKeys) {
            UIManager.put(key, null)
        }
        clearPeer()
    }

    /**
     * Hook for subclasses that need to observe which bg keys resolved vs missed
     * without rewriting the whole apply template. Default no-op. PanelBorder and
     * ToolWindowStripe use this for their first-apply M-3 diagnostic log.
     */
    protected open fun onBackgroundsTinted(tintedBackgrounds: Map<String, Color>) {
        // default no-op
    }

    /**
     * Default single-contrast fg write: samples [WcagForeground.pickForeground]
     * against the first tinted background and writes that color to every entry
     * in [foregroundKeys]. Subclasses with per-bg fg picks (StatusBar M-1,
     * ToolWindowStripe A-2) override this.
     */
    protected open fun writeForegrounds(tintedBackgrounds: Map<String, Color>) {
        if (foregroundKeys.isEmpty()) return
        val samplingBg = tintedBackgrounds.values.firstOrNull() ?: return
        val contrast = WcagForeground.pickForeground(samplingBg, foregroundTextTarget)
        for (key in foregroundKeys) {
            UIManager.put(key, contrast)
        }
    }

    /**
     * Dispatches the live peer refresh to [LiveChromeRefresher] per the typed
     * [peerTarget]. Phase 40.3c Refactor 2 collapsed the six entry points into
     * `LiveChromeRefresher.refresh(target, color)`; subclasses never touch the
     * LiveChromeRefresher surface directly.
     */
    private fun refreshPeer(color: Color) {
        val target = peerTarget ?: return
        LiveChromeRefresher.refresh(target, color)
    }

    /** D-14 symmetry mirror of [refreshPeer] — unconditional, ignores [isEnabled]. */
    private fun clearPeer() {
        val target = peerTarget ?: return
        LiveChromeRefresher.clear(target)
    }

    /**
     * Helper for subclasses that need to look up a [ClassFqn] for a constant peer
     * class name. Exposed here so per-element code can keep its PEER_CLASS string
     * constants collocated with the element's KDoc (where the javap verification
     * reasoning lives) without each subclass having to re-import [ClassFqn].
     */
    protected fun classFqn(value: String): ClassFqn = ClassFqn.require(value)
}
