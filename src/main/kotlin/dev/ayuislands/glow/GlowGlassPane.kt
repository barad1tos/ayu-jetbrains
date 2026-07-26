package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.glow.waveform.SolidFrameSpec
import dev.ayuislands.glow.waveform.TimerDirective
import dev.ayuislands.glow.waveform.TravelDirection
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformEdge
import dev.ayuislands.glow.waveform.WaveformEngine
import dev.ayuislands.glow.waveform.WaveformEvent
import dev.ayuislands.glow.waveform.WaveformFrame
import dev.ayuislands.glow.waveform.WaveformPaintRequest
import dev.ayuislands.glow.waveform.WaveformPainter
import dev.ayuislands.glow.waveform.WaveformRenderPlan
import dev.ayuislands.glow.waveform.WaveformTrack
import dev.ayuislands.glow.waveform.WaveformTrackSpec
import dev.ayuislands.glow.waveform.WaveformUpdate
import dev.ayuislands.glow.waveform.brightnessAt
import dev.ayuislands.glow.waveform.fixedDirection
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.UIManager

/**
 * Transparent overlay that paints the neon glow on top of tool window content.
 * Added as z-order 0 child of the island host container.
 * Mouse events pass through via contains() returning false.
 * Ignores layout managers to stay positioned over the full host area.
 */
