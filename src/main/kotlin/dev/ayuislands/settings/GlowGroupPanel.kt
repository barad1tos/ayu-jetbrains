package dev.ayuislands.settings

import com.intellij.util.ui.JBUI
import dev.ayuislands.glow.GlowRenderer
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.BeatMorphology
import dev.ayuislands.glow.waveform.FrameBeat
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformFrame
import dev.ayuislands.glow.waveform.WaveformMotion
import dev.ayuislands.glow.waveform.WaveformPaintRequest
import dev.ayuislands.glow.waveform.WaveformPainter
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import kotlin.random.Random

internal data class GlowPreview(
    val shape: GlowShape,
    val style: GlowStyle,
    val intensity: Int,
    val width: Int,
    val color: Color,
    val visible: Boolean,
    val waveformConfig: WaveformConfig,
)

/**
 * JPanel that paints a glow border around its content using [GlowRenderer].
 *
 * The glow is clipped to a donut-shaped border zone (outer minus inner rect),
 * then an opaque fill covers the inner content area so children render cleanly.
 */
class GlowGroupPanel : JPanel(BorderLayout()) {
    var glowStyle: GlowStyle = GlowStyle.SOFT
    var glowIntensity: Int = DEFAULT_INTENSITY
    var glowWidth: Int = DEFAULT_WIDTH
    var glowColor: Color = Color.decode(DEFAULT_COLOR_HEX)
    var glowVisible: Boolean = false
    var glowShape: GlowShape = GlowShape.SOLID
    var waveformConfig: WaveformConfig = WaveformConfig()

    private val renderer = GlowRenderer()
    private val waveformPainter = WaveformPainter()
    private val previewMorphology = BeatMorphology.random(Random(WaveformPainter.STATIC_MORPHOLOGY_SEED))

    init {
        border = JBUI.Borders.empty(FIXED_PADDING)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!glowVisible) return
        if (glowShape == GlowShape.SOLID && glowIntensity <= 0) return
        if (glowShape == GlowShape.WAVEFORM && waveformConfig.intensity <= 0) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.composite = AlphaComposite.SrcOver.derive(OVERLAY_ALPHA)

            if (glowShape == GlowShape.WAVEFORM) {
                paintWaveform(g2)
                return
            }

            val outer =
                Area(
                    RoundRectangle2D.Float(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        ARC_F,
                        ARC_F,
                    ),
                )
            val ins = insets
            val inner =
                Area(
                    RoundRectangle2D.Float(
                        ins.left.toFloat(),
                        ins.top.toFloat(),
                        (width - ins.left - ins.right).toFloat(),
                        (height - ins.top - ins.bottom).toFloat(),
                        INNER_ARC_F,
                        INNER_ARC_F,
                    ),
                )
            outer.subtract(inner)
            g2.clip(outer)

            renderer.ensureCache(glowColor, glowStyle, glowIntensity, glowWidth)
            renderer.paintGlow(g2, Rectangle(0, 0, width, height), glowWidth, ARC_F.toInt())
        } finally {
            g2.dispose()
        }

        // Opaque fill over inner content area so children render cleanly
        val g3 = g.create() as Graphics2D
        try {
            val ins = insets
            g3.color = background
            g3.fillRoundRect(
                ins.left,
                ins.top,
                width - ins.left - ins.right,
                height - ins.top - ins.bottom,
                INNER_ARC_RADIUS,
                INNER_ARC_RADIUS,
            )
        } finally {
            g3.dispose()
        }
    }

    internal fun updatePreview(preview: GlowPreview) {
        glowShape = preview.shape
        glowStyle = preview.style
        glowIntensity = preview.intensity
        glowWidth = preview.width
        glowColor = preview.color
        glowVisible = preview.visible
        waveformConfig = preview.waveformConfig
        val contentInset =
            if (preview.shape == GlowShape.WAVEFORM) {
                WaveformPainter.marginFor(preview.waveformConfig.amplitude).toInt() + WAVEFORM_CONTENT_CLEARANCE
            } else {
                FIXED_PADDING
            }
        border = JBUI.Borders.empty(contentInset)
        revalidate()
        repaint()
    }

    private fun paintWaveform(graphics: Graphics2D) {
        val bounds = Rectangle(0, 0, width, height)
        val previewConfig = waveformConfig.copy(motion = WaveformMotion.MONITOR)
        val trackLength = waveformPainter.trackLength(bounds, ARC_F.toInt(), previewConfig, isEditorOverlay = false)
        val frame =
            WaveformFrame(
                nowMs = 0L,
                config = previewConfig,
                beats =
                    listOf(
                        FrameBeat(
                            centerDistance = trackLength * WaveformPainter.STATIC_CENTER_FRACTION,
                            morphology = previewMorphology,
                            opacity = 1f,
                        ),
                    ),
            )
        waveformPainter.paint(
            graphics,
            WaveformPaintRequest(
                bounds = bounds,
                arcWidth = ARC_F.toInt(),
                accent = glowColor,
                frame = frame,
                isEditorOverlay = false,
            ),
        )
    }

    companion object {
        private const val ARC_F = 24f
        private const val INNER_ARC_F = 16f
        private const val INNER_ARC_RADIUS = 8
        private const val OVERLAY_ALPHA = 0.8f
        private const val DEFAULT_INTENSITY = 35
        private const val DEFAULT_WIDTH = 8
        private const val DEFAULT_COLOR_HEX = "#FFCC66"
        private const val FIXED_PADDING = 10
        private const val WAVEFORM_CONTENT_CLEARANCE = 13
    }
}
