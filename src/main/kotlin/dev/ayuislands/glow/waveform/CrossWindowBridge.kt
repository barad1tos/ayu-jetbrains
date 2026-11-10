package dev.ayuislands.glow.waveform

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.MacUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.awt.AWTError
import java.awt.Color
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JWindow
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal object WindowBridgeCapability {
    @RequiresEdt
    fun isSupported(
        source: Window,
        target: Window,
    ): Boolean {
        if (!SystemInfo.isMac) return false
        val devices =
            listOfNotNull(
                source.graphicsConfiguration?.device,
                target.graphicsConfiguration?.device,
            ).distinct()
        return isSupported(
            headless = GraphicsEnvironment.isHeadless(),
            deviceSupport =
                devices.map { device ->
                    device.isWindowTranslucencySupported(
                        GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT,
                    )
                },
        )
    }

    internal fun isSupported(
        headless: Boolean,
        deviceSupport: List<Boolean>,
    ): Boolean = !headless && deviceSupport.isNotEmpty() && deviceSupport.all { it }

    @RequiresEdt
    fun makeClickThrough(window: JWindow): Boolean {
        if (!SystemInfo.isMac) return false
        window.addNotify()
        val nativeWindow = MacUtil.getWindowFromJavaWindow(window)
        if (Foundation.isNil(nativeWindow)) return false

        val ignoresMouse = AtomicBoolean()
        Foundation.executeOnMainThread(true, true) {
            Foundation.invoke(nativeWindow, "setIgnoresMouseEvents:", true)
            ignoresMouse.set(Foundation.invoke(nativeWindow, "ignoresMouseEvents").booleanValue())
        }
        return ignoresMouse.get()
    }
}

