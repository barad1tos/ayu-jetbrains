package dev.ayuislands.settings

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.awt.Color
import java.awt.Component
import java.awt.Insets
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class GlowGroupPanelLifecycleTest {
    @Test
    fun `opening hiding closing and reopening preserves the supplied preview and content`() =
        withPlatformMocks { platform ->
            val firstLabel = onEdt { JLabel("Glow preview") }
            val firstToggle = onEdt { JCheckBox("Keep my glow", true) }
            val firstPanel = createPanel(firstLabel, firstToggle)
            val originalInsets = onEdt { firstPanel.insets }
            val originalChildren = onEdt { firstPanel.components.copyOf() }

            withPanel(firstPanel) {
                onEdt {
                    firstPanel.addNotify()
                    assertTrue(firstPanel.isShowing)
                    assertTrue(firstPanel.isPreviewAnimating)
                    firstPanel.isVisible = false
                    assertFalse(firstPanel.isPreviewAnimating)
                    firstPanel.isVisible = true
                    assertTrue(firstPanel.isPreviewAnimating)
                    assertPreview(firstPanel, WAVEFORM_PREVIEW)
                    assertContentEquals(originalChildren, firstPanel.components)
                    assertEquals(originalInsets, firstPanel.insets)
                    assertEquals("Glow preview", firstLabel.text)
                    assertTrue(firstToggle.isSelected)
                    firstPanel.removeNotify()
                    assertFalse(firstPanel.isPreviewAnimating)
                    firstPanel.addNotify()
                    assertTrue(firstPanel.isPreviewAnimating)
                    assertContentEquals(originalChildren, firstPanel.components)
                    assertEquals(originalInsets, firstPanel.insets)
                    assertEquals("Glow preview", firstLabel.text)
                    assertTrue(firstToggle.isSelected)
                    firstPanel.removeNotify()
                    assertFalse(firstPanel.isPreviewAnimating)
                }

                val secondLabel = onEdt { JLabel("Glow preview") }
                val secondToggle = onEdt { JCheckBox("Keep my glow", true) }
                val reopenedPanel = createPanel(secondLabel, secondToggle)
                val reopenedChildren = onEdt { reopenedPanel.components.copyOf() }
                withPanel(reopenedPanel) {
                    onEdt {
                        reopenedPanel.addNotify()
                        assertTrue(reopenedPanel.isPreviewAnimating)
                        assertPreview(reopenedPanel, WAVEFORM_PREVIEW)
                        assertContentEquals(reopenedChildren, reopenedPanel.components)
                        assertEquals(originalInsets, reopenedPanel.insets)
                        assertEquals(
                            listOf("Glow preview", "Keep my glow"),
                            reopenedPanel.components.map(::controlText),
                        )
                        assertTrue(secondToggle.isSelected)
                    }
                }

                assertEquals(3, platform.connections.size)
                verify(exactly = 1) { platform.connections[0].disconnect() }
                verify(exactly = 1) { platform.connections[1].disconnect() }
                verify(exactly = 1) { platform.connections[2].disconnect() }
                assertFalse(firstPanel.isPreviewAnimating)
            }
        }

    @Test
    fun `Power Save events from EDT and background respect visibility without mutating preview`() =
        withPlatformMocks { platform ->
            val label = onEdt { JLabel("Settings") }
            val toggle = onEdt { JCheckBox("Enabled", true) }
            val panel = createPanel(label, toggle)
            val originalChildren = onEdt { panel.components.copyOf() }
            val originalInsets = onEdt { panel.insets }
            withPanel(panel) {
                onEdt {
                    panel.addNotify()
                    assertTrue(panel.isPreviewAnimating)
                    platform.isPowerSaveEnabled = true
                    platform.listeners.single().powerSaveStateChanged()
                    assertFalse(panel.isPreviewAnimating)
                    platform.isPowerSaveEnabled = false
                    platform.listeners.single().powerSaveStateChanged()
                    assertTrue(panel.isPreviewAnimating)
                    assertPanelContent(panel, originalChildren, originalInsets, label, toggle)
                }

                platform.isPowerSaveEnabled = true
                platform.listeners.single().powerSaveStateChanged()
                onEdt {
                    assertFalse(panel.isPreviewAnimating)
                    assertPreview(panel, WAVEFORM_PREVIEW)
                    assertPanelContent(panel, originalChildren, originalInsets, label, toggle)
                }

                platform.isPowerSaveEnabled = false
                platform.listeners.single().powerSaveStateChanged()
                onEdt {
                    assertTrue(panel.isPreviewAnimating)
                    assertPanelContent(panel, originalChildren, originalInsets, label, toggle)
                    panel.isVisible = false
                    assertFalse(panel.isPreviewAnimating)
                }

                platform.isPowerSaveEnabled = true
                platform.listeners.single().powerSaveStateChanged()
                onEdt {
                    assertFalse(panel.isPreviewAnimating)
                    assertPreview(panel, WAVEFORM_PREVIEW)
                    platform.isPowerSaveEnabled = false
                    assertPanelContent(panel, originalChildren, originalInsets, label, toggle)
                }

                platform.listeners.single().powerSaveStateChanged()
                onEdt {
                    assertFalse(panel.isPreviewAnimating)
                    assertPanelContent(panel, originalChildren, originalInsets, label, toggle)
                    panel.isVisible = true
                    assertTrue(panel.isPreviewAnimating)
                    assertPanelContent(panel, originalChildren, originalInsets, label, toggle)
                }
            }
        }

    @Test
    fun `displayed preview stops for disabled and solid modes then restores waveform exactly`() =
        withPlatformMocks {
            val label = onEdt { JLabel("Accent") }
            val toggle = onEdt { JCheckBox("Waveform", true) }
            val panel = createPanel(label, toggle)
            val originalChildren = onEdt { panel.components.copyOf() }
            val originalInsets = onEdt { panel.insets }
            withPanel(panel) {
                onEdt {
                    panel.addNotify()
                    assertTrue(panel.isPreviewAnimating)
                    panel.updatePreview(WAVEFORM_PREVIEW.copy(visible = false))
                    assertFalse(panel.isPreviewAnimating)
                    panel.updatePreview(WAVEFORM_PREVIEW)
                    assertTrue(panel.isPreviewAnimating)
                    panel.updatePreview(WAVEFORM_PREVIEW.copy(shape = GlowShape.SOLID))
                    assertFalse(panel.isPreviewAnimating)
                    panel.updatePreview(WAVEFORM_PREVIEW)
                    assertTrue(panel.isPreviewAnimating)

                    panel.isVisible = false
                    val restingRaster = render(panel)
                    panel.updatePreview(WAVEFORM_PREVIEW.copy(visible = false))
                    assertFalse(panel.isPreviewAnimating)
                    panel.updatePreview(WAVEFORM_PREVIEW.copy(shape = GlowShape.SOLID))
                    assertFalse(panel.isPreviewAnimating)
                    panel.updatePreview(WAVEFORM_PREVIEW)
                    assertFalse(panel.isPreviewAnimating)
                    assertEquals(0, pixelDifference(restingRaster, render(panel)))
                    panel.isVisible = true
                    assertTrue(panel.isPreviewAnimating)
                    assertPreview(panel, WAVEFORM_PREVIEW)
                    assertContentEquals(originalChildren, panel.components)
                    assertEquals(originalInsets, panel.insets)
                    assertEquals("Accent", label.text)
                    assertTrue(toggle.isSelected)
                }
            }
        }

    @Test
    fun `displayed native timer advances the waveform before settings are applied`() =
        withPlatformMocks {
            val panel = createPanel(onEdt { JLabel("Live waveform") })
            val observedDifference = AtomicReference<Int>()
            val observerFailure = AtomicReference<Throwable>()
            val changed = CountDownLatch(1)
            var observer: Timer? = null
            var firstFailure: Throwable? = null
            try {
                onEdt {
                    panel.addNotify()
                    val initialRaster = render(panel)
                    observer =
                        Timer(OBSERVATION_DELAY_MS) {
                            try {
                                val difference = pixelDifference(initialRaster, render(panel))
                                if (difference >= MIN_ANIMATION_PIXEL_DIFFERENCE) {
                                    observedDifference.set(difference)
                                    changed.countDown()
                                }
                            } catch (failure: Throwable) {
                                observerFailure.set(failure)
                                changed.countDown()
                            }
                        }.apply { start() }
                }

                assertTrue(
                    changed.await(ANIMATION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "native preview timer did not change the rendered waveform " +
                        "within $ANIMATION_TIMEOUT_SECONDS seconds",
                )
                observerFailure.get()?.let { failure ->
                    throw AssertionError("waveform observer failed", failure)
                }
                assertTrue((observedDifference.get() ?: 0) >= MIN_ANIMATION_PIXEL_DIFFERENCE)
            } catch (failure: Throwable) {
                firstFailure = failure
            }
            firstFailure = attemptCleanup(firstFailure) { onEdt { observer?.stop() } }
            firstFailure = attemptCleanup(firstFailure) { removePanel(panel) }
            firstFailure?.let { failure -> throw failure }
            Unit
        }

    private fun createPanel(vararg children: Component): GlowGroupPanel =
        onEdt {
            GlowGroupPanel().apply {
                setSize(PANEL_WIDTH, PANEL_HEIGHT)
                children.forEach(::add)
                updatePreview(WAVEFORM_PREVIEW)
            }
        }

    private fun removePanel(panel: GlowGroupPanel) {
        onEdt {
            if (panel.isDisplayable) panel.removeNotify()
            assertFalse(panel.isPreviewAnimating)
        }
    }

    private fun <T> withPanel(
        panel: GlowGroupPanel,
        action: () -> T,
    ): T {
        val actionResult = AtomicReference<T>()
        var firstFailure: Throwable? = null
        try {
            actionResult.set(action())
        } catch (failure: Throwable) {
            firstFailure = failure
        }
        firstFailure = attemptCleanup(firstFailure) { removePanel(panel) }
        firstFailure?.let { failure -> throw failure }
        return actionResult.get()
    }

    private fun assertPreview(
        panel: GlowGroupPanel,
        expected: GlowPreview,
    ) {
        assertEquals(expected.shape, panel.glowShape)
        assertEquals(expected.style, panel.glowStyle)
        assertEquals(expected.intensity, panel.glowIntensity)
        assertEquals(expected.width, panel.glowWidth)
        assertEquals(expected.color, panel.glowColor)
        assertEquals(expected.visible, panel.glowVisible)
        assertEquals(expected.waveformConfig, panel.waveformConfig)
    }

    private fun assertPanelContent(
        panel: GlowGroupPanel,
        originalChildren: Array<Component>,
        originalInsets: Insets,
        label: JLabel,
        toggle: JCheckBox,
    ) {
        assertPreview(panel, WAVEFORM_PREVIEW)
        assertContentEquals(originalChildren, panel.components)
        assertEquals(originalInsets, panel.insets)
        assertEquals("Settings", label.text)
        assertTrue(toggle.isSelected)
    }

    private fun render(panel: GlowGroupPanel): BufferedImage {
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun pixelDifference(
        first: BufferedImage,
        second: BufferedImage,
    ): Int =
        (0 until first.height).sumOf { y ->
            (0 until first.width).count { x -> first.getRGB(x, y) != second.getRGB(x, y) }
        }

    private fun controlText(component: Component): String =
        when (component) {
            is JLabel -> component.text
            is JCheckBox -> component.text
            else -> fail("unexpected preview child ${component.javaClass.name}")
        }

    private fun <T> withPlatformMocks(action: (PlatformFixture) -> T): T {
        val application = mockk<Application>()
        val messageBus = mockk<MessageBus>()
        val fixture = PlatformFixture()
        val actionResult = AtomicReference<T>()
        var firstFailure: Throwable? = null
        try {
            mockkStatic(ApplicationManager::class)
            mockkStatic(PowerSaveMode::class)
            every { ApplicationManager.getApplication() } returns application
            every { application.messageBus } returns messageBus
            every { messageBus.connect() } answers {
                mockk<MessageBusConnection>().also { connection ->
                    val listener = slot<PowerSaveMode.Listener>()
                    every { connection.subscribe(PowerSaveMode.TOPIC, capture(listener)) } answers {
                        fixture.listeners += listener.captured
                    }
                    every { connection.disconnect() } returns Unit
                    fixture.connections += connection
                }
            }
            every { PowerSaveMode.isEnabled() } answers { fixture.isPowerSaveEnabled }
            actionResult.set(action(fixture))
        } catch (failure: Throwable) {
            firstFailure = failure
        }

        firstFailure = attemptCleanup(firstFailure) { onEdt {} }
        firstFailure = attemptCleanup(firstFailure) { unmockkStatic(PowerSaveMode::class) }
        firstFailure = attemptCleanup(firstFailure) { unmockkStatic(ApplicationManager::class) }
        firstFailure?.let { failure -> throw failure }
        return actionResult.get()
    }

    private fun attemptCleanup(
        firstFailure: Throwable?,
        cleanup: () -> Unit,
    ): Throwable? =
        try {
            cleanup()
            firstFailure
        } catch (cleanupFailure: Throwable) {
            firstFailure?.apply { addSuppressed(cleanupFailure) } ?: cleanupFailure
        }

    private fun <T> onEdt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        SwingUtilities.invokeAndWait {
            try {
                result.set(action())
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private class PlatformFixture {
        var isPowerSaveEnabled: Boolean = false
        val connections = mutableListOf<MessageBusConnection>()
        val listeners = mutableListOf<PowerSaveMode.Listener>()
    }

    private companion object {
        const val PANEL_WIDTH = 420
        const val PANEL_HEIGHT = 300
        const val OBSERVATION_DELAY_MS = 20
        const val ANIMATION_TIMEOUT_SECONDS = 3L
        const val MIN_ANIMATION_PIXEL_DIFFERENCE = 20
        val WAVEFORM_PREVIEW =
            GlowPreview(
                shape = GlowShape.WAVEFORM,
                style = GlowStyle.SHARP_NEON,
                intensity = 73,
                width = 11,
                color = Color(0x5CCFE6),
                visible = true,
                waveformConfig = WaveformConfig(amplitude = 16, intensity = 91, loopSeconds = 2f),
            )
    }
}
