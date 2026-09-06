package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformEdge
import io.mockk.EqMatcher
import io.mockk.Matcher
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlowGlassPaneLifecycleTest {
    @Test
    fun `pane follows its host layout while remaining transparent and click-through`() =
        onEdt {
            val firstChild = JPanel()
            val pane = newPane()
            val lastChild = JPanel()
            val host =
                JPanel(null).apply {
                    size = Dimension(WIDTH, HEIGHT)
                    add(firstChild)
                    add(pane)
                    add(lastChild)
                }

            assertFalse(pane.isOpaque)
            assertFalse(pane.contains(WIDTH / 2, HEIGHT / 2))
            assertEquals(host.size, pane.preferredSize)
            assertEquals(Dimension(0, 0), pane.minimumSize)
            assertEquals(Dimension(Int.MAX_VALUE, Int.MAX_VALUE), pane.maximumSize)
            assertEquals(listOf(firstChild, pane, lastChild), host.components.toList())

            host.size = Dimension(WIDTH / 2, HEIGHT / 2)
            assertEquals(host.size, pane.preferredSize)
            host.remove(pane)
            assertEquals(Dimension(0, 0), pane.preferredSize)
            assertEquals(listOf(firstChild, lastChild), host.components.toList())
        }

    @Test
    fun `fade callbacks change real output monotonically and clamp at both endpoints`() =
        withTimerScheduling {
            val pane = trackedPane()
            pane.startFadeIn()
            val fadeInCallback = startedCallbacks.single()
            val transparentAlpha = alphaSum(paint(pane))

            fire(fadeInCallback)
            val firstFadeInAlpha = alphaSum(paint(pane))
            fire(fadeInCallback)
            val secondFadeInAlpha = alphaSum(paint(pane))
            repeat(ENDPOINT_TICKS) { fire(fadeInCallback) }
            val opaqueAlpha = alphaSum(paint(pane))
            fire(fadeInCallback)

            assertEquals(0L, transparentAlpha)
            assertTrue(firstFadeInAlpha > transparentAlpha)
            assertTrue(secondFadeInAlpha > firstFadeInAlpha)
            assertEquals(opaqueAlpha, alphaSum(paint(pane)))
            assertTrue(fadeInCallback in stoppedCallbacks)

            pane.startFadeOut()
            val fadeOutCallback = startedCallbacks.last()
            fire(fadeOutCallback)
            val firstFadeOutAlpha = alphaSum(paint(pane))
            repeat(ENDPOINT_TICKS) { fire(fadeOutCallback) }
            val finalAlpha = alphaSum(paint(pane))
            fire(fadeOutCallback)

            assertTrue(firstFadeOutAlpha < opaqueAlpha)
            assertEquals(0L, finalAlpha)
            assertEquals(finalAlpha, alphaSum(paint(pane)))
            assertTrue(fadeOutCallback in stoppedCallbacks)
        }

    @Test
    fun `reversing a fade stops the previous timer and preserves pane preferences`() =
        withTimerScheduling {
            val pane = trackedPane()
            val expectedPreferences = pane.preferences()
            pane.startFadeIn()
            val firstCallback = startedCallbacks.single()
            repeat(4) { fire(firstCallback) }
            val partialAlpha = alphaSum(paint(pane))

            pane.startFadeOut()
            val reverseCallback = startedCallbacks.last()
            fire(reverseCallback)
            val reversedAlpha = alphaSum(paint(pane))
            pane.startFadeIn()
            val resumedCallback = startedCallbacks.last()
            fire(resumedCallback)

            assertTrue(firstCallback in stoppedCallbacks)
            assertTrue(reverseCallback in stoppedCallbacks)
            assertTrue(reversedAlpha < partialAlpha)
            assertTrue(alphaSum(paint(pane)) > reversedAlpha)
            assertEquals(expectedPreferences, pane.preferences())
            pane.stopAnimation()
        }

    @Test
    fun `animation alpha scales output without changing caller graphics`() =
        withTimerScheduling {
            val pane = trackedPane()
            pane.startFadeIn()
            repeat(ENDPOINT_TICKS) { fire(startedCallbacks.single()) }
            val fullAlpha = alphaSum(paint(pane))
            pane.animationAlpha = 0.5f
            val halfAlpha = alphaSum(paint(pane))
            pane.animationAlpha = 0.0f

            assertTrue(halfAlpha in 1 until fullAlpha)
            assertEquals(0L, alphaSum(paint(pane)))

            pane.animationAlpha = 1.0f
            val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            val expectedClip = Rectangle(5, 7, 80, 60)
            val expectedTransform = AffineTransform.getTranslateInstance(3.0, 4.0)
            val expectedComposite = AlphaComposite.SrcOver.derive(0.7f)
            try {
                graphics.transform = expectedTransform
                graphics.clip = expectedClip
                graphics.color = Color.MAGENTA
                graphics.font = Font(Font.MONOSPACED, Font.BOLD, 15)
                graphics.composite = expectedComposite

                pane.paint(graphics)

                val activeColor = graphics.color
                val activeFont = graphics.font
                assertEquals(expectedClip, graphics.clipBounds)
                assertEquals(expectedTransform, graphics.transform)
                assertEquals(expectedComposite, graphics.composite)

                val baselineGraphics = image.createGraphics()
                try {
                    baselineGraphics.transform = expectedTransform
                    baselineGraphics.clip = expectedClip
                    baselineGraphics.color = Color.MAGENTA
                    baselineGraphics.font = Font(Font.MONOSPACED, Font.BOLD, 15)
                    baselineGraphics.composite = expectedComposite
                    newPane().paint(baselineGraphics)

                    assertEquals(baselineGraphics.color, activeColor)
                    assertEquals(baselineGraphics.font, activeFont)
                } finally {
                    baselineGraphics.dispose()
                }
            } finally {
                graphics.dispose()
            }
        }

    @Test
    fun `stop and remove notify cancel timers and allow exact configuration restart`() =
        withTimerScheduling {
            val pane = trackedPane()
            val config = WaveformConfig(amplitude = 11, intensity = 73, loopSeconds = 1.7f)
            val topSpans = listOf(80..120, 4..20, 210..260)
            val inwardEdges = linkedSetOf(WaveformEdge.RIGHT, WaveformEdge.TOP)
            pane.timeSource = { 0L }
            pane.waveformTopSpans = topSpans
            pane.waveformInwardEdges = inwardEdges
            pane.configureWaveform(GlowShape.WAVEFORM, config)
            pane.startFadeIn()
            val fadeCallback = startedCallbacks.single()
            repeat(4) { fire(fadeCallback) }
            val pixelsBeforeStop = pixels(paint(pane))
            pane.activateWaveform(powerSaveEnabled = false)
            val waveformCallback = startedCallbacks.last()
            val stopsBeforeStopAnimation = stoppedCallbacks.toList()

            pane.stopAnimation()
            pane.stopAnimation()

            assertFalse(fadeCallback in stopsBeforeStopAnimation)
            assertTrue(waveformCallback in stoppedCallbacks)
            assertTrue(fadeCallback in stoppedCallbacks)
            assertEquals(topSpans, pane.waveformTopSpans)
            assertEquals(inwardEdges.toList(), pane.waveformInwardEdges.toList())
            assertEquals(PanePreferences(), pane.preferences())

            pane.configureWaveform(GlowShape.WAVEFORM, config)
            val pixelsAfterRestart = pixels(paint(pane))
            assertContentEquals(pixelsBeforeStop, pixelsAfterRestart)
            pane.activateWaveform(powerSaveEnabled = false)
            pane.startFadeIn()
            assertEquals(4, startedCallbacks.size)
            assertEquals(startedCallbacks.size, startedCallbacks.toSet().size)
            assertEquals(topSpans, pane.waveformTopSpans)
            assertEquals(inwardEdges.toList(), pane.waveformInwardEdges.toList())

            val restartedWaveformCallback = startedCallbacks[2]
            val restartedFadeCallback = startedCallbacks[3]
            pane.removeNotify()
            assertTrue(restartedWaveformCallback in stoppedCallbacks)
            assertTrue(restartedFadeCallback in stoppedCallbacks)
            assertEquals(PanePreferences(), pane.preferences())
            assertEquals(topSpans, pane.waveformTopSpans)
            assertEquals(inwardEdges.toList(), pane.waveformInwardEdges.toList())
        }

    @Test
    fun `power save and route mode gate waveform scheduling without losing configuration`() =
        withTimerScheduling {
            val pane = trackedPane()
            val config = WaveformConfig(amplitude = 9, intensity = 64, loopSeconds = 2.1f)
            pane.configureWaveform(GlowShape.WAVEFORM, config)
            pane.startFadeIn()
            val fadeCallback = startedCallbacks.single()
            repeat(ENDPOINT_TICKS) { fire(fadeCallback) }
            val startsBeforeActivation = startedCallbacks.size
            pane.activateWaveform(powerSaveEnabled = true)
            val powerSavePixels = pixels(paint(pane))
            assertEquals(startsBeforeActivation, startedCallbacks.size)

            pane.changeWaveformPowerSave(enabled = false)
            val activeCallback = startedCallbacks.last()
            pane.changeWaveformPowerSave(enabled = true)
            assertTrue(activeCallback in stoppedCallbacks)
            assertContentEquals(powerSavePixels, pixels(paint(pane)))

            pane.changeWaveformPowerSave(enabled = false)
            val resumedCallback = startedCallbacks.last()
            pane.configureRouteMode(enabled = true)
            assertTrue(resumedCallback in stoppedCallbacks)
            val guardedStartCount = startedCallbacks.size
            pane.activateWaveform(powerSaveEnabled = false)
            pane.changeWaveformPowerSave(enabled = false)
            assertEquals(guardedStartCount, startedCallbacks.size)

            pane.configureRouteMode(enabled = false)
            pane.configureWaveform(GlowShape.SOLID, config)
            pane.activateWaveform(powerSaveEnabled = false)
            assertEquals(guardedStartCount, startedCallbacks.size)
            assertEquals(PanePreferences(), pane.preferences())
        }

    private fun newPane(): GlowGlassPane =
        GlowGlassPane(
            glowColor = ACCENT,
            glowStyle = GlowStyle.SOFT,
            glowIntensity = 80,
            glowWidth = 12,
            isEditorOverlay = true,
            glowPlacement = GlowPlacement.ISLAND,
        ).also { pane -> pane.setSize(WIDTH, HEIGHT) }

    private fun GlowGlassPane.preferences(): PanePreferences =
        PanePreferences(
            color = glowColor,
            style = glowStyle,
            intensity = glowIntensity,
            width = glowWidth,
            editorOverlay = isEditorOverlay,
            placement = glowPlacement,
        )

    private fun paint(pane: GlowGlassPane): BufferedImage {
        val image = BufferedImage(pane.width, pane.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            pane.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun alphaSum(image: BufferedImage): Long =
        pixels(image)
            .sumOf { pixel -> (pixel ushr ALPHA_SHIFT).toLong() }

    private fun pixels(image: BufferedImage): IntArray =
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private fun fire(callback: ActionListener) {
        callback.actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, "tick"))
    }

    private fun withTimerScheduling(block: TimerFixture.() -> Unit) {
        mockkConstructor(Timer::class)
        try {
            val fixture = TimerFixture()
            every { anyConstructed<Timer>().start() } throws
                AssertionError("Unexpected Timer exceeded the bounded scheduling fixture")
            fixture.callbackMatchers.forEach { callbackMatcher ->
                every {
                    constructedWith<Timer>(EqMatcher(16), callbackMatcher).start()
                } answers {
                    fixture.startedCallbacks += callbackMatcher.callback()
                }
                every {
                    constructedWith<Timer>(EqMatcher(16), callbackMatcher).stop()
                } answers {
                    fixture.stoppedCallbacks += callbackMatcher.callback()
                }
            }
            onEdt {
                try {
                    fixture.block()
                } finally {
                    fixture.createdPanes.forEach(GlowGlassPane::stopAnimation)
                }
            }
        } finally {
            unmockkConstructor(Timer::class)
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait(block)
        }
    }

    private inner class TimerFixture {
        val startedCallbacks = mutableListOf<ActionListener>()
        val stoppedCallbacks = mutableListOf<ActionListener>()
        val callbackMatchers = List(TIMER_SLOT_COUNT) { CallbackMatcher() }
        val createdPanes = mutableListOf<GlowGlassPane>()

        fun trackedPane(): GlowGlassPane = newPane().also(createdPanes::add)
    }

    private class CallbackMatcher : Matcher<ActionListener> {
        private var capturedCallback: ActionListener? = null

        override fun match(arg: ActionListener?): Boolean {
            if (arg == null) return false
            val callback = capturedCallback
            if (callback == null) {
                capturedCallback = arg
                return true
            }
            return callback === arg
        }

        fun callback(): ActionListener = checkNotNull(capturedCallback)
    }

    private data class PanePreferences(
        val color: Color = ACCENT,
        val style: GlowStyle = GlowStyle.SOFT,
        val intensity: Int = 80,
        val width: Int = 12,
        val editorOverlay: Boolean = true,
        val placement: GlowPlacement = GlowPlacement.ISLAND,
    )

    private companion object {
        val ACCENT = Color(0xFF8F40)
        const val WIDTH = 180
        const val HEIGHT = 100
        const val ALPHA_SHIFT = 24
        const val ENDPOINT_TICKS = 16
        const val TIMER_SLOT_COUNT = 4
    }
}
