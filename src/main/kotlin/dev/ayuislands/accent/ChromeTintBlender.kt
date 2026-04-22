package dev.ayuislands.accent

import com.intellij.ui.ColorUtil
import java.awt.Color
import javax.swing.UIManager

/**
 * Pure color-math foundation for Phase 40 chrome tinting.
 *
 * Every chrome accent element (`StatusBar`, `MainToolbar`, `ToolWindowStripe`,
 * `NavBar`, `PanelBorder`) consumes these two helpers so the blend math lives
 * in exactly one place. Lifted from
 * [dev.ayuislands.accent.elements.SearchResultsElement.blendWithBackground] and
 * [dev.ayuislands.accent.AccentApplicator]'s contrast block (lines 55-56, 389)
 * per phase decisions D-04, D-05, D-06.
 */
object ChromeTintBlender {
    private const val MAX_CHANNEL_VALUE = 255
    private const val ROUNDING_BIAS = 0.5f
    private const val MIN_INTENSITY = 0
    private const val MAX_INTENSITY = 100
    private const val INTENSITY_TO_RATIO = 100f
    private const val DARK_FOREGROUND_HEX = 0x1F2430
    private const val PANEL_BACKGROUND_KEY = "Panel.background"

    private val DARK_FOREGROUND = Color(DARK_FOREGROUND_HEX)

    /**
     * Linear per-channel RGB lerp between the stock theme color behind [baseKey]
     * and the resolved [accent]. Output is always opaque (alpha=255) per D-05 —
     * translucent chrome would bleed through to native OS surfaces.
     *
     * @param accent resolved accent color (alpha channel ignored)
     * @param baseKey UIManager key naming the stock theme color to lerp against
     * @param intensityPercent 0-100 mix ratio; out-of-range values clamp without throwing
     * @return opaque [Color] at the requested blend ratio
     */
    fun blend(
        accent: Color,
        baseKey: String,
        intensityPercent: Int,
    ): Color {
        val clamped = intensityPercent.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        val background =
            UIManager.getColor(baseKey)
                ?: UIManager.getColor(PANEL_BACKGROUND_KEY)
                ?: accent
        val ratio = clamped.toFloat() / INTENSITY_TO_RATIO
        // 3-arg Color(r,g,b) ctor guarantees alpha=255 (D-05)
        return Color(
            lerpChannel(background.red, accent.red, ratio),
            lerpChannel(background.green, accent.green, ratio),
            lerpChannel(background.blue, accent.blue, ratio),
        )
    }

    /**
     * Contrast-aware foreground for text painted on top of a [tinted] chrome
     * background. Returns [Color.WHITE] when the background reads as dark per
     * [ColorUtil.isDark]; otherwise returns the Ayu dark-foreground constant
     * `0x1F2430` (mirrors `AccentApplicator.DARK_FOREGROUND`).
     */
    fun contrastForeground(tinted: Color): Color = if (ColorUtil.isDark(tinted)) Color.WHITE else DARK_FOREGROUND

    private fun lerpChannel(
        from: Int,
        to: Int,
        ratio: Float,
    ): Int = (from + (to - from) * ratio + ROUNDING_BIAS).toInt().coerceIn(0, MAX_CHANNEL_VALUE)
}
