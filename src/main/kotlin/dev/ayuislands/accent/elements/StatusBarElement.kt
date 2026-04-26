package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.WcagForeground
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints the status bar surface per CHROME-01 / CHROME-07.
 *
 * Phase 40.3c Refactor 1: migrated to [AbstractChromeElement]. [writeForegrounds] is
 * overridden because StatusBar uses a light-family foreground and extends that pick
 * across the 2026.1 Compact Navigation breadcrumb states.
 *
 * Phase 40.4 — extended key coverage for IntelliJ 2026.1 Compact Navigation. The path
 * widget that used to live in `MyNavBarWrapperPanel` is now rendered by
 * `com.intellij.platform.navbar.frontend.ui.NavBarItemComponent` mounted inside
 * `NewNavBarPanel` in the status bar tree. Per
 * `JBUI.CurrentTheme.StatusBar.Breadcrumbs` (intellij-community
 * `platform/util/ui/src/com/intellij/util/ui/JBUI.java`), the widget reads colors
 * state-dependent across **9 UIDefaults keys**:
 *
 *   - foreground / hoverForeground / floatingForeground / selectionForeground / selectionInactiveForeground
 *   - hoverBackground / selectionBackground / selectionInactiveBackground / floatingBackground
 *
 * Foreground states receive the status-bar contrast color. Background states are
 * translucent overlays in the LAF, so this element clears any stale plugin override
 * and lets them render over the directly-tinted `NewNavBarPanel` background.
 *
 * Foreground contrast uses [WcagForeground.pickLightFamilyForeground] (palette WHITE
 * + Ayu dark fg, NO black). The standard 3-color WCAG sweep correctly picks BLACK on
 * mid-luminance tinted bg (BLACK passes 5:1 there while WHITE drops to ~4:1), but on
 * a chrome surface we semantically own as a "dark tinted band" the user expects light
 * text. Restricting the palette restores the pre-Phase 40 always-light contract.
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
        )

    private val overlayBackgroundKeys =
        listOf(
            "StatusBar.Widget.hoverBackground",
            "StatusBar.Breadcrumbs.hoverBackground",
            "StatusBar.Breadcrumbs.selectionBackground",
            "StatusBar.Breadcrumbs.selectionInactiveBackground",
            "StatusBar.Breadcrumbs.floatingBackground",
        )

    // Phase 40.4 — covers ALL 9 JBUI.CurrentTheme.StatusBar.Breadcrumbs.* fg states
    // referenced by NavBarItemComponent.update(). Without writing every state, the
    // path widget falls through to stock UIUtil.getLabelForeground() the moment the
    // user hovers / NavBar promotes to floating mode / scope item is selected — a
    // dark grey on Mirage that reads as "broken contrast" against the tinted bg.
    //
    // Source-of-truth: intellij-community `platform/navbar/frontend/src/ui/NavBarItemComponent.kt`
    // `update()`:
    //   when {
    //     isHovered  -> Breadcrumbs.HOVER_FOREGROUND
    //     selected   -> if (focused) SELECTION_FOREGROUND else SELECTION_INACTIVE_FOREGROUND
    //     else       -> if (isFloating) FLOATING_FOREGROUND else FOREGROUND
    //   }
    //
    // Only keys confirmed present in JBUI.CurrentTheme.StatusBar.Breadcrumbs interface
    // (intellij-community `platform/util/ui/src/com/intellij/util/ui/JBUI.java`).
    // Keys absent (do NOT add — silent writes to non-existent UIDefaults are dead
    // bytes that lie to future readers):
    //   - Breadcrumbs.CurrentColor               — ABSENT in 2026.1
    //   - Breadcrumbs.InactiveColor              — ABSENT in 2026.1
    //   - Breadcrumbs.HoverColor                 — ABSENT in 2026.1
    private val normalForegroundKeys =
        listOf(
            "StatusBar.Widget.foreground",
            "StatusBar.Breadcrumbs.foreground",
            "StatusBar.Breadcrumbs.floatingForeground",
            "StatusBar.Breadcrumbs.selectionForeground",
            "StatusBar.Breadcrumbs.selectionInactiveForeground",
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
     * Single foreground pick sampled against [STATUS_BAR_BG_KEY]. Hover/selection
     * backgrounds are translucent overlays over the same chrome band, not opaque
     * standalone fills; sampling them directly would choose the dark-family fallback
     * for what is visually still a dark status-bar surface.
     */
    override fun writeForegrounds(tintedBackgrounds: Map<String, Color>) {
        val tintedBg = tintedBackgrounds[STATUS_BAR_BG_KEY] ?: return
        val contrast = WcagForeground.pickLightFamilyForeground(tintedBg, foregroundTextTarget)
        for (key in foregroundKeys) {
            UIManager.put(key, contrast)
        }
    }

    override fun onBackgroundsTinted(tintedBackgrounds: Map<String, Color>) {
        clearOverlayBackgrounds()
    }

    override fun revert() {
        super.revert()
        clearOverlayBackgrounds()
    }

    private fun clearOverlayBackgrounds() {
        for (key in overlayBackgroundKeys) {
            UIManager.put(key, null)
        }
    }

    private companion object {
        const val STATUS_BAR_BG_KEY = "StatusBar.background"
    }
}
