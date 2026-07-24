package dev.ayuislands.glow.waveform

import dev.ayuislands.glow.GlowStyle
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.awt.AWTError
import java.awt.Color
import java.awt.Container
import java.awt.Rectangle
import java.awt.Window
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JWindow
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossWindowBridgeTest {
    @Test
    fun `headless disables native bridge`() {
        assertFalse(WindowBridgeCapability.isSupported(headless = true, deviceSupport = listOf(true)))
    }

    @Test
    fun `all graphics devices must support translucency`() {
        assertFalse(WindowBridgeCapability.isSupported(headless = false, deviceSupport = emptyList()))
        assertFalse(
            WindowBridgeCapability.isSupported(
                headless = false,
                deviceSupport = listOf(true, false),
            ),
        )
        assertTrue(
            WindowBridgeCapability.isSupported(
                headless = false,
                deviceSupport = listOf(true, true),
            ),
        )
    }

    @Test
    fun `runtime capability rejects unavailable devices`() {
        assertFalse(WindowBridgeCapability.isSupported(owner(), owner()))
    }

    @Test
    fun `mac bridge enables native click through before display`() {
        org.junit.jupiter.api.Assumptions
            .assumeTrue(com.intellij.openapi.util.SystemInfo.isMac)
        val nativeWindow = mockk<JWindow>(relaxed = true)
        val nativeId =
            com.intellij.ui.mac.foundation
                .ID(42)
        io.mockk.mockkStatic(com.intellij.ui.mac.foundation.MacUtil::class)
        io.mockk.mockkStatic(com.intellij.ui.mac.foundation.Foundation::class)
        try {
            every {
                com.intellij.ui.mac.foundation.MacUtil
                    .getWindowFromJavaWindow(nativeWindow)
            } returns nativeId
            every {
                com.intellij.ui.mac.foundation.Foundation
                    .isNil(nativeId)
            } returns false
            every {
                com.intellij.ui.mac.foundation.Foundation
                    .executeOnMainThread(true, true, any())
            } answers {
                (invocation.args[2] as Runnable).run()
            }
            every {
                com.intellij.ui.mac.foundation.Foundation.invoke(
                    nativeId,
                    "setIgnoresMouseEvents:",
                    true,
                )
            } returns com.intellij.ui.mac.foundation.ID.NIL
            every {
                com.intellij.ui.mac.foundation.Foundation
                    .invoke(nativeId, "ignoresMouseEvents")
            } returns
                com.intellij.ui.mac.foundation
                    .ID(1)

            val bridge = CrossWindowBridge(onFailure = { throw AssertionError(it) }, createWindow = { nativeWindow })
            val connector = connector()
            val slice = bridgeSlice(connector)
            val owner = owner()

            bridge.show(owner, connector, bridgeFrame(slice), slice, bridgeStyle())
            bridge.show(owner, connector, bridgeFrame(slice), slice, bridgeStyle())

            io.mockk.verifyOrder {
                nativeWindow.addNotify()
                com.intellij.ui.mac.foundation.MacUtil
                    .getWindowFromJavaWindow(nativeWindow)
                com.intellij.ui.mac.foundation.Foundation
                    .executeOnMainThread(true, true, any())
                com.intellij.ui.mac.foundation.Foundation.invoke(
                    nativeId,
                    "setIgnoresMouseEvents:",
                    true,
                )
                nativeWindow.isVisible = true
            }
            verify(exactly = 2) {
                com.intellij.ui.mac.foundation.MacUtil
                    .getWindowFromJavaWindow(nativeWindow)
                com.intellij.ui.mac.foundation.Foundation.invoke(
                    nativeId,
                    "setIgnoresMouseEvents:",
                    true,
                )
            }
        } finally {
            io.mockk.unmockkStatic(com.intellij.ui.mac.foundation.Foundation::class)
            io.mockk.unmockkStatic(com.intellij.ui.mac.foundation.MacUtil::class)
        }
    }

    @Test
    fun `native policy applies after display`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            com.intellij.openapi.util.SystemInfo.isMac && !java.awt.GraphicsEnvironment.isHeadless(),
        )
        var owner: javax.swing.JFrame? = null
        var overlay: JWindow? = null
        try {
            javax.swing.SwingUtilities.invokeAndWait {
                val testOwner =
                    javax.swing.JFrame("Bridge native policy test").apply {
                        isUndecorated = true
                        focusableWindowState = false
                        isAutoRequestFocus = false
                        setSize(32, 32)
                        setLocation(-10_000, -10_000)
                        isVisible = true
                    }
                owner = testOwner
                val testOverlay =
                    JWindow(testOwner).apply {
                        background = Color(0, 0, 0, 0)
                        focusableWindowState = false
                        isAutoRequestFocus = false
                        bounds = testOwner.bounds
                    }
                overlay = testOverlay
                assertTrue(WindowBridgeCapability.makeClickThrough(testOverlay))
                testOverlay.isVisible = true
                assertTrue(WindowBridgeCapability.makeClickThrough(testOverlay))
            }
        } finally {
            javax.swing.SwingUtilities.invokeAndWait {
                overlay?.dispose()
                owner?.dispose()
            }
        }
    }

    @Test
    fun `failure reports connector once`() {
        val failures = mutableListOf<RouteConnectorId>()
        val connector = connector()
        val bridge =
            bridge(
                onFailure = { connectorId -> failures += connectorId },
                createWindow = { throw AWTError("forced bridge failure") },
            )
        val slice = bridgeSlice(connector)
        val frame = bridgeFrame(slice)
        val owner = owner()

        bridge.show(owner, connector, frame, slice, bridgeStyle())
        bridge.show(owner, connector, frame, slice, bridgeStyle())

        assertEquals(listOf(connector.id), failures)
        assertFalse(bridge.isVisible)
    }

    @Test
    fun `bridge translates screen samples`() {
        val nativeWindow = mockk<JWindow>(relaxed = true)
        val capturedBounds = slot<Rectangle>()
        val capturedContent = slot<Container>()
        justRun { nativeWindow.bounds = capture(capturedBounds) }
        justRun { nativeWindow.contentPane = capture(capturedContent) }
        val bridge = bridge(onFailure = { throw AssertionError(it) }, createWindow = { nativeWindow })
        val connector = connector()
        val slice = bridgeSlice(connector)
        val frame = bridgeFrame(slice)
        val style = bridgeStyle()

        bridge.show(owner(), connector, frame, slice, style)

        val margin = ceil(WaveformPainter.marginFor(style.config.amplitude, style.width)).toInt()
        val expectedBounds = Rectangle(298, 150, 45, 1).apply { grow(margin, margin) }
        val layer = capturedContent.captured as JComponent
        val image = render(layer)
        assertEquals(expectedBounds, capturedBounds.captured)
        assertEquals(Rectangle(0, 0, expectedBounds.width, expectedBounds.height), layer.bounds)
        assertTrue(paintedPixelCount(image) > 0)
        assertTrue(bridge.isVisible)
        verify {
            nativeWindow.background = Color(0, 0, 0, 0)
            nativeWindow.focusableWindowState = false
            nativeWindow.isAutoRequestFocus = false
            nativeWindow.type = Window.Type.POPUP
            nativeWindow.isVisible = true
        }
    }

    @Test
    fun `invalid bridge frame hides window`() {
        val nativeWindow = mockk<JWindow>(relaxed = true)
        val bridge = bridge(onFailure = { throw AssertionError(it) }, createWindow = { nativeWindow })
        val connector = connector()
        val slice = bridgeSlice(connector)
        val frame = bridgeFrame(slice)
        val owner = owner()
        bridge.show(owner, connector, frame, slice, bridgeStyle())

        bridge.show(
            owner = owner,
            connector = connector,
            frame = frame,
            slice = slice.copy(target = RoutePaintTarget.Root(RouteRootId(1))),
            style = bridgeStyle(),
        )

        assertFalse(bridge.isVisible)
        verify { nativeWindow.isVisible = false }
    }

    @Test
    fun `unsupported connector hides window`() {
        val nativeWindow = mockk<JWindow>(relaxed = true)
        val bridge = bridge(onFailure = { throw AssertionError(it) }, createWindow = { nativeWindow })
        val connector = connector()
        val slice = bridgeSlice(connector)
        val frame = bridgeFrame(slice)
        val owner = owner()
        bridge.show(owner, connector, frame, slice, bridgeStyle())

        bridge.show(owner, connector.copy(requiresWindowBridge = false), frame, slice, bridgeStyle())

        assertFalse(bridge.isVisible)
        verify { nativeWindow.isVisible = false }
    }

    @Test
    fun `empty bridge slice hides window`() {
        val nativeWindow = mockk<JWindow>(relaxed = true)
        val bridge = bridge(onFailure = { throw AssertionError(it) }, createWindow = { nativeWindow })
        val connector = connector()
        val slice = bridgeSlice(connector)
        val frame = bridgeFrame(slice)
        val owner = owner()
        bridge.show(owner, connector, frame, slice, bridgeStyle())

        bridge.show(owner, connector, frame, slice.copy(samples = emptyList()), bridgeStyle())

        assertFalse(bridge.isVisible)
        verify { nativeWindow.isVisible = false }
    }

    @Test
    fun `owner loss hides window`() {
        val nativeWindow = mockk<JWindow>(relaxed = true)
        val bridge = bridge(onFailure = { throw AssertionError(it) }, createWindow = { nativeWindow })
        val connector = connector()
        val slice = bridgeSlice(connector)
        val frame = bridgeFrame(slice)
        val owner = owner()
        bridge.show(owner, connector, frame, slice, bridgeStyle())
        every { owner.isDisplayable } returns false

        bridge.show(owner, connector, frame, slice, bridgeStyle())

        assertFalse(bridge.isVisible)
        verify { nativeWindow.isVisible = false }
    }

    @Test
    fun `dispose releases native window`() {
        val nativeWindow = mockk<JWindow>(relaxed = true)
        val bridge = bridge(onFailure = { throw AssertionError(it) }, createWindow = { nativeWindow })
        val connector = connector()
        val slice = bridgeSlice(connector)
        bridge.show(owner(), connector, bridgeFrame(slice), slice, bridgeStyle())

        bridge.dispose()

        assertFalse(bridge.isVisible)
        verify { nativeWindow.dispose() }
    }

    @Test
    fun `owner replacement disposes old window`() {
        val firstWindow = mockk<JWindow>(relaxed = true)
        val secondWindow = mockk<JWindow>(relaxed = true)
        val windows = ArrayDeque(listOf(firstWindow, secondWindow))
        val bridge =
            bridge(
                onFailure = { throw AssertionError(it) },
                createWindow = { windows.removeFirst() },
            )
        val connector = connector()
        val slice = bridgeSlice(connector)
        val frame = bridgeFrame(slice)

        bridge.show(owner(), connector, frame, slice, bridgeStyle())
        bridge.show(owner(), connector, frame, slice, bridgeStyle())

        assertTrue(bridge.isVisible)
        verify { firstWindow.dispose() }
        verify { secondWindow.isVisible = true }
    }

    @Test
    fun `runtime failure disposes window`() {
        val failures = mutableListOf<RouteConnectorId>()
        val nativeWindow = mockk<JWindow>(relaxed = true)
        every { nativeWindow.isVisible = true } throws IllegalStateException("forced runtime failure")
        val bridge =
            bridge(
                onFailure = { connectorId -> failures += connectorId },
                createWindow = { nativeWindow },
            )
        val connector = connector()
        val slice = bridgeSlice(connector)

        bridge.show(owner(), connector, bridgeFrame(slice), slice, bridgeStyle())

        assertEquals(listOf(connector.id), failures)
        assertFalse(bridge.isVisible)
        verify { nativeWindow.dispose() }
    }

    @Test
    fun `repeated cleanup error is contained`() {
        val failures = mutableListOf<RouteConnectorId>()
        val nativeWindow = mockk<JWindow>(relaxed = true)
        val repeatedError = LinkageError("forced repeated linkage failure")
        every { nativeWindow.isVisible = true } throws repeatedError
        every { nativeWindow.isVisible = false } throws repeatedError
        val bridge =
            bridge(
                onFailure = { connectorId -> failures += connectorId },
                createWindow = { nativeWindow },
            )
        val connector = connector()
        val slice = bridgeSlice(connector)

        bridge.show(owner(), connector, bridgeFrame(slice), slice, bridgeStyle())

        assertEquals(listOf(connector.id), failures)
        assertFalse(bridge.isVisible)
        assertTrue(repeatedError.suppressed.isEmpty())
    }

    @Test
    fun `failure isolates connector and contains cleanup errors`() {
        val failures = mutableListOf<RouteConnectorId>()
        val firstWindow = mockk<JWindow>(relaxed = true)
        val secondWindow = mockk<JWindow>(relaxed = true)
        val windows = ArrayDeque(listOf(firstWindow, secondWindow))
        val nativePolicy = ArrayDeque(listOf(true, false, true, true))
        every { firstWindow.isVisible = false } throws LinkageError("forced hide failure")
        every { firstWindow.dispose() } throws LinkageError("forced dispose failure")
        val bridge =
            bridge(
                onFailure = { connectorId -> failures += connectorId },
                createWindow = { windows.removeFirst() },
                makeClickThrough = { nativePolicy.removeFirst() },
            )
        val failedConnector = connector()
        val nextConnector =
            connector().copy(
                id = RouteConnectorId("Git", "Commit", RouteSide.BOTTOM),
                sourceId = "Git",
                targetId = "Commit",
            )
        val failedSlice = bridgeSlice(failedConnector)
        val nextSlice = bridgeSlice(nextConnector)

        bridge.show(
            owner(),
            failedConnector,
            bridgeFrame(failedSlice),
            failedSlice,
            bridgeStyle(),
        )
        bridge.show(
            owner(),
            failedConnector,
            bridgeFrame(failedSlice),
            failedSlice,
            bridgeStyle(),
        )
        bridge.show(
            owner(),
            nextConnector,
            bridgeFrame(nextSlice),
            nextSlice,
            bridgeStyle(),
        )

        assertEquals(listOf(failedConnector.id), failures)
        assertTrue(bridge.isVisible)
        verify(exactly = 1) { firstWindow.dispose() }
        verify { secondWindow.isVisible = true }
    }

    private fun bridge(
        onFailure: (RouteConnectorId) -> Unit,
        createWindow: (Window) -> JWindow,
        makeClickThrough: (JWindow) -> Boolean = { true },
    ): CrossWindowBridge =
        CrossWindowBridge(
            onFailure = onFailure,
            createWindow = createWindow,
            makeClickThrough = makeClickThrough,
        )

    private fun owner(): Window =
        mockk(relaxed = true) {
            every { isDisplayable } returns true
        }

    private fun connector(): RouteConnector =
        RouteConnector(
            id = RouteConnectorId("Editor", "Git", RouteSide.RIGHT),
            endpoint = RouteEndpoint.START,
            sourceId = "Editor",
            targetId = "Git",
            sourceSide = RouteSide.RIGHT,
            targetSide = RouteSide.LEFT,
            sourceDistance = 298f,
            targetDistance = 342f,
            sourcePoint = RoutePoint(298f, 150f),
            targetPoint = RoutePoint(342f, 150f),
            length = 44f,
            requiresWindowBridge = true,
        )

    private fun bridgeSlice(connector: RouteConnector): RouteSlice =
        RouteSlice(
            target = RoutePaintTarget.WindowBridge(connector.id),
            surfaceId = null,
            samples =
                listOf(
                    WaveformSample(298f, 150f, 0f, -1f, 0f, 1f),
                    WaveformSample(342f, 150f, 0f, -1f, 44f, 1f),
                ),
            distanceOffset = 298f,
            inwardEdges = emptySet(),
        )

    private fun bridgeFrame(slice: RouteSlice): RouteFrame {
        val config = bridgeStyle().config
        return RouteFrame(
            signal =
                WaveformFrame(
                    config = config,
                    direction = TravelDirection.CLOCKWISE,
                    trace =
                        FrameTrace(
                            anchorOffset = 0f,
                            history = List(config.traceComplexCount) { BeatMorphology.standard() },
                            phase = 0.4f,
                        ),
                ),
            centerDistance = 320f,
            signalSpan = 360f,
            currentSurfaceId = "Git",
            slices = listOf(slice),
        )
    }

    private fun bridgeStyle(): RouteLayerStyle =
        RouteLayerStyle(
            accent = Color(255, 204, 102),
            glowStyle = GlowStyle.SHARP_NEON,
            intensity = 100,
            width = 4,
            arcWidth = 16,
            config =
                WaveformConfig(
                    movement = WaveformMovement.CHAOTIC,
                    amplitude = 8,
                    intensity = 100,
                    traceLength = 360,
                ),
        )

    private fun render(component: JComponent): BufferedImage {
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        component.paint(graphics)
        graphics.dispose()
        return image
    }

    private fun paintedPixelCount(image: BufferedImage): Int =
        (0 until image.height).sumOf { y ->
            (0 until image.width).count { x -> image.getRGB(x, y).ushr(24) > 0 }
        }
}