internal class CrossWindowBridge(
    private val onFailure: (RouteConnectorId) -> Unit,
    private val createWindow: (Window) -> JWindow = ::JWindow,
    private val makeClickThrough: (JWindow) -> Boolean = WindowBridgeCapability::makeClickThrough,
) : Disposable {
    private var nativeWindow: JWindow? = null
    private var routeLayer: WaveformRouteLayer? = null
    private var windowOwner: Window? = null
    private var connectorId: RouteConnectorId? = null
    private var state = State.HIDDEN
    private val failedConnectors = mutableSetOf<RouteConnectorId>()

    val isVisible: Boolean
        get() = state == State.VISIBLE

    val hasPendingCleanup: Boolean
        get() = state == State.CLEANUP_PENDING

    private val isRetired: Boolean
        get() = state == State.CLEANUP_PENDING || state == State.CLOSED

    @RequiresEdt
    fun show(
        owner: Window,
        connector: RouteConnector,
        frame: RouteFrame,
        slice: RouteSlice,
        style: RouteLayerStyle,
    ) = show(owner, connector, frame, listOf(slice), style)

    @RequiresEdt
    fun show(
        owner: Window,
        connector: RouteConnector,
        frame: RouteFrame,
        slices: List<RouteSlice>,
        style: RouteLayerStyle,
    ) {
        if (isRetired) return
        if (!owner.isDisplayable) {
            hide()
            return
        }
        if (!connector.requiresWindowBridge) {
            hide()
            return
        }
        if (slices.any { slice -> (slice.target as? RoutePaintTarget.WindowBridge)?.connectorId != connector.id }) {
            hide()
            return
        }
        val visibleSlices = slices.filter { slice -> slice.samples.isNotEmpty() }
        if (visibleSlices.isEmpty()) {
            hide()
            return
        }

        connectorId = connector.id
        try {
            val window = windowFor(owner) ?: return
            val bounds = bridgeBounds(connector, style)
            val layer = requireNotNull(routeLayer)
            window.bounds = bounds
            layer.setBounds(0, 0, bounds.width, bounds.height)
            layer.updateStyle(style)
            layer.showFrame(
                frame = frame,
                slices = visibleSlices.map { slice -> localSlice(slice, bounds) },
                surfaceBounds = emptyMap(),
            )
            revealWindow(window, connector.id)
        } catch (exception: RuntimeException) {
            fail(connector.id, exception)
        } catch (error: AWTError) {
            fail(connector.id, error)
        } catch (error: LinkageError) {
            fail(connector.id, error)
        }
    }

    private fun revealWindow(
        window: JWindow,
        targetId: RouteConnectorId,
    ) {
        if (isRetired) return
        if (!isVisible) {
            window.isVisible = true
            if (isRetired) return
            if (!makeClickThrough(window)) {
                fail(targetId, IllegalStateException("Native bridge input transparency is unavailable"))
                return
            }
        }
        if (!isRetired) state = State.VISIBLE
    }

    @RequiresEdt
    fun hide() {
        if (!isVisible) return
        try {
            routeLayer?.clearFrame()
            nativeWindow?.isVisible = false
            if (!isRetired) state = State.HIDDEN
        } catch (exception: RuntimeException) {
            fail(connectorId, exception)
        } catch (error: AWTError) {
            fail(connectorId, error)
        } catch (error: LinkageError) {
            fail(connectorId, error)
        }
    }

    @RequiresEdt
    override fun dispose() {
        if (state == State.CLOSED) return
        state = State.CLEANUP_PENDING
        try {
            closeWindow()
        } catch (exception: RuntimeException) {
            LOG.warn(DISPOSE_FAILURE, exception)
        } catch (error: AWTError) {
            LOG.warn(DISPOSE_FAILURE, error)
        } catch (error: LinkageError) {
            LOG.warn(DISPOSE_FAILURE, error)
        }
    }

    private fun windowFor(owner: Window): JWindow? {
        if (windowOwner === owner) return requireNotNull(nativeWindow)
        closeWindow()
        if (isRetired) return null
        lateinit var layer: WaveformRouteLayer
        layer =
            WaveformRouteLayer(BRIDGE_ROOT_ID) { exception ->
                if (routeLayer === layer) {
                    fail(connectorId, exception)
                } else {
                    LOG.warn("Retired cross-window waveform layer failed", exception)
                }
            }
        val window = createWindow(owner)
        nativeWindow = window
        routeLayer = layer
        windowOwner = owner
        window.apply {
            background = TRANSPARENT
            focusableWindowState = false
            isAutoRequestFocus = false
            type = Window.Type.POPUP
            contentPane = layer
        }
        if (!makeClickThrough(window)) {
            fail(connectorId, IllegalStateException("Native bridge input transparency is unavailable"))
            return null
        }
        return window
    }

    private fun closeWindow() {
        val layer = routeLayer
        val window = nativeWindow
        val steps = listOf<() -> Unit>({ layer?.clearFrame() }, { window?.isVisible = false }, { window?.dispose() })
        val failures =
            steps.mapNotNull { cleanup ->
                try {
                    cleanup()
                    null
                } catch (exception: RuntimeException) {
                    exception
                } catch (error: AWTError) {
                    error
                } catch (error: LinkageError) {
                    error
                }
            }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
            throw first
        }
        clearWindowState()
    }

    private fun fail(
        failedConnectorId: RouteConnectorId?,
        failure: Throwable,
    ) {
        state = State.CLEANUP_PENDING
        try {
            closeWindow()
        } catch (exception: RuntimeException) {
            addCleanupFailure(failure, exception)
        } catch (error: AWTError) {
            addCleanupFailure(failure, error)
        } catch (error: LinkageError) {
            addCleanupFailure(failure, error)
        }
        if (failedConnectorId == null) {
            LOG.warn("Cross-window waveform bridge failed before connector binding", failure)
            return
        }
        if (!failedConnectors.add(failedConnectorId)) return
        LOG.warn("Cross-window waveform bridge failed for connector=$failedConnectorId", failure)
        onFailure(failedConnectorId)
    }

    private fun addCleanupFailure(
        failure: Throwable,
        cleanupFailure: Throwable,
    ) {
        if (cleanupFailure !== failure) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun clearWindowState() {
        nativeWindow = null
        routeLayer = null
        windowOwner = null
        state = if (isRetired) State.CLOSED else State.HIDDEN
    }

    private enum class State {
        HIDDEN,
        VISIBLE,
        CLEANUP_PENDING,
        CLOSED,
    }

    private fun bridgeBounds(
        connector: RouteConnector,
        style: RouteLayerStyle,
    ): Rectangle {
        val left = floor(min(connector.sourcePoint.x, connector.targetPoint.x)).toInt()
        val top = floor(min(connector.sourcePoint.y, connector.targetPoint.y)).toInt()
        val right = ceil(max(connector.sourcePoint.x, connector.targetPoint.x)).toInt()
        val bottom = ceil(max(connector.sourcePoint.y, connector.targetPoint.y)).toInt()
        val margin = ceil(WaveformPainter.marginFor(style.config.amplitude, style.width)).toInt()
        return Rectangle(
            left,
            top,
            (right - left + 1).coerceAtLeast(1),
            (bottom - top + 1).coerceAtLeast(1),
        ).apply {
            grow(margin, margin)
        }
    }

    private fun localSlice(
        slice: RouteSlice,
        bounds: Rectangle,
    ): RouteSlice =
        slice.copy(
            target = RoutePaintTarget.Root(BRIDGE_ROOT_ID),
            samples =
                slice.samples.map { sample ->
                    sample.copy(
                        x = sample.x - bounds.x,
                        y = sample.y - bounds.y,
                    )
                },
        )

    private companion object {
        const val DISPOSE_FAILURE = "Failed to dispose cross-window waveform bridge"
        val TRANSPARENT = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))
        val BRIDGE_ROOT_ID = RouteRootId(0)
        val LOG = logger<CrossWindowBridge>()
    }
}
