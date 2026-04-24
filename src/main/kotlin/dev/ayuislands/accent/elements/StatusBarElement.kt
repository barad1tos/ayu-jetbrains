package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.accent.WcagForeground
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import javax.swing.UIManager

/**
 * Tints the status bar background, border, and widget hover per CHROME-01 / CHROME-07.
 *
 * Intensity is read directly from [AyuIslandsSettings.state] per CONTEXT D-03 — the
 * `apply(color)` signature does not carry it, so every Phase 40 chrome element pulls
 * the same live settings. WCAG-aware foreground contrast is always applied — the
 * earlier opt-out toggle was retired because saturated tints without readable
 * foregrounds failed the user-space quality bar.
 *
 * `revert()` nulls every touched UIManager key so the LAF re-resolves the stock
 * theme value (D-14 / CHROME-08). Both the background keys and the foreground keys
 * are nulled unconditionally so a previously-applied contrast color cannot leak
 * after the user disables chrome tinting.
 */
class StatusBarElement : AccentElement {
    override val id = AccentElementId.STATUS_BAR
    override val displayName = "Status bar"

    private val backgroundKeys =
        listOf(
            STATUS_BAR_BG_KEY,
            "StatusBar.borderColor",
            STATUS_WIDGET_HOVER_BG_KEY,
        )

    // Foreground keys receive the WcagForeground-picked contrast color sampled against
    // the tinted StatusBar background. Breadcrumb foregrounds are included so the
    // path breadcrumb ("project > build.gradle.kts") inherits the same contrast-aware
    // colour as the rest of the status bar widget text — without them, breadcrumbs
    // stayed LAF-grey and became unreadable once the status bar was tinted.
    //
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
    // platformVersion 2026.1 (ide.impl/themes/metadata) are written. Verified via
    // `unzip -p intellij.platform.ide.impl.jar themes/metadata/IntelliJPlatform.themeMetadata.json`
    // on IDE 2026.1:
    //   - StatusBar.Breadcrumbs.foreground       — PRESENT
    //   - StatusBar.Breadcrumbs.hoverForeground  — PRESENT
    // Keys that do NOT exist in 2026.1 metadata (do NOT add — silent writes to
    // non-existent UIManager keys are dead bytes and lie to future readers):
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

    override fun apply(color: Color) {
        val intensity = AyuIslandsSettings.getInstance().state.effectiveChromeTintIntensity()
        var tintedBackground: Color? = null
        var tintedHoverBackground: Color? = null
        for (key in backgroundKeys) {
            val baseColor = ChromeBaseColors.get(key) ?: continue
            val tinted = ChromeTintBlender.blend(color, baseColor, intensity)
            UIManager.put(key, tinted)
            when (key) {
                STATUS_BAR_BG_KEY -> tintedBackground = tinted
                STATUS_WIDGET_HOVER_BG_KEY -> tintedHoverBackground = tinted
            }
        }
        if (tintedBackground != null) {
            val contrast = WcagForeground.pickForeground(tintedBackground, WcagForeground.TextTarget.PRIMARY_TEXT)
            for (key in normalForegroundKeys) {
                UIManager.put(key, contrast)
            }
        }
        if (tintedHoverBackground != null) {
            val hoverContrast =
                WcagForeground.pickForeground(tintedHoverBackground, WcagForeground.TextTarget.PRIMARY_TEXT)
            for (key in hoverForegroundKeys) {
                UIManager.put(key, hoverContrast)
            }
        }
        // Level 2 Gap-4: push the tinted bg to the live IdeStatusBarImpl peer because
        // UIManager writes don't propagate to already-rendered chrome (CHROME-07).
        tintedBackground?.let { LiveChromeRefresher.refreshStatusBar(it) }
    }

    override fun revert() {
        for (key in backgroundKeys + normalForegroundKeys + hoverForegroundKeys) {
            UIManager.put(key, null)
        }
        // D-14 symmetry: hand the status bar peer back to LAF default.
        LiveChromeRefresher.clearStatusBar()
    }

    private companion object {
        const val STATUS_BAR_BG_KEY = "StatusBar.background"
        const val STATUS_WIDGET_HOVER_BG_KEY = "StatusBar.Widget.hoverBackground"
    }
}
