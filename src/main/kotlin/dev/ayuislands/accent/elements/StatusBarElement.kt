package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.WcagForeground
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints the status bar background, border, and widget hover per CHROME-01 / CHROME-07.
 *
 * Phase 40.3c Refactor 1: migrated to [AbstractChromeElement]. [writeForegrounds] is
 * overridden because StatusBar needs the Phase 40.2 M-1 split-fg pick — normal and
 * hover foregrounds are sampled against their own tinted backgrounds rather than
 * sharing a single contrast pick.
 *
 * Intensity is read directly from [dev.ayuislands.settings.AyuIslandsSettings.state]
 * per CONTEXT D-03 — the `apply(color)` signature does not carry it, so every Phase
 * 40 chrome element pulls the same live settings. WCAG-aware foreground contrast is
 * always applied — the earlier opt-out toggle was retired because saturated tints
 * without readable foregrounds failed the user-space quality bar.
 *
 * `revert()` nulls every touched UIManager key so the LAF re-resolves the stock
 * theme value (D-14 / CHROME-08). Both the background keys and the foreground keys
 * are nulled unconditionally so a previously-applied contrast color cannot leak
 * after the user disables chrome tinting.
 */
class StatusBarElement : AbstractChromeElement() {
    override val id = AccentElementId.STATUS_BAR
    override val displayName = "Status bar"

    override val backgroundKeys =
        listOf(
            STATUS_BAR_BG_KEY,
            "StatusBar.borderColor",
            STATUS_WIDGET_HOVER_BG_KEY,
        )

    // Phase 40.2 M-1: foregrounds split into two pairs sampled against their own bg.
    // Previously one contrast pick from StatusBar.background drove all four fg keys,
    // but Widget.hoverForeground sits on StatusBar.Widget.hoverBackground — a
    // different tinted surface. At non-trivial intensities the two bg tints diverge
    // and a shared contrast pick can drop the hover foreground under the WCAG AA
    // ratio. Mirrors the Round 2 A-2 split already applied in ToolWindowStripeElement.
    //   - Normal pair: (Widget.foreground + Breadcrumbs.foreground) vs StatusBar.background
    //   - Hover pair:  (Widget.hoverForeground + Breadcrumbs.hoverForeground)
    //                  vs StatusBar.Widget.hoverBackground
    //
    // Only keys confirmed present in IntelliJPlatform.themeMetadata.json for
    // platformVersion 2026.1 (ide.impl/themes/metadata) are written. Keys that do NOT
    // exist in 2026.1 metadata (do NOT add — silent writes to non-existent UIManager
    // keys are dead bytes and lie to future readers):
    //   - Breadcrumbs.CurrentColor               — ABSENT in 2026.1
    //   - Breadcrumbs.InactiveColor              — ABSENT in 2026.1
    //   - Breadcrumbs.HoverColor                 — ABSENT in 2026.1
    private val normalForegroundKeys =
        listOf(
            "StatusBar.Widget.foreground",
            "StatusBar.Breadcrumbs.foreground",
        )

    private val hoverForegroundKeys =
        listOf(
            "StatusBar.Widget.hoverForeground",
            "StatusBar.Breadcrumbs.hoverForeground",
        )

    /** Flat aggregation used by the base class's revert(); writes happen via [writeForegrounds]. */
    override val foregroundKeys: List<String> = normalForegroundKeys + hoverForegroundKeys

    override val foregroundTextTarget = WcagForeground.TextTarget.PRIMARY_TEXT

    override val peerTarget: ChromeTarget = ChromeTarget.StatusBar

    /**
     * M-1 split-fg pick: two independent contrast samples, one per tinted bg surface.
     * Breadcrumb foregrounds are included so the path breadcrumb inherits the same
     * contrast-aware color as the rest of the status bar widget text.
     */
    override fun writeForegrounds(tintedBackgrounds: Map<String, Color>) {
        val tintedBg = tintedBackgrounds[STATUS_BAR_BG_KEY]
        if (tintedBg != null) {
            val contrast = WcagForeground.pickForeground(tintedBg, foregroundTextTarget)
            for (key in normalForegroundKeys) {
                UIManager.put(key, contrast)
            }
        }
        val tintedHoverBg = tintedBackgrounds[STATUS_WIDGET_HOVER_BG_KEY]
        if (tintedHoverBg != null) {
            val hoverContrast = WcagForeground.pickForeground(tintedHoverBg, foregroundTextTarget)
            for (key in hoverForegroundKeys) {
                UIManager.put(key, hoverContrast)
            }
        }
    }

    private companion object {
        const val STATUS_BAR_BG_KEY = "StatusBar.background"
        const val STATUS_WIDGET_HOVER_BG_KEY = "StatusBar.Widget.hoverBackground"
    }
}
