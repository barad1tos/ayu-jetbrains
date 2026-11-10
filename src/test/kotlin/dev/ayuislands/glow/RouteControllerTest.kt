package dev.ayuislands.glow

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.RunInEdt
import dev.ayuislands.glow.waveform.CrossWindowBridge
import dev.ayuislands.glow.waveform.RouteConnector
import dev.ayuislands.glow.waveform.RouteConnectorId
import dev.ayuislands.glow.waveform.RouteCoordinator
import dev.ayuislands.glow.waveform.RouteEndpoint
import dev.ayuislands.glow.waveform.RouteEvent
import dev.ayuislands.glow.waveform.RouteFrame
import dev.ayuislands.glow.waveform.RouteGraph
import dev.ayuislands.glow.waveform.RouteLayerStyle
import dev.ayuislands.glow.waveform.RoutePaintTarget
import dev.ayuislands.glow.waveform.RoutePoint
import dev.ayuislands.glow.waveform.RouteRootId
import dev.ayuislands.glow.waveform.RouteSide
import dev.ayuislands.glow.waveform.RouteSlice
import dev.ayuislands.glow.waveform.RouteUpdate
import dev.ayuislands.glow.waveform.TimerDirective
import dev.ayuislands.glow.waveform.TravelDirection
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformFrame
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.glow.waveform.WaveformRouteLayer
import dev.ayuislands.glow.waveform.WaveformSample
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.loadGlowSettings
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Point
import java.awt.Window
import java.awt.event.ActionEvent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.Timer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunInEdt
class RouteControllerTest {
    @Test
    fun `accepted route discovers late panes without recording them in its completed attempt`() {
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)
        val first = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        val second = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        first.configureWaveform(GlowShape.WAVEFORM, config)
        second.configureWaveform(GlowShape.WAVEFORM, config)
        val initial = RouteOverlay("Editor", first, JPanel(), JLayeredPane())
        val late = RouteOverlay("Project", second, JPanel(), JLayeredPane())
        val available = mutableListOf(initial)
        val recorded = mutableListOf<Timer>()
        val scheduled = mutableListOf<Timer>()
        val request = RoutePlayback(config, listOf(initial))
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { available.toList() },
                focusedSurfaceId = { "Editor" },
                settings = { loadGlowSettings(AyuIslandsState()) },
                onFailure = { throw it },
                createTimer = { delay, update ->
                    object : Timer(delay, { update() }) {
                        override fun restart() {
                            scheduled.add(this)
                        }
                    }
                },
            )
        try {
            controller.configure(request) { _, timer -> recorded += timer }
            val original = recorded.single()
            controller.release(request)
            assertTrue(original.isRunning)
            assertEquals(1, recorded.size)
            available.add(late)
            controller.scheduleGraphRefresh()
            val refresh = scheduled.single()
            refresh.actionListeners.forEach { it.actionPerformed(ActionEvent(refresh, 0, "layout")) }

            assertEquals(1, recorded.size)
            val fade = second.javaClass.getDeclaredField("fadeTimer").apply { isAccessible = true }
            assertTrue(assertIs<Timer>(fade.get(second)).isRunning)
            assertNull(controller.currentPlayback)
            assertSame(config, controller.currentConfig)
        } finally {
            controller.dispose()
            first.stopAnimation()
            second.stopAnimation()
        }
    }

    @Test
    fun `route handoff renders the actual signal configuration instead of stored preferences`() {
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC, amplitude = 27, loopSeconds = 18f)
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        val target = RouteOverlay("Editor", pane, JPanel(), JLayeredPane())
        val request = RoutePlayback(config, listOf(target))
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { listOf(target) },
                focusedSurfaceId = { "Editor" },
                settings = { loadGlowSettings(AyuIslandsState()) },
                onFailure = { throw it },
            )
        val coordinator = mockk<RouteCoordinator>()
        every { coordinator.handle(any()) } returns RouteUpdate()
        seedCoordinator(controller, coordinator)
        val rootId = RouteRootId(1)
        val frame =
            routeFrame().copy(
                signal = WaveformFrame(config, TravelDirection.CLOCKWISE),
                slices =
                    listOf(
                        bridgeSlice(connector("Editor", "Commit")).copy(target = RoutePaintTarget.Root(rootId)),
                    ),
            )
        try {
            controller.configure(request)
            seedRouteRoot(controller, rootId, mockk(relaxed = true))
            val event = RouteEvent.ApplicationActiveChanged(false)
            every { coordinator.handle(event) } returns RouteUpdate(frame = frame)
            controller.release(request)
            controller.handle(event, requireCompletion = true)

            val layersField = controller.javaClass.getDeclaredField("layers").apply { isAccessible = true }
            val layer = assertIs<WaveformRouteLayer>((layersField.get(controller) as Map<*, *>)[rootId])
            val styleField = layer.javaClass.getDeclaredField("style").apply { isAccessible = true }
            assertSame(config, assertIs<RouteLayerStyle>(styleField.get(layer)).config)
        } finally {
            controller.dispose()
            pane.stopAnimation()
        }
    }

    @Test
    fun `explicit route retains graph and fallback fades before scheduling`() {
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        pane.stopAnimation()
        val retained = pane.Playback()
        val target = RouteOverlay("Editor", pane, JPanel(), JLayeredPane())
        val request = RoutePlayback(config, listOf(target))
        val observed = mutableListOf<Timer>()
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { listOf(target) },
                focusedSurfaceId = { "Editor" },
                settings = { error("Explicit playback must not read stored preferences") },
                onFailure = {},
            )
        val coordinator = mockk<RouteCoordinator>()
        every { coordinator.handle(any()) } returns RouteUpdate()
        seedCoordinator(controller, coordinator)
        try {
            controller.configure(request) { overlay, timer ->
                assertSame(target, overlay)
                assertFalse(timer.isRunning)
                observed += timer
                retained.recordFade(timer)
            }
            assertEquals(1, observed.size)
            val event = RouteEvent.ApplicationActiveChanged(false)
            every { coordinator.handle(event) } throws IllegalStateException("Native reconciliation failure")
            assertFailsWith<IllegalStateException> { controller.handle(event, requireCompletion = true) }
            assertEquals(2, observed.size)
            assertSame(request, controller.currentPlayback)

            var replacement: Timer? = null
            pane.startFadeIn { replacement = it }
            controller.dispose(requireCompletion = true)
            retained.stop()
            assertTrue(observed.none(Timer::isRunning))
            assertTrue(assertNotNull(replacement).isRunning)
            assertNull(controller.currentPlayback)
        } finally {
            controller.dispose()
            retained.stop()
            pane.stopAnimation()
        }
    }

    @Test
    fun `explicit route rejects an unresolved pane failure before starting shared playback`() {
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        pane.failRouteWaveform(IllegalStateException("Foreign renderer failure"))
        val failure = assertNotNull(pane.waveformFailure)
        val appearance = pane.captureAppearance()
        val target = RouteOverlay("Editor", pane, JPanel(), JLayeredPane())
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { listOf(target) },
                focusedSurfaceId = { "Editor" },
                settings = { error("Failed panes cannot fall back to stored settings") },
                onFailure = { error("Preparation must not replace the existing failure") },
            )
        try {
            assertFailsWith<IllegalStateException> { controller.configure(RoutePlayback(config, listOf(target))) }

            assertFalse(controller.isActive)
            assertFalse(pane.isWaveform)
            assertSame(failure, pane.waveformFailure)
            assertEquals(appearance, pane.captureAppearance())
        } finally {
            controller.dispose()
            pane.stopAnimation()
        }
    }

    @Test
    fun `current route configuration follows its native owner and ends with disposal`() {
        val initial = WaveformConfig(movement = WaveformMovement.CHAOTIC, amplitude = 21)
        val changed = initial.copy(amplitude = 38)
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { emptyList() },
                focusedSurfaceId = { null },
                settings = { error("Actual route configuration must not read stored preferences") },
                onFailure = {},
            )
        try {
            assertNull(controller.currentConfig)
            seedCoordinator(controller, RouteCoordinator(initial))
            assertSame(initial, controller.currentConfig)

            controller.handle(RouteEvent.Configure(changed), requireCompletion = true)
            assertSame(changed, controller.currentConfig)

            controller.dispose(requireCompletion = true)
            assertNull(controller.currentConfig)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `explicit application activity reconciliation reports native failure to its caller`() {
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        val target = RouteOverlay("Editor", pane, JPanel(), JLayeredPane())
        val request = RoutePlayback(config, listOf(target))
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { listOf(target) },
                focusedSurfaceId = { "Editor" },
                settings = { error("Explicit reconciliation must not read stored preferences") },
                onFailure = {},
            )
        val coordinator = mockk<RouteCoordinator>()
        every { coordinator.handle(any()) } returns RouteUpdate()
        seedCoordinator(controller, coordinator)
        try {
            controller.configure(request)
            val event = RouteEvent.ApplicationActiveChanged(false)
            val cause = IllegalStateException("Failed while reconciling application activity")
            every { coordinator.handle(event) } throws cause

            assertSame(
                cause,
                assertFailsWith<IllegalStateException> { controller.handle(event, requireCompletion = true) },
            )

            assertSame(cause, assertNotNull(pane.waveformFailure).cause)
            assertFalse(controller.isActive)
        } finally {
            controller.dispose()
            pane.stopAnimation()
        }
    }

    @Test
    fun `explicit reconciliation cannot claim settlement without a route owner`() {
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { emptyList() },
                focusedSurfaceId = { null },
                settings = { error("Inactive reconciliation must not read stored preferences") },
                onFailure = {},
            )
        val event = RouteEvent.ApplicationActiveChanged(true)

        assertFailsWith<IllegalStateException> { controller.handle(event, requireCompletion = true) }
        assertFalse(controller.handle(event))
    }

    @Test
    fun `route failure retains the exact request origin through the local engine fallback`() {
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        val target = RouteOverlay("Editor", pane, JPanel(), JLayeredPane())
        val request = RoutePlayback(config, listOf(target))
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { listOf(target) },
                focusedSurfaceId = { "Editor" },
                settings = { error("Stored preferences must not drive explicit playback") },
                onFailure = {},
            )
        val coordinator = mockk<RouteCoordinator>()
        val cause = IllegalStateException("Native render failed")
        every { coordinator.handle(any()) } throws cause
        seedCoordinator(controller, coordinator)
        try {
            assertSame(cause, assertFailsWith<IllegalStateException> { controller.configure(request) })
            val failure = assertNotNull(pane.waveformFailure)
            assertSame(cause, failure.cause)
            assertSame(request, assertIs<GlowFailureOrigin.Route>(failure.origin).playback)
            pane.configureWaveform(GlowShape.WAVEFORM, config)
            assertSame(failure, pane.waveformFailure)

            pane.failRouteWaveform(cause, request)
            assertNotSame(failure, pane.waveformFailure)
            assertSame(request, assertIs<GlowFailureOrigin.Route>(assertNotNull(pane.waveformFailure).origin).playback)
        } finally {
            controller.dispose()
            pane.stopAnimation()
        }
    }

    @Test
    fun `explicit route cleanup never changes a replacement pane under the same id`() {
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)
        val original = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        original.configureWaveform(GlowShape.WAVEFORM, config)
        val replacement = spyk(GlowGlassPane(Color.MAGENTA, GlowStyle.GRADIENT, 70, 11))
        val target = RouteOverlay("Editor", original, JPanel(), JLayeredPane())
        var available = listOf(target)
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { available },
                focusedSurfaceId = { "Editor" },
                settings = { error("Stored preferences must not drive explicit playback") },
                onFailure = {},
            )
        mockkStatic(PowerSaveMode::class)
        every { PowerSaveMode.isEnabled() } returns false
        try {
            controller.configure(RoutePlayback(config, listOf(target)))
            available = listOf(target.copy(pane = replacement))

            controller.dispose(requireCompletion = true)

            verify(exactly = 0) { replacement.configureRouteMode(any()) }
            assertFalse(controller.isActive)
        } finally {
            available = listOf(target)
            controller.dispose()
            original.stopAnimation()
            replacement.stopAnimation()
            unmockkStatic(PowerSaveMode::class)
        }
    }

    @Test
    fun `explicit route targets and waveform survive layout refresh without reading stored preferences`() {
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC, amplitude = 17, loopSeconds = 9.5f)
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.GRADIENT, 73, 11)
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        val target = RouteOverlay("Editor", pane, JPanel(), JLayeredPane())
        val targets = mutableListOf(target)
        val scheduled = mutableListOf<Timer>()
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { listOf(target) },
                focusedSurfaceId = { "Editor" },
                settings = { error("Stored preferences must not drive explicit playback") },
                onFailure = { throw it },
                createTimer = { delay, update ->
                    object : Timer(delay, { update() }) {
                        override fun restart() {
                            scheduled.add(this)
                        }
                    }
                },
            )
        mockkStatic(PowerSaveMode::class)
        every { PowerSaveMode.isEnabled() } returns false
        try {
            controller.configure(RoutePlayback(config, targets))
            targets.clear()
            controller.scheduleGraphRefresh()
            val timer = scheduled.single()
            timer.actionListeners.forEach { it.actionPerformed(ActionEvent(timer, 0, "layout")) }

            assertTrue(controller.isActive)
            val style =
                assertNotNull(
                    captureRouteStyle(controller.currentPlayback, listOf(target), { listOf(target) }, config),
                )
            assertEquals(config, style.config)
            assertSame(Color.CYAN, style.accent)
            assertEquals(73, style.intensity)
            assertEquals(11, style.width)
        } finally {
            controller.dispose()
            pane.stopAnimation()
            unmockkStatic(PowerSaveMode::class)
        }
    }

    @Test
    fun `explicit route refuses heterogeneous native peers without changing their appearance`() {
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)
        val first = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        val second = GlowGlassPane(Color.MAGENTA, GlowStyle.SHARP_NEON, 80, 13)
        first.configureWaveform(GlowShape.WAVEFORM, config)
        second.configureWaveform(GlowShape.WAVEFORM, config.copy(amplitude = 13))
        val targets =
            listOf(
                RouteOverlay("Editor", first, JPanel(), JLayeredPane()),
                RouteOverlay("Project", second, JPanel(), JLayeredPane()),
            )
        val before = targets.map { it.pane.captureAppearance() }
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { targets },
                focusedSurfaceId = { "Editor" },
                settings = { loadGlowSettings(AyuIslandsState()) },
                onFailure = {},
            )
        mockkStatic(PowerSaveMode::class)
        every { PowerSaveMode.isEnabled() } returns false
        try {
            assertFailsWith<IllegalStateException> {
                controller.configure(RoutePlayback(config, targets))
            }
            assertFalse(controller.isActive)
            assertEquals(before, targets.map { it.pane.captureAppearance() })
        } finally {
            controller.dispose()
            targets.forEach { it.pane.stopAnimation() }
            unmockkStatic(PowerSaveMode::class)
        }
    }

    @Test
    fun `failed route timer startup retains the scheduled timer for cleanup`() {
        var isScheduled = false
        val timer =
            object : Timer(1, {}) {
                override fun start() {
                    isScheduled = true
                    error("Native timer started before failure")
                }

                override fun stop() {
                    isScheduled = false
                }
            }
        val controller = controller(createTimer = { _, _ -> timer })
        val coordinator = mockk<RouteCoordinator>()
        every { coordinator.handle(any()) } returns RouteUpdate(timerDirective = TimerDirective.START)
        seedCoordinator(controller, coordinator)
        try {
            controller.handle(RouteEvent.Keystroke(100L))
            assertFalse(isScheduled)
            assertFalse(controller.isActive)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `transactional route configuration propagates a contained render failure`() {
        val failures = mutableListOf<RuntimeException>()
        val controller = controller(onFailure = { failures += it })
        val coordinator = mockk<RouteCoordinator>()
        val failure = IllegalStateException("Native route configuration failed")
        every { coordinator.handle(any()) } throws failure
        seedCoordinator(controller, coordinator)
        try {
            val thrown =
                assertFailsWith<IllegalStateException> {
                    controller.configure(WaveformConfig(), requireCompletion = true)
                }

            assertSame(failure, thrown)
            assertFalse(controller.isActive)
            assertEquals(listOf<RuntimeException>(failure), failures)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `transactional route disposal reports retained peer cleanup and succeeds on retry`() {
        val controller = controller()
        val connector = connector("Editor", "Commit")
        val window = mockk<JWindow>(relaxed = true)
        val owner = mockk<Window>(relaxed = true)
        every { owner.isDisplayable } returns true
        var canDispose = false
        every { window.dispose() } answers { check(canDispose) { "Native peer remains alive" } }
        val bridge = CrossWindowBridge(onFailure = {}, createWindow = { window }, makeClickThrough = { true })
        bridge.show(owner, connector, routeFrame(connector), bridgeSlice(connector), bridgeStyle())
        seedBridge(controller, connector, bridge)
        try {
            assertFailsWith<IllegalStateException> { controller.dispose(requireCompletion = true) }
            assertTrue(bridge.hasPendingCleanup)
            assertSame(bridge, bridgeAt(controller, connector))

            canDispose = true
            controller.dispose(requireCompletion = true)
            assertFalse(bridge.hasPendingCleanup)
            assertEquals(0, bridgeCount(controller))
        } finally {
            canDispose = true
            controller.dispose()
        }
    }

    @Test
    fun `late root failure leaves the replacement coordinator active`() = assertRetiredRoot(shouldReplaceOwner = true)

    @Test
    fun `late root failure leaves the replacement layer intact`() = assertRetiredRoot(shouldReplaceOwner = false)

    @Test
    fun `late bridge failure leaves the replacement coordinator intact`() =
        assertRetiredBridge(shouldReplaceOwner = true)

    @Test
    fun `late bridge failure leaves the replacement peer intact`() = assertRetiredBridge(shouldReplaceOwner = false)

    private fun assertRetiredRoot(shouldReplaceOwner: Boolean) {
        val failures = mutableListOf<RuntimeException>()
        val controller = controller(onFailure = { failures += it })
        seedCoordinator(controller, mockk())
        val rootId = RouteRootId(1)
        seedRouteRoot(controller, rootId, mockk(relaxed = true))
        val frame =
            routeFrame().copy(
                slices =
                    listOf(
                        bridgeSlice(connector("Editor", "Commit")).copy(
                            target = RoutePaintTarget.Root(rootId),
                            surfaceId = "Editor",
                        ),
                    ),
            )
        val render =
            controller.javaClass.getDeclaredMethod("renderFrame", RouteFrame::class.java, RouteLayerStyle::class.java)
        render.isAccessible = true
        render.invoke(controller, frame, bridgeStyle())
        val layersField = controller.javaClass.getDeclaredField("layers").apply { isAccessible = true }
        val layers = layersField.get(controller) as Map<*, *>
        val before = layers[rootId] as WaveformRouteLayer
        val replacement =
            if (shouldReplaceOwner) {
                seedCoordinator(controller, mockk())
                before
            } else {
                WaveformRouteLayer(rootId) {}.also { layer ->
                    layers.javaClass.getMethod("put", Any::class.java, Any::class.java).invoke(layers, rootId, layer)
                }
            }
        val failure = IllegalStateException("Retired root failed")
        val report = before.javaClass.getDeclaredMethod("reportFailure", RuntimeException::class.java)
        report.isAccessible = true
        try {
            report.invoke(before, failure)

            assertTrue(controller.isActive)
            assertSame(replacement, layers[rootId])
            assertEquals(listOf<RuntimeException>(failure), failures)

            val currentFailure = IllegalStateException("Current root failed")
            val fail = controller.javaClass.getDeclaredMethod("fail", RuntimeException::class.java)
            fail.isAccessible = true
            fail.invoke(controller, currentFailure)
            assertFalse(controller.isActive)
            assertEquals(listOf<RuntimeException>(failure, currentFailure), failures)
        } finally {
            controller.dispose()
            (before.parent as? JLayeredPane)?.remove(before)
        }
    }

    private fun assertRetiredBridge(shouldReplaceOwner: Boolean) {
        val controller = controller()
        val owner = mockk<RouteCoordinator>(relaxed = true)
        seedCoordinator(controller, owner)
        val connector = connector("Editor", "Commit")
        seedBridgeTarget(controller, connector)
        val create = controller.javaClass.getDeclaredMethod("bridgeFor", RouteConnector::class.java)
        create.isAccessible = true
        val before = create.invoke(controller, connector) as CrossWindowBridge
        val currentOwner =
            if (shouldReplaceOwner) {
                mockk<RouteCoordinator>(relaxed = true).also { seedCoordinator(controller, it) }
            } else {
                owner
            }
        val replacement =
            if (shouldReplaceOwner) {
                before
            } else {
                mockk<CrossWindowBridge>(relaxed = true).also {
                    seedBridge(controller, connector, it)
                }
            }
        val fail = before.javaClass.getDeclaredMethod("fail", RouteConnectorId::class.java, Throwable::class.java)
        fail.isAccessible = true
        try {
            fail.invoke(before, connector.id, IllegalStateException("Retired bridge failed"))

            assertTrue(controller.isActive)
            assertSame(replacement, bridgeAt(controller, connector))
            assertEquals(setOf(connector.id), bridgeTargetIds(controller))
            verify(exactly = 0) { currentOwner.handle(any()) }
            if (!shouldReplaceOwner) verify(exactly = 0) { replacement.dispose() }
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `route disposal retains native failure for the next attempt`() = assertCleanupRetry("dispose")

    @Test
    fun `route clear retains native failure for the next attempt`() = assertCleanupRetry("clear")

    @Test
    fun `bridge failure retains native failure for the next attempt`() = assertCleanupRetry("failure")

    @Test
    fun `route rendering retains native failure for the next attempt`() = assertCleanupRetry("render")

    private fun assertCleanupRetry(action: String) {
        val controller = controller { false }
        val connector = connector("Editor", "Commit")
        val window = mockk<JWindow>(relaxed = true)
        val owner = mockk<Window>(relaxed = true)
        every { owner.isDisplayable } returns true
        var canDispose = false
        var attempts = 0
        every { window.dispose() } answers {
            attempts++
            check(canDispose) { "Native window disposal failed" }
        }
        val bridge = CrossWindowBridge(onFailure = {}, createWindow = { window }, makeClickThrough = { true })
        bridge.show(owner, connector, routeFrame(connector), bridgeSlice(connector), bridgeStyle())
        seedBridge(controller, connector, bridge)
        try {
            cleanRoute(controller, connector, action)

            assertTrue(bridge.hasPendingCleanup)
            assertSame(bridge, bridgeAt(controller, connector))
            val failedAttempts = attempts

            canDispose = true
            cleanRoute(controller, connector, action)

            assertEquals(failedAttempts + 1, attempts)
            assertEquals(0, bridgeCount(controller))
        } finally {
            canDispose = true
            controller.dispose()
            bridge.dispose()
        }
    }

    @Test
    fun `route cleanup preserves a replacement registered during disposal`() {
        val controller = controller()
        val connector = connector("Editor", "Commit")
        val before = mockk<CrossWindowBridge>(relaxed = true)
        val replacement = mockk<CrossWindowBridge>(relaxed = true)
        seedBridge(controller, connector, before)
        every { before.dispose() } answers { seedBridge(controller, connector, replacement) }
        try {
            controller.dispose()

            assertSame(replacement, bridgeAt(controller, connector))
            verify(exactly = 0) { replacement.dispose() }
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `strict disposal cannot retire a route installed by timer shutdown`() {
        mockkStatic(PowerSaveMode::class)
        every { PowerSaveMode.isEnabled() } returns false
        val controller = controller()
        val initial = RoutePlayback(WaveformConfig(movement = WaveformMovement.CHAOTIC), emptyList())
        val replacement = RoutePlayback(initial.config.copy(amplitude = 37), emptyList())
        val timer = mockk<Timer>(relaxed = true)
        var shouldReplace = true
        every { timer.stop() } answers {
            if (shouldReplace) {
                shouldReplace = false
                controller.configure(replacement)
            }
        }
        try {
            controller.configure(initial)
            controller.javaClass
                .getDeclaredField("layoutTimer")
                .apply { isAccessible = true }
                .set(controller, timer)

            assertFailsWith<IllegalStateException> { controller.dispose(requireCompletion = true) }

            assertTrue(controller.isActive)
            assertSame(replacement.config, controller.currentConfig)
        } finally {
            shouldReplace = false
            controller.dispose()
            unmockkStatic(PowerSaveMode::class)
        }
    }

    @Test
    fun `strict disposal cannot retire a route installed by native peer cleanup`() {
        mockkStatic(PowerSaveMode::class)
        every { PowerSaveMode.isEnabled() } returns false
        val controller = controller()
        val initial = RoutePlayback(WaveformConfig(movement = WaveformMovement.CHAOTIC), emptyList())
        val replacement = RoutePlayback(initial.config.copy(amplitude = 37), emptyList())
        val connector = connector("Editor", "Commit")
        val bridge = mockk<CrossWindowBridge>(relaxed = true)
        var shouldReplace = true
        every { bridge.dispose() } answers {
            if (shouldReplace) {
                shouldReplace = false
                controller.configure(replacement)
            }
        }
        try {
            controller.configure(initial)
            seedBridge(controller, connector, bridge)

            assertFailsWith<IllegalStateException> { controller.dispose(requireCompletion = true) }

            assertTrue(controller.isActive)
            assertSame(replacement.config, controller.currentConfig)
        } finally {
            shouldReplace = false
            controller.dispose()
            unmockkStatic(PowerSaveMode::class)
        }
    }

    @Test
    fun `old route failure cannot mark the route installed by native cleanup as failed`() =
        assertFailureReplacement(duringCleanup = true)

    @Test
    fun `reconciliation error after owner replacement cannot fail the new route`() =
        assertFailureReplacement(duringCleanup = false)

    private fun assertFailureReplacement(duringCleanup: Boolean) {
        mockkStatic(PowerSaveMode::class)
        every { PowerSaveMode.isEnabled() } returns false
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        val target = RouteOverlay("Editor", pane, JPanel(), JLayeredPane())
        val initial = RoutePlayback(config, emptyList())
        val replacement = RoutePlayback(config.copy(amplitude = 37), listOf(target))
        pane.configureWaveform(GlowShape.WAVEFORM, replacement.config)
        val controller =
            RouteController(
                project = mockk(relaxed = true),
                overlays = { listOf(target) },
                focusedSurfaceId = { "Editor" },
                settings = { error("Explicit route recovery must not use stored settings") },
                onFailure = {},
            )
        val event = RouteEvent.ApplicationActiveChanged(true)
        val failedCoordinator = mockk<RouteCoordinator>()
        val failure = IllegalStateException("Old route render failed")
        every { failedCoordinator.handle(event) } answers {
            if (!duringCleanup) {
                controller.dispose(requireCompletion = true)
                controller.configure(replacement)
            }
            throw failure
        }
        val bridge = mockk<CrossWindowBridge>(relaxed = true)
        var shouldReplace = true
        every { bridge.dispose() } answers {
            if (shouldReplace) {
                shouldReplace = false
                controller.configure(replacement)
            }
        }
        try {
            controller.configure(initial)
            seedCoordinator(controller, failedCoordinator)
            if (duringCleanup) seedBridge(controller, connector("Editor", "Commit"), bridge)

            assertFailsWith<IllegalStateException> { controller.handle(event, requireCompletion = true) }

            assertTrue(controller.isActive)
            assertSame(replacement.config, controller.currentConfig)
            assertNull(pane.waveformFailure)
        } finally {
            shouldReplace = false
            controller.dispose()
            pane.stopAnimation()
            unmockkStatic(PowerSaveMode::class)
        }
    }

    @Test
    fun `new bridge geometry waits for cleanup of its previous native peer`() {
        val controller = controller()
        val previous = connector("Editor", "Commit", verticalOffset = 0f)
        val next = connector("Editor", "Commit", verticalOffset = 50f)
        val bridge = mockk<CrossWindowBridge>(relaxed = true)
        var canDispose = false
        var isPending = true
        every { bridge.hasPendingCleanup } answers { isPending }
        every { bridge.dispose() } answers { if (canDispose) isPending = false }
        seedBridge(controller, previous, bridge)
        seedBridgeTarget(controller, next)
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        try {
            renderBridge(controller, routeFrame(next))

            assertSame(bridge, bridgeAt(controller, previous))
            assertEquals(1, bridgeCount(controller))
            verify(exactly = 0) {
                anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
            }

            canDispose = true
            renderBridge(controller, routeFrame(next))

            assertNull(bridgeAt(controller, previous))
            assertNotNull(bridgeAt(controller, next))
            assertEquals(1, bridgeCount(controller))
            verify(exactly = 1) {
                anyConstructed<CrossWindowBridge>().show(any(), next, any(), any<List<RouteSlice>>(), any())
            }
        } finally {
            canDispose = true
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `bridge failure cleans its peer while held without starting follow-up rendering`() {
        var allowed = false
        val controller = controller { allowed }
        val coordinator = mockk<RouteCoordinator>()
        every { coordinator.handle(any()) } returns RouteUpdate(timerDirective = TimerDirective.START)
        seedCoordinator(controller, coordinator)
        val failed = connector("Editor", "Commit")
        val bridge = mockk<CrossWindowBridge>(relaxed = true)
        seedBridge(controller, failed, bridge)
        val failureMethod =
            controller.javaClass.getDeclaredMethod("recordBridgeFailure", RouteConnectorId::class.java).apply {
                isAccessible = true
            }
        mockkStatic(PowerSaveMode::class)
        every { PowerSaveMode.isEnabled() } returns false
        try {
            failureMethod.invoke(controller, failed.id)

            verify(exactly = 1) { bridge.dispose() }
            verify(exactly = 0) { coordinator.handle(any()) }
            assertNull(routeTimer(controller))

            allowed = true
            controller.configure(WaveformConfig())
            assertNotNull(routeTimer(controller))
            verify(exactly = 0) { coordinator.handle(ofType<RouteEvent.BridgeFailed>()) }
        } finally {
            controller.dispose()
            unmockkStatic(PowerSaveMode::class)
        }
    }

    @Test
    fun `already armed frame callback respects current admission`() {
        var allowed = true
        val controller = controller { allowed }
        val coordinator = mockk<RouteCoordinator>()
        every { coordinator.handle(any()) } returns RouteUpdate(timerDirective = TimerDirective.START)
        seedCoordinator(controller, coordinator)
        val window = mockk<Window>()
        every { window.isActive } returns true
        seedRouteRoot(controller, RouteRootId(1), window)
        try {
            controller.handle(RouteEvent.Tick(100L))
            val timer = assertNotNull(routeTimer(controller))
            timer.stop()
            allowed = false
            timer.actionListeners.forEach { it.actionPerformed(ActionEvent(timer, 0, "queued")) }

            verify(exactly = 1) { coordinator.handle(any()) }

            allowed = true
            timer.actionListeners.forEach { it.actionPerformed(ActionEvent(timer, 0, "current")) }
            verify(exactly = 2) { coordinator.handle(any()) }
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `already queued layout callback is deferred until fresh refresh admission`() {
        var allowed = true
        val scheduled = mutableListOf<Timer>()
        val controller =
            controller(
                asyncUpdatesAllowed = { allowed },
                createTimer = { delay, update ->
                    object : Timer(delay, { update() }) {
                        override fun restart() {
                            scheduled.add(this)
                        }
                    }
                },
            )
        val coordinator = mockk<RouteCoordinator>()
        every { coordinator.handle(any()) } returns RouteUpdate(timerDirective = TimerDirective.KEEP)
        seedCoordinator(controller, coordinator)
        mockkStatic(PowerSaveMode::class)
        every { PowerSaveMode.isEnabled() } returns false
        try {
            controller.scheduleGraphRefresh()
            val timer = scheduled.single()
            assertEquals(1, timer.actionListeners.size)
            allowed = false
            timer.actionListeners.forEach { it.actionPerformed(ActionEvent(timer, 0, "queued")) }
            controller.scheduleGraphRefresh()

            verify(exactly = 0) { coordinator.handle(any()) }
            assertEquals(1, scheduled.size)

            allowed = true
            controller.scheduleGraphRefresh()
            timer.actionListeners.forEach { it.actionPerformed(ActionEvent(timer, 0, "current")) }
            assertEquals(2, scheduled.size)
            verify(exactly = 1) { coordinator.handle(any()) }
        } finally {
            controller.dispose()
            unmockkStatic(PowerSaveMode::class)
        }
    }

    @Test
    fun `retired frame callback cannot advance a replacement route`() {
        val controller = controller()
        val before = mockk<RouteCoordinator>()
        every { before.handle(any()) } returns RouteUpdate(timerDirective = TimerDirective.START)
        seedCoordinator(controller, before)
        val window = mockk<Window>()
        every { window.isActive } returns true
        seedRouteRoot(controller, RouteRootId(1), window)
        controller.handle(RouteEvent.Tick(100L))
        val retiredTimer = assertNotNull(routeTimer(controller))
        controller.dispose()

        val replacement = mockk<RouteCoordinator>()
        every { replacement.handle(any()) } returns RouteUpdate(timerDirective = TimerDirective.START)
        seedCoordinator(controller, replacement)
        seedRouteRoot(controller, RouteRootId(2), window)
        try {
            controller.handle(RouteEvent.Tick(200L))
            assertNotNull(routeTimer(controller)).stop()

            retiredTimer.actionListeners.forEach { it.actionPerformed(ActionEvent(retiredTimer, 0, "retired")) }

            verify(exactly = 1) { replacement.handle(any()) }
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `inactive route does not advance until one route window is active`() {
        val controller = controller()
        val coordinator = mockk<RouteCoordinator>()
        every { coordinator.handle(any()) } returns RouteUpdate(timerDirective = TimerDirective.START)
        seedCoordinator(controller, coordinator)
        val firstWindow = mockk<Window>()
        val secondWindow = mockk<Window>()
        every { firstWindow.isActive } returns false
        every { secondWindow.isActive } returns false
        seedRouteRoot(controller, RouteRootId(1), firstWindow)
        seedRouteRoot(controller, RouteRootId(2), secondWindow)

        controller.handle(RouteEvent.Tick(100L))

        verify(exactly = 1) { coordinator.handle(RouteEvent.Tick(100L, isWindowActive = false)) }
        assertNull(routeTimer(controller))

        every { secondWindow.isActive } returns true
        controller.handle(RouteEvent.Tick(200L))

        verify(exactly = 1) { coordinator.handle(RouteEvent.Tick(200L, isWindowActive = true)) }
        assertNotNull(routeTimer(controller)).stop()
    }

    @Test
    fun `every visible window bridge slice is rendered`() {
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        val controller =
            RouteController(
                project = mockk<Project>(relaxed = true),
                overlays = { emptyList() },
                focusedSurfaceId = { null },
                settings = { loadGlowSettings(AyuIslandsState()) },
                onFailure = {},
            )
        val first = connector("Editor", "Commit")
        val second = connector("Commit", "Project")
        seedBridgeTarget(controller, first)
        seedBridgeTarget(controller, second)
        val frame =
            RouteFrame(
                signal =
                    WaveformFrame(
                        config = WaveformConfig(movement = WaveformMovement.CHAOTIC),
                        direction = TravelDirection.CLOCKWISE,
                    ),
                centerDistance = 10f,
                signalSpan = 20f,
                currentSurfaceId = "Commit",
                slices = listOf(bridgeSlice(first), bridgeSlice(second)),
            )
        val style =
            RouteLayerStyle(
                accent = Color.ORANGE,
                glowStyle = GlowStyle.SOFT,
                intensity = 100,
                width = 2,
                arcWidth = 2,
                config = WaveformConfig(movement = WaveformMovement.CHAOTIC),
            )

        try {
            val method =
                controller.javaClass.getDeclaredMethod(
                    "renderBridge",
                    RouteFrame::class.java,
                    RouteLayerStyle::class.java,
                )
            method.isAccessible = true
            method.invoke(controller, frame, style)

            verify(exactly = 2) {
                anyConstructed<CrossWindowBridge>().show(any(), any(), frame, any<List<RouteSlice>>(), style)
            }
        } finally {
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `bridge endpoints keep independent renderers`() {
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        val controller = controller()
        val start = connector("Editor", "Commit", RouteEndpoint.START)
        val end = connector("Editor", "Commit", RouteEndpoint.END)
        seedBridgeTarget(controller, start)
        seedBridgeTarget(controller, end)

        try {
            renderBridge(controller, routeFrame(start, end))

            assertEquals(2, bridgeCount(controller))
        } finally {
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `layout refresh retains every visible bridge target`() {
        val controller = controller()
        val first = connector("Editor", "Commit")
        val second = connector("Commit", "Project")
        seedBridgeTarget(controller, first)
        seedBridgeTarget(controller, second)
        setLastFrame(controller, routeFrame(first, second))

        cacheBridgeTargets(controller, RouteGraph(emptyMap(), emptyMap()))

        assertEquals(setOf(first.id, second.id), bridgeTargetIds(controller))
        controller.dispose()
    }

    @Test
    fun `current endpoint keeps peer preserved geometry`() {
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        val controller = controller()
        val start = connector("Editor", "Commit", RouteEndpoint.START, verticalOffset = 0f)
        val oldEnd = connector("Editor", "Commit", RouteEndpoint.END, verticalOffset = 20f)
        val currentEnd = connector("Editor", "Commit", RouteEndpoint.END, verticalOffset = 100f)
        val startTarget = seedBridgeTarget(controller, start)
        val currentEndTarget = seedBridgeTarget(controller, currentEnd)
        seedBridgeTarget(controller, oldEnd)
        seedCurrentTargets(controller, start.id, startTarget, currentEndTarget)
        val frame = routeFrame(start, oldEnd)

        try {
            renderBridge(controller, frame)

            verify(exactly = 1) {
                anyConstructed<CrossWindowBridge>().show(any(), oldEnd, frame, listOf(frame.slices[1]), any())
            }
        } finally {
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `newer bridge render survives reentrant predecessor`() {
        val controller = controller()
        val first = connector("Editor", "Commit")
        val replacement = connector("Commit", "Project")
        val firstBridge = mockk<CrossWindowBridge>(relaxed = true)
        val replacementBridge = mockk<CrossWindowBridge>(relaxed = true)
        seedBridge(controller, first, firstBridge)
        seedBridge(controller, replacement, replacementBridge)
        seedBridgeTarget(controller, first)
        seedBridgeTarget(controller, replacement)
        val replacementFrame = routeFrame(replacement)
        every { firstBridge.show(any(), first, any(), any<List<RouteSlice>>(), any()) } answers {
            renderBridge(controller, replacementFrame)
        }

        try {
            renderBridge(controller, routeFrame(first))

            verify(exactly = 0) { replacementBridge.hide() }
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `same endpoint bridge slices share one render`() {
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        val controller = controller()
        val connector = connector("Editor", "Commit")
        seedBridgeTarget(controller, connector)
        val frame = routeFrame(connector, connector)

        try {
            renderBridge(controller, frame)

            verify(exactly = 1) {
                anyConstructed<CrossWindowBridge>().show(any(), connector, frame, frame.slices, any())
            }
        } finally {
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `obsolete bridge geometry is disposed`() {
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        every { anyConstructed<CrossWindowBridge>().dispose() } just Runs
        val controller = controller()

        try {
            repeat(10) { index ->
                val connector =
                    connector(
                        sourceId = "Editor",
                        targetId = "Commit",
                        verticalOffset = index * 10f,
                    )
                replaceBridgeTarget(controller, connector)
                renderBridge(controller, routeFrame(connector))
            }

            assertEquals(1, bridgeCount(controller))
            verify(exactly = 9) { anyConstructed<CrossWindowBridge>().dispose() }
        } finally {
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `newer bridge survives reentrant cleanup`() {
        val controller = controller()
        val first = connector("Editor", "Commit")
        val replacement = connector("Commit", "Project")
        val firstBridge = mockk<CrossWindowBridge>(relaxed = true)
        val replacementBridge = mockk<CrossWindowBridge>(relaxed = true)
        seedBridge(controller, first, firstBridge)
        seedBridge(controller, replacement, replacementBridge)
        seedBridgeTarget(controller, first)
        seedBridgeTarget(controller, replacement)
        var didReenter = false
        every { firstBridge.hide() } answers {
            if (!didReenter) {
                didReenter = true
                renderBridge(controller, routeFrame(replacement))
            }
        }

        try {
            renderBridge(controller, routeFrame())

            verify(exactly = 0) { replacementBridge.hide() }
            verify(exactly = 0) { replacementBridge.dispose() }
        } finally {
            controller.dispose()
        }
    }

    private fun controller(
        createTimer: (Int, () -> Unit) -> Timer = { delay, update -> Timer(delay) { update() } },
        onFailure: (RuntimeException) -> Unit = {},
        asyncUpdatesAllowed: () -> Boolean = { true },
    ): RouteController =
        RouteController(
            project = mockk<Project>(relaxed = true),
            overlays = { emptyList() },
            focusedSurfaceId = { null },
            settings = { loadGlowSettings(AyuIslandsState()) },
            onFailure = onFailure,
            asyncUpdatesAllowed = asyncUpdatesAllowed,
            createTimer = createTimer,
        )

    private fun cleanRoute(
        controller: RouteController,
        connector: RouteConnector,
        action: String,
    ) {
        when (action) {
            "dispose" -> controller.dispose()
            "render" -> renderBridge(controller, routeFrame())
            "clear" -> {
                val method = controller.javaClass.getDeclaredMethod("clearRendering")
                method.isAccessible = true
                method.invoke(controller)
            }
            "failure" -> {
                val method = controller.javaClass.getDeclaredMethod("recordBridgeFailure", RouteConnectorId::class.java)
                method.isAccessible = true
                method.invoke(controller, connector.id)
            }
            else -> error("Unknown cleanup action: $action")
        }
    }

    private fun bridgeAt(
        controller: RouteController,
        connector: RouteConnector,
    ): CrossWindowBridge? {
        val field = controller.javaClass.getDeclaredField("bridges")
        field.isAccessible = true
        return (field.get(controller) as Map<*, *>)[connector] as? CrossWindowBridge
    }

    private fun seedCoordinator(
        controller: RouteController,
        coordinator: RouteCoordinator,
    ) {
        val field = controller.javaClass.getDeclaredField("coordinator")
        field.isAccessible = true
        field.set(controller, coordinator)
    }

    private fun seedRouteRoot(
        controller: RouteController,
        rootId: RouteRootId,
        window: Window,
    ) {
        val rootClass = Class.forName("dev.ayuislands.glow.RouteRoot")
        val constructor =
            rootClass.getDeclaredConstructor(
                JLayeredPane::class.java,
                Window::class.java,
                Point::class.java,
            )
        constructor.isAccessible = true
        val root = constructor.newInstance(JLayeredPane(), window, Point())
        val field = controller.javaClass.getDeclaredField("roots")
        field.isAccessible = true
        val roots = field.get(controller)
        val put = roots.javaClass.getMethod("put", Any::class.java, Any::class.java)
        put.invoke(roots, rootId, root)
    }

    private fun routeTimer(controller: RouteController): Timer? {
        val field = controller.javaClass.getDeclaredField("timer")
        field.isAccessible = true
        return field.get(controller) as Timer?
    }

    private fun renderBridge(
        controller: RouteController,
        frame: RouteFrame,
    ) {
        val method =
            controller.javaClass.getDeclaredMethod(
                "renderBridge",
                RouteFrame::class.java,
                RouteLayerStyle::class.java,
            )
        method.isAccessible = true
        method.invoke(controller, frame, bridgeStyle())
    }

    private fun cacheBridgeTargets(
        controller: RouteController,
        graph: RouteGraph,
    ) {
        val method = controller.javaClass.getDeclaredMethod("cacheBridgeTargets", RouteGraph::class.java)
        method.isAccessible = true
        method.invoke(controller, graph)
    }

    private fun setLastFrame(
        controller: RouteController,
        frame: RouteFrame,
    ) {
        val field = controller.javaClass.getDeclaredField("lastFrame")
        field.isAccessible = true
        field.set(controller, frame)
    }

    private fun bridgeTargetIds(controller: RouteController): Set<RouteConnectorId> {
        val field = controller.javaClass.getDeclaredField("bridgeTargets")
        field.isAccessible = true
        val targets = field.get(controller) as Map<*, *>
        return targets.keys.filterIsInstance<RouteConnectorId>().toSet()
    }

    private fun bridgeCount(controller: RouteController): Int {
        val field = controller.javaClass.getDeclaredField("bridges")
        field.isAccessible = true
        return (field.get(controller) as Map<*, *>).size
    }

    private fun seedBridge(
        controller: RouteController,
        connector: RouteConnector,
        bridge: CrossWindowBridge,
    ) {
        val field = controller.javaClass.getDeclaredField("bridges")
        field.isAccessible = true
        val bridges = field.get(controller) as Map<*, *>
        val put = bridges.javaClass.getMethod("put", Any::class.java, Any::class.java)
        put.invoke(bridges, connector, bridge)
    }

    private fun seedBridgeTarget(
        controller: RouteController,
        connector: RouteConnector,
    ): Any {
        val owner = mockk<Window>(relaxed = true)
        val targetClass = Class.forName("dev.ayuislands.glow.BridgeTarget")
        val constructor = targetClass.getDeclaredConstructor(RouteConnector::class.java, Window::class.java)
        constructor.isAccessible = true
        val target = constructor.newInstance(connector, owner)
        val field = controller.javaClass.getDeclaredField("bridgeTargets")
        field.isAccessible = true
        val targets = field.get(controller)
        val existing = targets.javaClass.getMethod("get", Any::class.java).invoke(targets, connector.id)
        if (existing != null) {
            existing.javaClass.getMethod("add", Any::class.java).invoke(existing, target)
            return target
        }
        val put = targets.javaClass.getMethod("put", Any::class.java, Any::class.java)
        put.invoke(targets, connector.id, mutableListOf(target))
        return target
    }

    private fun seedCurrentTargets(
        controller: RouteController,
        connectorId: RouteConnectorId,
        vararg targets: Any,
    ) {
        val field = controller.javaClass.getDeclaredField("currentBridgeTargets")
        field.isAccessible = true
        field.set(controller, mapOf(connectorId to targets.toList()))
    }

    private fun replaceBridgeTarget(
        controller: RouteController,
        connector: RouteConnector,
    ) {
        val field = controller.javaClass.getDeclaredField("bridgeTargets")
        field.isAccessible = true
        val targets = field.get(controller)
        targets.javaClass
            .getMethod("clear")
            .invoke(targets)
        val target = seedBridgeTarget(controller, connector)
        seedCurrentTargets(controller, connector.id, target)
    }

    private fun connector(
        sourceId: String,
        targetId: String,
        endpoint: RouteEndpoint = RouteEndpoint.END,
        verticalOffset: Float = if (endpoint == RouteEndpoint.START) 0f else 20f,
    ): RouteConnector =
        RouteConnector(
            id = RouteConnectorId(sourceId, targetId, RouteSide.RIGHT),
            endpoint = endpoint,
            sourceId = sourceId,
            targetId = targetId,
            sourceSide = RouteSide.RIGHT,
            targetSide = RouteSide.LEFT,
            sourceDistance = 0f,
            targetDistance = 0f,
            sourcePoint = RoutePoint(0f, verticalOffset),
            targetPoint = RoutePoint(10f, verticalOffset),
            length = 10f,
            requiresWindowBridge = true,
        )

    private fun routeFrame(vararg connectors: RouteConnector): RouteFrame =
        RouteFrame(
            signal =
                WaveformFrame(
                    config = WaveformConfig(movement = WaveformMovement.CHAOTIC),
                    direction = TravelDirection.CLOCKWISE,
                ),
            centerDistance = 10f,
            signalSpan = 20f,
            currentSurfaceId = "Commit",
            slices = connectors.map(::bridgeSlice),
        )

    private fun bridgeStyle(): RouteLayerStyle =
        RouteLayerStyle(
            accent = Color.ORANGE,
            glowStyle = GlowStyle.SOFT,
            intensity = 100,
            width = 2,
            arcWidth = 2,
            config = WaveformConfig(movement = WaveformMovement.CHAOTIC),
        )

    private fun bridgeSlice(connector: RouteConnector): RouteSlice =
        RouteSlice(
            target = RoutePaintTarget.WindowBridge(connector.id),
            surfaceId = null,
            samples =
                listOf(
                    WaveformSample(
                        x = 5f,
                        y = connector.sourcePoint.y,
                        normalX = 0f,
                        normalY = -1f,
                        distance = 5f,
                        amplitudeMask = 1f,
                    ),
                ),
            distanceOffset = 0f,
            inwardEdges = emptySet(),
        )
}
