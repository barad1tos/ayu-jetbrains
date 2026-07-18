package dev.ayuislands.settings

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import dev.ayuislands.glow.GlowRenderer
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.BeatMorphology
import dev.ayuislands.glow.waveform.FrameTrace
import dev.ayuislands.glow.waveform.SolidFrameSpec
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformEdge
import dev.ayuislands.glow.waveform.WaveformEngine
import dev.ayuislands.glow.waveform.WaveformEvent
import dev.ayuislands.glow.waveform.WaveformFrame
import dev.ayuislands.glow.waveform.WaveformPaintRequest
import dev.ayuislands.glow.waveform.WaveformPainter
import dev.ayuislands.glow.waveform.brightnessAt
import dev.ayuislands.glow.waveform.paint
import dev.ayuislands.glow.waveform.traceComplexCount
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.HierarchyEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.ceil
import kotlin.math.roundToInt
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
    private val waveformPainter = WaveformPainter(renderer)
    private val previewMorphology = BeatMorphology.standard()
    private val previewEngine = WaveformEngine(waveformConfig, Random(PREVIEW_RANDOM_SEED))
    private var waveformFrame = restingPreviewFrame(waveformConfig)
    private var powerSaveConnection: MessageBusConnection? = null
    private val previewTimer =
        Timer(TIMER_DELAY_MS) {
            advanceWaveformPreview(System.currentTimeMillis())
        }.apply {
            isCoalesce = true
        }

    init {
        border = JBUI.Borders.empty(FIXED_PADDING)
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                syncPreviewAnimation()
            }
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!glowVisible) return
        if (glowShape == GlowShape.SOLID && glowIntensity <= 0) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            if (glowShape == GlowShape.WAVEFORM) {
                paintWaveform(g2)
                return
            }

            g2.composite = AlphaComposite.SrcOver.derive(OVERLAY_ALPHA)

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
        previewEngine.handle(WaveformEvent.Configure(waveformConfig))
        waveformFrame =
            if (preview.shape == GlowShape.WAVEFORM) {
                waveformFrame.copy(
                    config = waveformConfig,
                    trace = waveformFrame.trace ?: restingPreviewFrame(waveformConfig).trace,
                    brightness = waveformConfig.brightnessAt(waveformFrame.energy),
                )
            } else {
                previewEngine.handle(WaveformEvent.Deactivate)
                restingPreviewFrame(waveformConfig)
            }
        syncPreviewAnimation()
        repaint()
    }

    override fun addNotify() {
        super.addNotify()
        subscribePowerSave()
        syncPreviewAnimation()
    }

    override fun removeNotify() {
        previewTimer.stop()
        previewEngine.handle(WaveformEvent.Deactivate)
        powerSaveConnection?.disconnect()
        powerSaveConnection = null
        super.removeNotify()
    }

    internal fun advanceWaveformPreview(nowMs: Long) {
        if (!shouldAnimatePreview()) return

        previewEngine.handle(WaveformEvent.Activate(powerSaveEnabled = false)).frame?.let { waveformFrame = it }
        val bounds = waveformBounds()
        val trackLength = waveformPainter.trackLength(bounds, ARC_F.toInt(), waveformConfig, glowWidth)
        previewEngine.handle(WaveformEvent.Tick(nowMs, trackLength)).frame?.let { waveformFrame = it }
        repaint()
    }

    private fun paintWaveform(graphics: Graphics2D) {
        val bounds = waveformBounds()
        val gutter = JBUI.scale(PREVIEW_GUTTER)
        val previewBand = Area(Rectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat()))
        val protectedInset = previewBandDepth()
        val protectedContent =
            Rectangle2D.Float(
                protectedInset.toFloat(),
                protectedInset.toFloat(),
                (width - protectedInset * 2).coerceAtLeast(0).toFloat(),
                (height - protectedInset * 2).coerceAtLeast(0).toFloat(),
            )
        previewBand.subtract(Area(protectedContent))
        graphics.clip(previewBand)
        waveformPainter.paint(
            graphics,
            WaveformPaintRequest(
                bounds = bounds,
                arcWidth = ARC_F.toInt(),
                accent = glowColor,
                frame = waveformFrame,
                solidFrame =
                    SolidFrameSpec(
                        bounds =
                            Rectangle(
                                gutter,
                                gutter,
                                (width - gutter * 2).coerceAtLeast(0),
                                (height - gutter * 2).coerceAtLeast(0),
                            ),
                        style = glowStyle,
                        intensity = glowIntensity,
                        width = glowWidth,
                    ),
                inwardEdges = PREVIEW_INWARD_EDGES,
            ),
        )
    }

    private fun waveformBounds(): Rectangle {
        val margin = WaveformPainter.marginFor(waveformConfig.amplitude, glowWidth)
        val gutter = JBUI.scale(PREVIEW_GUTTER)
        val shift = (margin - gutter).roundToInt().coerceAtLeast(0)
        return Rectangle(-shift, -shift, width + shift * 2, height + shift * 2)
    }

    private fun previewBandDepth(): Int =
        ceil(
            JBUI.scale(PREVIEW_GUTTER) +
                WaveformPainter.baselineInsetFor(waveformConfig.baseline, glowWidth) +
                WaveformPainter.marginFor(waveformConfig.amplitude, glowWidth),
        ).toInt()

    private fun restingPreviewFrame(config: WaveformConfig): WaveformFrame =
        WaveformFrame(
            config = config,
            trace =
                FrameTrace(
                    anchorOffset = 0f,
                    history = List(config.traceComplexCount) { previewMorphology },
                ),
            brightness = config.brightnessAt(0f),
        )

    internal fun syncPreviewAnimation(
        showing: Boolean,
        powerSaveEnabled: Boolean,
    ) {
        if (showing && shouldAnimatePreview() && !powerSaveEnabled) {
            previewEngine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
            previewTimer.start()
        } else {
            previewTimer.stop()
            previewEngine.handle(WaveformEvent.Deactivate)
            waveformFrame = restingPreviewFrame(waveformConfig)
        }
    }

    internal val isPreviewAnimating: Boolean
        get() = previewTimer.isRunning

    private fun syncPreviewAnimation() {
        val showing = isShowing
        syncPreviewAnimation(
            showing = showing,
            powerSaveEnabled = showing && PowerSaveMode.isEnabled(),
        )
    }

    private fun subscribePowerSave() {
        if (powerSaveConnection != null) return
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(
            PowerSaveMode.TOPIC,
            PowerSaveMode.Listener {
                if (SwingUtilities.isEventDispatchThread()) {
                    syncPreviewAnimation()
                } else {
                    SwingUtilities.invokeLater(::syncPreviewAnimation)
                }
            },
        )
        powerSaveConnection = connection
    }

    private fun shouldAnimatePreview(): Boolean = glowVisible && glowShape == GlowShape.WAVEFORM

    companion object {
        private const val ARC_F = 24f
        private const val INNER_ARC_F = 16f
        private const val INNER_ARC_RADIUS = 8
        private const val OVERLAY_ALPHA = 0.8f
        private const val DEFAULT_INTENSITY = 35
        private const val DEFAULT_WIDTH = 8
        private const val DEFAULT_COLOR_HEX = "#FFCC66"
        private const val FIXED_PADDING = 10
        private const val PREVIEW_GUTTER = 4
        private const val TIMER_DELAY_MS = 33
        private const val PREVIEW_RANDOM_SEED = 27
        private val PREVIEW_INWARD_EDGES = WaveformEdge.entries.toSet()
    }
}
