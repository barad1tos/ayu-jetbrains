package dev.ayuislands.settings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.vcs.VcsColorBlender
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPalette
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsIntensity
import dev.ayuislands.vcs.VcsWriteMode
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import kotlin.math.roundToInt

/** Compact visual preview for Diff modified / inserted / deleted colors in the VCS settings tab. */
internal class VcsDiffPreviewComponent(
    private var variant: AyuVariant,
    private var intensity: Int = VcsColorPreset.AMBIENT_SLIDER,
) : JComponent() {
    init {
        isOpaque = false
        toolTipText = "Diff preview"
    }

    fun updatePreview(
        variant: AyuVariant,
        intensity: Int,
    ) {
        this.variant = variant
        this.intensity = intensity.coerceIn(VcsIntensity.MIN, VcsIntensity.MAX)
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(PREVIEW_WIDTH), JBUI.scale(PREVIEW_HEIGHT))

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val surface = editorSurface()
            val arc = JBUI.scale(ARC)
            g2.color = surface
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

            val padding = JBUI.scale(PADDING)
            val rowHeight = JBUI.scale(ROW_HEIGHT)
            val gap = JBUI.scale(ROW_GAP)
            var y = padding
            for (row in ROWS) {
                paintRow(g2, row, padding, y, rowHeight)
                y += rowHeight + gap
            }
        } finally {
            g2.dispose()
        }
    }

    private fun paintRow(
        g2: Graphics2D,
        row: PreviewRow,
        x: Int,
        y: Int,
        height: Int,
    ) {
        val stripeWidth = JBUI.scale(STRIPE_WIDTH)
        val stripeGap = JBUI.scale(STRIPE_GAP)
        val rowWidth = width - x * 2
        val background = compositeOverSurface(rowBackground(row.keyName), editorSurface())

        g2.color = background
        g2.fillRect(x, y, rowWidth, height)
        g2.color = stripeColor(row.keyName)
        g2.fillRect(x, y, stripeWidth, height)

        g2.font = JBUI.Fonts.smallFont()
        g2.color = UIUtil.getLabelForeground()
        val baseline = y + (height - g2.fontMetrics.height) / 2 + g2.fontMetrics.ascent
        g2.drawString(row.text, x + stripeWidth + stripeGap, baseline)
    }

    private fun rowBackground(keyName: String): Color = blendedColor(keyName, VcsWriteMode.TEXT_ATTR_BG)

    private fun stripeColor(keyName: String): Color = blendedColor(keyName, VcsWriteMode.COLOR_KEY)

    private fun blendedColor(
        keyName: String,
        mode: VcsWriteMode,
    ): Color {
        val entry =
            VcsColorPalette.entriesFor(VcsColorCategory.DIFF_VIEWER).first {
                it.keyName == keyName && it.mode == mode
            }
        val (base, target) = VcsColorPalette.endpoints(entry, variant)
        return VcsColorBlender.blend(base, target, VcsIntensity.of(intensity))
    }

    private fun editorSurface(): Color =
        when (variant) {
            AyuVariant.DARK -> DARK_SURFACE
            AyuVariant.MIRAGE -> MIRAGE_SURFACE
            AyuVariant.LIGHT -> LIGHT_SURFACE
        }

    private fun compositeOverSurface(
        foreground: Color,
        surface: Color,
    ): Color {
        val alpha = foreground.alpha / CHANNEL_MAX.toDouble()
        val inverseAlpha = 1.0 - alpha
        return Color(
            blendChannel(foreground.red, surface.red, alpha, inverseAlpha),
            blendChannel(foreground.green, surface.green, alpha, inverseAlpha),
            blendChannel(foreground.blue, surface.blue, alpha, inverseAlpha),
        )
    }

    private fun blendChannel(
        foreground: Int,
        surface: Int,
        alpha: Double,
        inverseAlpha: Double,
    ): Int = (foreground * alpha + surface * inverseAlpha).roundToInt().coerceIn(0, CHANNEL_MAX)

    @TestOnly
    internal fun rowBackgroundForTest(keyName: String): Color = rowBackground(keyName)

    @TestOnly
    internal fun intensityForTest(): Int = intensity

    private data class PreviewRow(
        val keyName: String,
        val text: String,
    )

    private companion object {
        private const val PREVIEW_WIDTH = 420
        private const val PREVIEW_HEIGHT = 86
        private const val PADDING = 8
        private const val ROW_HEIGHT = 20
        private const val ROW_GAP = 4
        private const val STRIPE_WIDTH = 4
        private const val STRIPE_GAP = 8
        private const val ARC = 8
        private const val CHANNEL_MAX = 255

        private val DARK_SURFACE = Color(0x0D1017)
        private val MIRAGE_SURFACE = Color(0x1F2430)
        private val LIGHT_SURFACE = Color(0xFAFAFA)

        private val ROWS =
            listOf(
                PreviewRow("DIFF_DELETED", "- removed stale branch color"),
                PreviewRow("DIFF_INSERTED", "+ added focused preview row"),
                PreviewRow("DIFF_MODIFIED", "~ updated diff highlight"),
            )
    }
}