class GlowGlassPane(
    var glowColor: Color,
    var glowStyle: GlowStyle,
    var glowIntensity: Int,
    var glowWidth: Int,
    var isEditorOverlay: Boolean = false,
    var glowPlacement: GlowPlacement = GlowPlacement.ISLAND,
) : JPanel(null) {
    private val log = logger<GlowGlassPane>()
    private val renderer = GlowRenderer()
    private var waveformPainter = WaveformPainter(renderer)
    private var fadeAlpha: Float = 0.0f
    private var fadeTimer: Timer? = null
    private var waveformTimer: Timer? = null
    private var waveformEngine: WaveformEngine? = null
    private var waveformFrame: WaveformFrame? = null
    private var waveformPlan: WaveformRenderPlan? = null
    private var isRouteMode = false
    internal var waveformTopSpans: List<IntRange> = emptyList()
        set(value) {
            val snapshot = value.map { it.first..it.last }
            if (field == snapshot) return
            field = snapshot
            waveformPlan = null
            repaint()
        }
    internal var waveformInwardEdges: Set<WaveformEdge> = emptySet()
        set(value) {
            if (field == value) return
            field = value.toSet()
            waveformPlan = null
            repaint()
        }
    internal var topSpansProvider: (() -> List<IntRange>)? = null
    internal var timeSource: () -> Long = System::currentTimeMillis
    private var topSpansRefreshAtMs = 0L
    private var waveformConfig = WaveformConfig()
    private var glowShape = GlowShape.SOLID
    private var waveformFailed = false
    private var waveformFailureLogged = false
    private var slowWaveformLogged = false
    private var topSpansFailureLogged = false

    internal val isWaveform: Boolean
        get() = glowShape == GlowShape.WAVEFORM && !waveformFailed

    internal val usesWaveformBounds: Boolean
        get() = glowShape == GlowShape.WAVEFORM

    internal val waveformMargin: Int
        get() = WaveformPainter.marginFor(waveformConfig.amplitude, glowWidth).toInt()

    private val solidPaintStyle: SolidPaintStyle
        get() =
            SolidPaintStyle(
                accent = glowColor,
                style = glowStyle,
                intensity = glowIntensity,
                width = glowWidth,
                edgesOnly = glowPlacement == GlowPlacement.SIDE_EDGES,
                fallbackInset = waveformMargin.takeIf { waveformFailed && glowShape == GlowShape.WAVEFORM },
            )

    private val waveformPaintState: WaveformPaintState
        get() =
            WaveformPaintState(
                accent = glowColor,
                frame =
                    waveformFrame
                        ?: waveformPlan?.request?.frame?.takeIf { it.config == waveformConfig }
                        ?: WaveformFrame(
                            config = waveformConfig,
                            direction =
                                waveformConfig.movement.fixedDirection
                                    ?: TravelDirection.CLOCKWISE,
                            brightness = waveformConfig.brightnessAt(0f),
                        ),
                style = glowStyle,
                intensity = glowIntensity,
                width = glowWidth,
                margin = waveformMargin,
                occupiedTopSpans = waveformTopSpans,
                inwardEdges = waveformInwardEdges,
                paintsSignal = !isRouteMode,
            )

    companion object {
        private const val DEFAULT_ARC_FALLBACK = 8
        private const val ISLAND_ARC_KEY = "Island.arc"
        private const val FADE_TIMER_INTERVAL_MS = 16
        private const val WAVEFORM_TIMER_INTERVAL_MS = 16
        private const val FADE_STEP = 0.08f
        private const val FRAME_BUDGET_MS = 16.0
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
        private const val TOP_SPANS_REFRESH_MS = 250L
    }

    /** Animation alpha modulated by GlowAnimator (Pulse/Breathe/Reactive). Default 1.0 = no effect. */
    var animationAlpha: Float = 1.0f
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
    }

    override fun removeNotify() {
        stopAnimation()
        super.removeNotify()
    }

    override fun contains(
        x: Int,
        y: Int,
    ): Boolean = false

    // Prevent layout managers from resizing/repositioning this overlay
    override fun getPreferredSize(): Dimension = parent?.size ?: super.getPreferredSize()

    override fun getMinimumSize(): Dimension = Dimension(0, 0)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

    override fun paintComponent(g: Graphics) {
        val effectiveAlpha = fadeAlpha * animationAlpha
        if (effectiveAlpha <= 0.0f) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.composite = AlphaComposite.SrcOver.derive(effectiveAlpha)

            val arcWidth = UIManager.getInt(ISLAND_ARC_KEY).let { if (it > 0) it else DEFAULT_ARC_FALLBACK }
            val bounds = Rectangle(0, 0, width, height)
            if (glowShape == GlowShape.WAVEFORM && !waveformFailed) {
                paintWaveform(g2, bounds, arcWidth)
            } else {
                renderer.paintSolid(g2, bounds, arcWidth, solidPaintStyle)
            }
        } finally {
            g2.dispose()
        }
    }

    internal fun configureWaveform(
        shape: GlowShape,
        config: WaveformConfig,
    ) {
        val previousShape = glowShape
        val previousConfig = waveformConfig
        val geometryChanged = previousShape != shape || previousConfig != config
        val shouldRetry =
            waveformFailed &&
                shape == GlowShape.WAVEFORM &&
                (previousShape != GlowShape.WAVEFORM || previousConfig != config)
        glowShape = shape
        waveformConfig = config
        if (geometryChanged) {
            waveformFrame = null
            waveformPlan = null
        }
        if (shouldRetry) {
            applyTimerDirective(TimerDirective.STOP)
            waveformEngine = WaveformEngine(config)
            waveformFrame = null
            waveformPlan = null
            waveformFailed = false
            waveformFailureLogged = false
        }
        if (shape == GlowShape.SOLID) {
            waveformEngine?.handle(WaveformEvent.Deactivate)?.let(::applyWaveformUpdate)
            applyTimerDirective(TimerDirective.STOP)
            repaint()
            return
        }
        if (waveformFailed) {
            repaint()
            return
        }

        val engine = waveformEngine
        if (engine == null) {
            waveformEngine = WaveformEngine(config)
        } else {
            applyWaveformUpdate(engine.handle(WaveformEvent.Configure(config)))
        }
        repaint()
    }

    internal fun activateWaveform(powerSaveEnabled: Boolean) {
        if (isRouteMode || glowShape != GlowShape.WAVEFORM || waveformFailed) return
        val engine = waveformEngine ?: WaveformEngine(waveformConfig).also { waveformEngine = it }
        applyWaveformUpdate(engine.handle(WaveformEvent.Activate(powerSaveEnabled)))
    }

    internal fun deactivateWaveform() {
        waveformEngine?.handle(WaveformEvent.Deactivate)?.let(::applyWaveformUpdate)
        waveformFrame = null
        waveformPlan = null
        applyTimerDirective(TimerDirective.STOP)
        repaint()
    }

    internal fun onWaveformKeystroke(nowMs: Long = System.currentTimeMillis()) {
        if (isRouteMode || glowShape != GlowShape.WAVEFORM || waveformFailed) return
        waveformEngine?.handle(WaveformEvent.Keystroke(nowMs))?.let(::applyWaveformUpdate)
    }

    internal fun changeWaveformPowerSave(enabled: Boolean) {
        if (isRouteMode) return
        waveformEngine?.handle(WaveformEvent.PowerSaveChanged(enabled))?.let(::applyWaveformUpdate)
    }

    internal fun configureRouteMode(enabled: Boolean) {
        if (isRouteMode == enabled) return
        isRouteMode = enabled
        if (enabled) {
            waveformEngine?.handle(WaveformEvent.Deactivate)?.let(::applyWaveformUpdate)
            applyTimerDirective(TimerDirective.STOP)
            waveformFrame = null
            waveformPlan = null
        }
        repaint()
    }

    internal fun routeTrackSnapshot(): WaveformTrack =
        waveformPainter.track(
            WaveformTrackSpec(
                bounds = Rectangle(0, 0, width, height),
                arcWidth = UIManager.getInt(ISLAND_ARC_KEY).let { if (it > 0) it else DEFAULT_ARC_FALLBACK },
                config = waveformConfig,
                solidWidth = glowWidth,
                occupiedTopSpans = waveformTopSpans,
                direction = TravelDirection.CLOCKWISE,
            ),
        )

    internal fun failRouteWaveform(exception: RuntimeException) {
        reportWaveformFailure(exception)
    }

    internal val waveformTrackLength: Float
        get() {
            val frame =
                waveformFrame
                    ?: WaveformFrame(
                        config = waveformConfig,
                        direction =
                            waveformConfig.movement.fixedDirection
                                ?: TravelDirection.CLOCKWISE,
                    )
            return waveformPainter.trackLength(
                bounds = Rectangle(0, 0, width, height),
                arcWidth = UIManager.getInt(ISLAND_ARC_KEY).let { if (it > 0) it else DEFAULT_ARC_FALLBACK },
                frame = frame,
                solidWidth = glowWidth,
                occupiedTopSpans = waveformTopSpans,
            )
        }

    private fun paintWaveform(
        graphics: Graphics2D,
        bounds: Rectangle,
        arcWidth: Int,
    ) {
        val startNanos = System.nanoTime()
        val nowMs = timeSource()
        if (nowMs >= topSpansRefreshAtMs) {
            try {
                topSpansProvider?.invoke()?.let { waveformTopSpans = it }
            } catch (exception: RuntimeException) {
                if (!topSpansFailureLogged) {
                    topSpansFailureLogged = true
                    log.warn("Waveform top-span refresh failed; keeping the last geometry", exception)
                } else {
                    log.debug("Waveform top-span refresh still unavailable", exception)
                }
            }
            topSpansRefreshAtMs = nowMs + TOP_SPANS_REFRESH_MS
        }
        try {
            val request = waveformPaintState.request(bounds, arcWidth)
            val plan =
                waveformPlan
                    ?.takeIf { it.request == request }
                    ?: waveformPainter.prepare(request).also { waveformPlan = it }
            waveformPainter.paint(graphics, plan)
        } catch (exception: RuntimeException) {
            reportWaveformFailure(exception)
            renderer.paintSolid(graphics, bounds, arcWidth, solidPaintStyle)
            return
        }

        val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND
        if (elapsedMs > FRAME_BUDGET_MS && !slowWaveformLogged) {
            slowWaveformLogged = true
            log.warn("Waveform glow frame work exceeded 16ms: %.2fms".format(elapsedMs))
        }
    }

    private fun applyWaveformUpdate(update: WaveformUpdate) {
        if (update.fallbackToSolid) {
            waveformFailed = true
            waveformPlan = null
        }
        val frame = update.frame
        if (frame != null) {
            if (update.needsRepaint && !waveformFailed) {
                showWaveformFrame(frame)
            } else {
                waveformFrame = frame
            }
        }
        applyTimerDirective(update.timerDirective)
        if (update.needsRepaint && (frame == null || waveformFailed)) repaint()
    }

    internal fun showWaveformFrame(frame: WaveformFrame) {
        val previousPlan = waveformPlan
        waveformFrame = frame
        if (glowShape != GlowShape.WAVEFORM || waveformFailed) {
            waveformPlan = null
            repaint()
            return
        }
        try {
            val bounds = Rectangle(0, 0, width, height)
            val arcWidth = UIManager.getInt(ISLAND_ARC_KEY).let { if (it > 0) it else DEFAULT_ARC_FALLBACK }
            val startNanos = System.nanoTime()
            val nextPlan = waveformPainter.prepare(waveformPaintState.request(bounds, arcWidth))
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND
            if (elapsedMs > FRAME_BUDGET_MS && !slowWaveformLogged) {
                slowWaveformLogged = true
                log.warn("Waveform glow frame work exceeded 16ms: %.2fms".format(elapsedMs))
            }
            waveformPlan = nextPlan
            repaintPlanChange(this, previousPlan, nextPlan)
        } catch (exception: RuntimeException) {
            reportWaveformFailure(exception)
        }
    }

    private fun applyTimerDirective(directive: TimerDirective) {
        when (directive) {
            TimerDirective.KEEP -> {
                // Keep the current timer state.
            }

            TimerDirective.START -> {
                if (waveformTimer != null || waveformFailed) return
                waveformTimer =
                    Timer(WAVEFORM_TIMER_INTERVAL_MS) {
                        val engine = waveformEngine ?: return@Timer
                        try {
                            val update =
                                engine.handle(
                                    WaveformEvent.Tick(
                                        nowMs = timeSource(),
                                        trackLength = waveformTrackLength,
                                    ),
                                )
                            applyWaveformUpdate(if (isShowing) update else update.copy(needsRepaint = false))
                        } catch (exception: RuntimeException) {
                            reportWaveformFailure(exception)
                        }
                    }.also { timer ->
                        timer.isCoalesce = true
                        timer.start()
                    }
            }

            TimerDirective.STOP -> {
                waveformTimer?.stop()
                waveformTimer = null
            }
        }
    }

    private fun reportWaveformFailure(exception: RuntimeException) {
        if (!waveformFailureLogged) {
            waveformFailureLogged = true
            log.warn("Waveform glow render failed", exception)
        }
        waveformFailed = true
        waveformPlan = null
        waveformEngine?.handle(WaveformEvent.RenderFailed)?.let(::applyWaveformUpdate)
        applyTimerDirective(TimerDirective.STOP)
        repaint()
    }

    fun startFadeIn() {
        fadeTimer?.stop()
        fadeTimer =
            Timer(FADE_TIMER_INTERVAL_MS) {
                fadeAlpha = (fadeAlpha + FADE_STEP).coerceAtMost(1.0f)
                repaint()
                if (fadeAlpha >= 1.0f) fadeTimer?.stop()
            }.also { it.start() }
    }

    fun startFadeOut() {
        fadeTimer?.stop()
        fadeTimer =
            Timer(FADE_TIMER_INTERVAL_MS) {
                fadeAlpha = (fadeAlpha - FADE_STEP).coerceAtLeast(0.0f)
                repaint()
                if (fadeAlpha <= 0.0f) fadeTimer?.stop()
            }.also { it.start() }
    }

    fun stopAnimation() {
        fadeTimer?.stop()
        fadeTimer = null
        applyTimerDirective(TimerDirective.STOP)
        waveformEngine = null
        waveformFrame = null
        waveformPlan = null
    }

    fun invalidateRendererCache() {
        renderer.invalidateCache()
    }
}

private data class SolidPaintStyle(
    val accent: Color,
    val style: GlowStyle,
    val intensity: Int,
    val width: Int,
    val edgesOnly: Boolean,
    val fallbackInset: Int?,
)

private data class WaveformPaintState(
    val accent: Color,
    val frame: WaveformFrame,
    val style: GlowStyle,
    val intensity: Int,
    val width: Int,
    val margin: Int,
    val occupiedTopSpans: List<IntRange>,
    val inwardEdges: Set<WaveformEdge>,
    val paintsSignal: Boolean,
) {
    fun request(
        bounds: Rectangle,
        arcWidth: Int,
    ): WaveformPaintRequest =
        WaveformPaintRequest(
            bounds = bounds,
            arcWidth = arcWidth,
            accent = accent,
            frame = frame,
            solidFrame =
                SolidFrameSpec(
                    bounds =
                        Rectangle(
                            margin,
                            margin,
                            (bounds.width - margin * 2).coerceAtLeast(0),
                            (bounds.height - margin * 2).coerceAtLeast(0),
                        ),
                    style = style,
                    intensity = intensity,
                    width = width,
                ),
            occupiedTopSpans = occupiedTopSpans,
            inwardEdges = inwardEdges,
            paintsSignal = paintsSignal,
        )
}

private fun GlowRenderer.paintSolid(
    graphics: Graphics2D,
    bounds: Rectangle,
    arcWidth: Int,
    style: SolidPaintStyle,
) {
    val inset = style.fallbackInset
    val paintBounds =
        if (inset == null) {
            bounds
        } else {
            Rectangle(
                bounds.x + inset,
                bounds.y + inset,
                (bounds.width - inset * 2).coerceAtLeast(0),
                (bounds.height - inset * 2).coerceAtLeast(0),
            )
        }
    ensureCache(style.accent, style.style, style.intensity, style.width)
    paintGlow(
        graphics,
        paintBounds,
        style.width,
        arcWidth,
        edgesOnly = style.edgesOnly,
    )
}

private fun repaintPlanChange(
    pane: JPanel,
    previous: WaveformRenderPlan?,
    next: WaveformRenderPlan,
) {
    if (previous == null) {
        pane.repaint()
        return
    }
    val previousBounds = previous.signalBounds
    val nextBounds = next.signalBounds
    val dirtyBounds =
        when {
            previousBounds != null && nextBounds != null -> previousBounds.union(nextBounds)
            previousBounds != null -> previousBounds
            nextBounds != null -> nextBounds
            else -> null
        }
    if (dirtyBounds == null) {
        pane.repaint()
    } else {
        pane.repaint(dirtyBounds.x, dirtyBounds.y, dirtyBounds.width, dirtyBounds.height)
    }
}
