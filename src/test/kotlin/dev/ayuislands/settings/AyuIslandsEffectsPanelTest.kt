package dev.ayuislands.settings

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.util.ui.JBUI
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.DEFAULT_TRACE_LENGTH
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformDirection
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AyuIslandsEffectsPanelTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings

    @BeforeTest
    fun setUp() {
        state =
            AyuIslandsSettings().state.apply {
                glowEnabled = true
                glowPreset = GlowPreset.WHISPER.name
            }
        settings = mockk(relaxed = true)
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManager>(relaxed = true)
        val lafManagerMock = mockk<LafManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { appMock.getService(LafManager::class.java) } returns lafManagerMock
        every { actionManagerMock.getAction(any()) } returns null
        // Placement preview's default sink iterates open projects; headless
        // tests have none, which keeps the un-injected path a no-op.
        val projectManagerMock = mockk<ProjectManager>(relaxed = true)
        every { appMock.getService(ProjectManager::class.java) } returns projectManagerMock
        every { projectManagerMock.openProjects } returns emptyArray()

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUiMock = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { appMock.getService(experimentalUiClass) } returns experimentalUiMock
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `unlicensed glow preset preview cannot mutate pending state`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        assertFalse(effectsPanel.isModified(), "locked Glow preview must start clean")
        presetSegmented(effectsPanel).selectedItem = GlowPreset.CYBERPUNK

        assertFalse(
            effectsPanel.isModified(),
            "locked Glow preset preview must ignore selection changes instead of dirtying Settings",
        )
    }

    @Test
    fun `unlicensed glow keeps custom controls and targets visible but locked`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        state.glowEnabled = false
        state.glowPreset = GlowPreset.WHISPER.name
        val effectsPanel = AyuIslandsEffectsPanel()

        val dialogPanel = buildDialogPanel(effectsPanel)
        val styleCombo = field<JComboBox<*>>(effectsPanel, "styleCombo")
        val animationCombo = field<JComboBox<*>>(effectsPanel, "animationCombo")
        val intensitySlider = field<JSlider>(effectsPanel, "intensitySlider")
        val widthSlider = field<JSlider>(effectsPanel, "widthSlider")
        val targetCheckboxes = islandCheckboxes(effectsPanel).values.toList()
        val targetsLabel = descendants(dialogPanel, JLabel::class.java).first { it.text == "Targets" }

        assertTrue(
            styleCombo.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Glow preview must show the style selector even when the preset is not Custom",
        )
        assertFalse(styleCombo.isEnabled, "Locked Glow style selector must not be mutable")
        assertTrue(
            intensitySlider.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Glow preview must show the intensity slider even when the preset is not Custom",
        )
        assertFalse(intensitySlider.isEnabled, "Locked Glow intensity slider must not be mutable")
        assertTrue(
            widthSlider.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Glow preview must show the width slider even when the preset is not Custom",
        )
        assertFalse(widthSlider.isEnabled, "Locked Glow width slider must not be mutable")
        assertTrue(
            animationCombo.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Glow preview must show animation controls even when the preset is not Custom",
        )
        assertFalse(animationCombo.isEnabled, "Locked Glow animation controls must not be mutable")
        assertTrue(
            targetsLabel.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Glow preview must show the Targets group even when the preset is not Custom",
        )
        assertTrue(targetCheckboxes.isNotEmpty(), "Locked Glow preview must render target checkboxes")
        assertTrue(
            targetCheckboxes.all { !it.isEnabled },
            "Locked Glow target controls must not be mutable",
        )

        intensitySlider.value += 1

        assertFalse(
            effectsPanel.isModified(),
            "Programmatic changes to locked Glow preview controls must not dirty Settings",
        )
    }

    @Test
    fun `licensed placement selection persists through apply into state`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        placementSegmented(effectsPanel, "editorPlacement").selectedItem = GlowPlacement.SIDE_EDGES
        placementSegmented(effectsPanel, "toolWindowPlacement").selectedItem = GlowPlacement.SIDE_EDGES

        assertTrue(effectsPanel.isModified(), "placement change must dirty the panel")
        effectsPanel.apply()

        assertEquals(GlowPlacement.SIDE_EDGES.name, state.glowEditorPlacement)
        assertEquals(GlowPlacement.SIDE_EDGES.name, state.glowToolWindowPlacement)
        assertFalse(effectsPanel.isModified(), "apply must converge stored onto pending")
    }

    @Test
    fun `placement selection pushes a live preview and reset reverts it`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        val previews = mutableListOf<Pair<GlowPlacement, GlowPlacement>>()
        var reverted = 0
        effectsPanel.placementPreview = { editor, toolWindow -> previews.add(editor to toolWindow) }
        effectsPanel.placementPreviewRevert = { reverted++ }
        buildDialogPanel(effectsPanel)
        previews.clear() // building the panel replays stored selections; only user clicks matter

        placementSegmented(effectsPanel, "editorPlacement").selectedItem = GlowPlacement.SIDE_EDGES

        assertEquals(
            GlowPlacement.SIDE_EDGES to GlowPlacement.ISLAND,
            previews.lastOrNull(),
            "selection must push the pending placements onto live overlays before Apply",
        )

        effectsPanel.reset()
        assertTrue(reverted > 0, "reset must revert the live preview to stored state")
    }

    @Test
    fun `disposing the panel reverts an unapplied preview`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        var reverted = 0
        effectsPanel.placementPreviewRevert = { reverted++ }
        buildDialogPanel(effectsPanel)

        effectsPanel.dispose()

        assertTrue(reverted > 0, "closing Settings must not leave a previewed placement on screen")
    }

    @Test
    fun `unlicensed glow placement preview cannot mutate pending state`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        placementSegmented(effectsPanel, "editorPlacement").selectedItem = GlowPlacement.SIDE_EDGES

        assertFalse(
            effectsPanel.isModified(),
            "locked placement preview must ignore selection changes instead of dirtying Settings",
        )
    }

    @Test
    fun `reset restores placement selection from stored state`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        placementSegmented(effectsPanel, "editorPlacement").selectedItem = GlowPlacement.SIDE_EDGES
        assertTrue(effectsPanel.isModified(), "precondition: selection dirtied the panel")

        effectsPanel.reset()

        assertFalse(effectsPanel.isModified(), "reset must drop the pending placement change")
        assertEquals(
            GlowPlacement.ISLAND,
            placementSegmented(effectsPanel, "editorPlacement").selectedItem,
            "reset must restore the stored placement selection",
        )
    }

    @Test
    fun `shape switch shows waveform controls and restores dormant solid choices`() {
        state.glowPreset = GlowPreset.CUSTOM.name
        state.glowStyle = GlowStyle.SHARP_NEON.name
        state.glowEditorPlacement = GlowPlacement.SIDE_EDGES.name
        state.glowToolWindowPlacement = GlowPlacement.SIDE_EDGES.name
        state.setIntensityForStyle(GlowStyle.SHARP_NEON, 73)
        val effectsPanel = AyuIslandsEffectsPanel()
        val dialogPanel = buildDialogPanel(effectsPanel)
        val shape = waveformField<JComboBox<*>>(effectsPanel, "shapeCombo")
        val style = field<JComboBox<*>>(effectsPanel, "styleCombo")
        val solidIntensity = field<JSlider>(effectsPanel, "intensitySlider")
        val direction = waveformField<JComboBox<*>>(effectsPanel, "directionCombo")
        val baseline = waveformField<JComboBox<*>>(effectsPanel, "baselineCombo")
        val density = densityControl(dialogPanel)
        val traceLength = waveformField<JSlider>(effectsPanel, "traceLengthSlider")
        val amplitude = waveformField<JSlider>(effectsPanel, "amplitudeSlider")
        val loopDuration = waveformField<JSlider>(effectsPanel, "loopSlider")
        val editorPlacement = placementSegmented(effectsPanel, "editorPlacement")
        val toolWindowPlacement = placementSegmented(effectsPanel, "toolWindowPlacement")
        val editorPlacementLabel = descendants(dialogPanel, JLabel::class.java).first { it.text == "Editor placement" }

        shape.selectedItem = GlowShape.WAVEFORM.displayName

        assertTrue(direction.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(baseline.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(density.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(traceLength.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(amplitude.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(loopDuration.isEffectivelyVisibleWithin(dialogPanel))
        assertFalse(style.isEffectivelyVisibleWithin(dialogPanel))
        assertFalse(editorPlacementLabel.isEffectivelyVisibleWithin(dialogPanel))

        shape.selectedItem = GlowShape.SOLID.displayName

        assertTrue(style.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(editorPlacementLabel.isEffectivelyVisibleWithin(dialogPanel))
        assertEquals(GlowStyle.SHARP_NEON.displayName, style.selectedItem)
        assertEquals(73, solidIntensity.value)
        assertEquals(GlowPreset.CUSTOM, presetSegmented(effectsPanel).selectedItem)
        assertEquals(GlowPlacement.SIDE_EDGES, editorPlacement.selectedItem)
        assertEquals(GlowPlacement.SIDE_EDGES, toolWindowPlacement.selectedItem)
    }

    @Test
    fun `waveform sliders align and mark real values`() {
        state.glowShape = GlowShape.WAVEFORM.name
        val effectsPanel = AyuIslandsEffectsPanel()
        val dialogPanel = buildDialogPanel(effectsPanel)
        dialogPanel.setSize(TEST_PANEL_WIDTH, dialogPanel.preferredSize.height)
        layoutRecursively(dialogPanel)

        val enumBounds =
            listOf("shapeCombo", "directionCombo", "baselineCombo")
                .map { waveformField<JComboBox<*>>(effectsPanel, it).boundsIn(dialogPanel) }
        assertEquals(1, enumBounds.map(Rectangle::x).distinct().size)
        assertEquals(1, enumBounds.map(Rectangle::width).distinct().size)

        val sliderRows =
            listOf(
                "Loop duration" to "loopSlider",
                "Spike density" to "densitySlider",
                "Trace length (px)" to "traceLengthSlider",
                "Amplitude (px)" to "amplitudeSlider",
                "Intensity" to "intensitySlider",
            )
        val sliders = sliderRows.map { (_, fieldName) -> waveformField<JSlider>(effectsPanel, fieldName) }
        val sliderBounds = sliders.map { it.boundsIn(dialogPanel) }
        assertEquals(1, sliderBounds.map(Rectangle::x).distinct().size)
        assertEquals(1, sliderBounds.map(Rectangle::width).distinct().size, "Slider bounds: $sliderBounds")
        sliderRows.zip(sliders).forEach { (row, slider) ->
            val rowLabel = descendants(dialogPanel, JLabel::class.java).first { it.text == row.first }
            assertSame(slider, rowLabel.labelFor)
        }

        val tickScales =
            listOf(
                TickScale((20..400 step 20).toList(), (100..400 step 100).toSet()),
                TickScale((1..4).toList(), (1..4).toSet()),
                TickScale((120..800 step 40).toList(), (200..800 step 200).toSet()),
                TickScale(
                    buildList {
                        add(1)
                        addAll(5..40 step 5)
                    },
                    setOf(1, 10, 20, 30, 40),
                ),
                TickScale((0..200 step 10).toList(), (0..200 step 50).toSet()),
            )
        val tickStrips =
            sliders.map { slider ->
                slider.parent.components
                    .filterIsInstance<SliderTickStrip>()
                    .single()
            }
        assertTrue(tickStrips.all { it.isEffectivelyVisibleWithin(dialogPanel) && it.width > 0 && it.height > 0 })
        sliders.zip(tickStrips).zip(tickScales).forEach { (control, scale) ->
            val (slider, tickStrip) = control
            val sliderLabel = sliderRows[sliders.indexOf(slider)].first
            assertEquals(expectedTicks(slider, scale), tickStrip.paintedTicks(), "Unexpected ticks for $sliderLabel")
        }

        val glowPanel = field<GlowGroupPanel>(effectsPanel, "glowGroupPanel")
        val readouts =
            listOf("loopLabel", "traceLengthLabel", "amplitudeLabel", "intensityLabel")
                .map { waveformField<JLabel>(effectsPanel, it).boundsIn(glowPanel) }
        val innerRightEdge = glowPanel.width - glowPanel.insets.right
        assertTrue(
            readouts.all { innerRightEdge - (it.x + it.width) >= JBUI.scale(READOUT_INSET) },
            "Waveform readouts must keep a visible right inset: $readouts",
        )
    }

    @Test
    fun `RTL amplitude slider follows visual arrow direction`() {
        state.glowShape = GlowShape.WAVEFORM.name
        val effectsPanel = AyuIslandsEffectsPanel()
        val dialogPanel = buildDialogPanel(effectsPanel)
        dialogPanel.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
        val amplitude = waveformField<JSlider>(effectsPanel, "amplitudeSlider")
        amplitude.value = 25

        performFocusedAction(amplitude, KeyEvent.VK_LEFT)
        assertEquals(30, amplitude.value)

        performFocusedAction(amplitude, KeyEvent.VK_RIGHT)
        assertEquals(25, amplitude.value)
    }

    @Test
    fun `RTL waveform tick strip mirrors the slider scale`() {
        state.glowShape = GlowShape.WAVEFORM.name
        val effectsPanel = AyuIslandsEffectsPanel()
        val dialogPanel = buildDialogPanel(effectsPanel)
        dialogPanel.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
        val amplitude = waveformField<JSlider>(effectsPanel, "amplitudeSlider")
        val tickStrip =
            amplitude.parent.components
                .filterIsInstance<SliderTickStrip>()
                .single()
        val scale =
            TickScale(
                buildList {
                    add(1)
                    addAll(5..40 step 5)
                },
                setOf(1, 10, 20, 30, 40),
            )
        val mirroredTicks =
            expectedTicks(amplitude, scale)
                .map { it.copy(x = TICK_STRIP_WIDTH - 1 - it.x) }
                .sortedBy(PaintedTick::x)

        assertEquals(mirroredTicks, tickStrip.paintedTicks())
    }

    @Test
    fun `waveform preview keeps numeric readouts clear throughout the loop`() {
        state.glowShape = GlowShape.WAVEFORM.name
        val effectsPanel = AyuIslandsEffectsPanel()
        val dialogPanel = buildDialogPanel(effectsPanel)
        dialogPanel.setSize(TEST_PANEL_WIDTH, dialogPanel.preferredSize.height)
        layoutRecursively(dialogPanel)
        val glowPanel = field<GlowGroupPanel>(effectsPanel, "glowGroupPanel")
        val readouts =
            listOf("loopLabel", "densityLabel", "traceLengthLabel", "amplitudeLabel", "intensityLabel")
                .associateWith { fieldName -> waveformField<JLabel>(effectsPanel, fieldName).boundsIn(glowPanel) }
        val preview =
            GlowPreview(
                shape = GlowShape.WAVEFORM,
                style = GlowStyle.SHARP_NEON,
                intensity = 0,
                width = 4,
                color = Color(0xFF6B6B),
                visible = true,
                waveformConfig =
                    WaveformConfig(
                        baseline = WaveformBaseline.CENTERED,
                        traceDensity = 4,
                        amplitude = 24,
                        intensity = 100,
                        loopSeconds = 20f,
                    ),
            )
        glowPanel.updatePreview(preview.copy(visible = false))
        val withoutWaveform = render(glowPanel)
        glowPanel.updatePreview(preview)

        val overlap =
            (0L..20_000L step 250L).firstNotNullOfOrNull { nowMs ->
                glowPanel.advanceWaveformPreview(nowMs)
                val withWaveform = render(glowPanel)
                readouts.entries
                    .firstOrNull { (_, bounds) ->
                        pixelDifference(withoutWaveform, withWaveform, bounds) > 0
                    }?.let { (fieldName, _) -> fieldName to nowMs }
            }

        assertEquals(null, overlap, "Waveform preview crossed a numeric readout at $overlap")
    }

    @Test
    fun `amplitude snaps to the nearest displayed tick`() {
        state.glowShape = GlowShape.WAVEFORM.name
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)
        val amplitude = waveformField<JSlider>(effectsPanel, "amplitudeSlider")

        amplitude.valueIsAdjusting = true
        amplitude.value = 23
        assertEquals(23, amplitude.value)
        assertTrue(effectsPanel.isModified(), "dragging must update the live waveform preview")
        amplitude.valueIsAdjusting = false
        effectsPanel.apply()

        assertEquals(25, amplitude.value)
        assertEquals(25, state.waveformAmplitude)
        assertFalse(effectsPanel.isModified())
    }

    @Test
    fun `amplitude snapping resolves equal distance toward the lower tick`() {
        state.glowShape = GlowShape.WAVEFORM.name
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)
        val amplitude = waveformField<JSlider>(effectsPanel, "amplitudeSlider")

        amplitude.valueIsAdjusting = true
        amplitude.value = 3
        amplitude.valueIsAdjusting = false

        assertEquals(1, amplitude.value)
    }

    @Test
    fun `amplitude keyboard navigation moves between displayed ticks`() {
        state.glowShape = GlowShape.WAVEFORM.name
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)
        val amplitude = waveformField<JSlider>(effectsPanel, "amplitudeSlider")

        performFocusedAction(amplitude, KeyEvent.VK_LEFT)
        assertEquals(20, amplitude.value)

        performFocusedAction(amplitude, KeyEvent.VK_RIGHT)
        assertEquals(25, amplitude.value)

        performFocusedAction(amplitude, KeyEvent.VK_DOWN)
        assertEquals(20, amplitude.value)

        performFocusedAction(amplitude, KeyEvent.VK_UP)
        assertEquals(25, amplitude.value)

        amplitude.value = amplitude.minimum
        performFocusedAction(amplitude, KeyEvent.VK_LEFT)
        assertEquals(amplitude.minimum, amplitude.value)
        performFocusedAction(amplitude, KeyEvent.VK_DOWN)
        assertEquals(amplitude.minimum, amplitude.value)

        amplitude.value = amplitude.maximum
        performFocusedAction(amplitude, KeyEvent.VK_RIGHT)
        assertEquals(amplitude.maximum, amplitude.value)
        performFocusedAction(amplitude, KeyEvent.VK_UP)
        assertEquals(amplitude.maximum, amplitude.value)
        assertTrue(effectsPanel.isModified())
    }

    @Test
    fun `first waveform selection uses the calibrated ECG profile`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        waveformField<JComboBox<*>>(effectsPanel, "shapeCombo").selectedItem = GlowShape.WAVEFORM.displayName

        assertEquals(
            WaveformDirection.CLOCKWISE.displayName,
            waveformField<JComboBox<*>>(effectsPanel, "directionCombo").selectedItem,
        )
        assertEquals(
            WaveformBaseline.CENTERED.displayName,
            waveformField<JComboBox<*>>(effectsPanel, "baselineCombo").selectedItem,
        )
        assertEquals(200, waveformField<JSlider>(effectsPanel, "loopSlider").value)
        assertEquals(1, waveformField<JSlider>(effectsPanel, "densitySlider").value)
        assertEquals(199, waveformField<JSlider>(effectsPanel, "traceLengthSlider").value)

        val amplitude = waveformField<JSlider>(effectsPanel, "amplitudeSlider")
        val intensity = waveformField<JSlider>(effectsPanel, "intensitySlider")
        assertEquals(24, amplitude.value)
        assertEquals(1, amplitude.minimum)
        assertEquals(40, amplitude.maximum)
        assertEquals(100, intensity.value)
        assertEquals(intensity.minimum + intensity.maximum, intensity.value * 2)
    }

    @Test
    fun `waveform selection preserves dormant custom ECG choices`() {
        state.waveformDirection = WaveformDirection.COUNTER_CLOCKWISE.name
        state.waveformBaseline = WaveformBaseline.OUTSIDE.name
        state.waveformLoopSeconds = 3.7f
        state.waveformTraceDensity = 4
        state.waveformTraceLength = 640
        state.waveformAmplitude = 36
        state.waveformIntensity = 150
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        waveformField<JComboBox<*>>(effectsPanel, "shapeCombo").selectedItem = GlowShape.WAVEFORM.displayName
        effectsPanel.apply()

        assertEquals(WaveformDirection.COUNTER_CLOCKWISE.name, state.waveformDirection)
        assertEquals(WaveformBaseline.OUTSIDE.name, state.waveformBaseline)
        assertEquals(3.7f, state.waveformLoopSeconds)
        assertEquals(4, state.waveformTraceDensity)
        assertEquals(640, state.waveformTraceLength)
        assertEquals(36, state.waveformAmplitude)
        assertEquals(150, state.waveformIntensity)
    }

    @Test
    fun `glow preview content stays transparent over the painted border`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)
        val glowPanel = field<GlowGroupPanel>(effectsPanel, "glowGroupPanel")
        val content = glowPanel.components.filterIsInstance<JComponent>()

        assertTrue(content.isNotEmpty())
        assertTrue(content.all { !it.isOpaque })
    }

    @Test
    fun `default reset covers solid and waveform settings`() {
        val reset =
            GlowSettings(
                shape = GlowShape.WAVEFORM,
                preset = GlowPreset.CUSTOM,
                waveformDirection = WaveformDirection.COUNTER_CLOCKWISE,
                waveformBaseline = WaveformBaseline.CENTERED,
                waveformTraceDensity = 4,
                waveformTraceLength = 640,
                waveformAmplitude = 16,
                waveformIntensity = 12,
                waveformLoopSeconds = 5.5f,
            ).withDefaults()

        assertEquals(GlowShape.SOLID, reset.shape)
        assertEquals(GlowPreset.WHISPER, reset.preset)
        assertEquals(WaveformDirection.CLOCKWISE, reset.waveformDirection)
        assertEquals(WaveformBaseline.CENTERED, reset.waveformBaseline)
        assertEquals(1, reset.waveformTraceDensity)
        assertEquals(DEFAULT_TRACE_LENGTH, reset.waveformTraceLength)
        assertEquals(24, reset.waveformAmplitude)
        assertEquals(100, reset.waveformIntensity)
        assertEquals(20f, reset.waveformLoopSeconds)
    }

    @Test
    fun `waveform settings apply without rewriting dormant solid preferences`() {
        state.glowPreset = GlowPreset.CUSTOM.name
        state.glowStyle = GlowStyle.GRADIENT.name
        state.setIntensityForStyle(GlowStyle.GRADIENT, 47)
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        waveformField<JComboBox<*>>(effectsPanel, "shapeCombo").selectedItem = GlowShape.WAVEFORM.displayName
        waveformField<JComboBox<*>>(effectsPanel, "directionCombo").selectedItem =
            WaveformDirection.COUNTER_CLOCKWISE.displayName
        waveformField<JSlider>(effectsPanel, "amplitudeSlider").value = 16
        waveformField<JSlider>(effectsPanel, "intensitySlider").value = 88

        effectsPanel.apply()

        assertEquals(GlowShape.WAVEFORM.name, state.glowShape)
        assertEquals(WaveformDirection.COUNTER_CLOCKWISE.name, state.waveformDirection)
        assertEquals(15, state.waveformAmplitude)
        assertEquals(88, state.waveformIntensity)
        assertEquals(GlowPreset.CUSTOM.name, state.glowPreset)
        assertEquals(GlowStyle.GRADIENT.name, state.glowStyle)
        assertEquals(47, state.getIntensityForStyle(GlowStyle.GRADIENT))
        assertFalse(effectsPanel.isModified())
    }

    @Test
    fun `applying another glow setting preserves legacy waveform values`() {
        state.waveformAmplitude = 6
        state.waveformIntensity = -5
        state.waveformLoopSeconds = 99f
        state.waveformTraceDensity = 99
        state.waveformTraceLength = 9_999
        state.waveformBaseline = WaveformBaseline.CENTERED.name
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        assertFalse(effectsPanel.isModified())
        field<JSlider>(effectsPanel, "intensitySlider").value = 42
        effectsPanel.apply()

        assertEquals(6, state.waveformAmplitude)
        assertEquals(-5, state.waveformIntensity)
        assertEquals(99f, state.waveformLoopSeconds)
        assertEquals(99, state.waveformTraceDensity)
        assertEquals(9_999, state.waveformTraceLength)
        assertEquals(WaveformBaseline.CENTERED.name, state.waveformBaseline)
        assertEquals(6, waveformField<JSlider>(effectsPanel, "amplitudeSlider").value)
        assertEquals(0, waveformField<JSlider>(effectsPanel, "intensitySlider").value)
        assertEquals(400, waveformField<JSlider>(effectsPanel, "loopSlider").value)
        assertEquals(4, waveformField<JSlider>(effectsPanel, "densitySlider").value)
        assertEquals(800, waveformField<JSlider>(effectsPanel, "traceLengthSlider").value)
        assertEquals(
            WaveformBaseline.CENTERED.displayName,
            waveformField<JComboBox<*>>(effectsPanel, "baselineCombo").selectedItem,
        )
    }

    @Test
    fun `perimeter loop duration applies in tenths of a second`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        waveformField<JComboBox<*>>(effectsPanel, "shapeCombo").selectedItem = GlowShape.WAVEFORM.displayName
        val loopSlider = waveformField<JSlider>(effectsPanel, "loopSlider")
        loopSlider.value = 37

        effectsPanel.apply()

        assertEquals(3.7f, state.waveformLoopSeconds)
        assertFalse(effectsPanel.isModified())
    }

    @Test
    fun `reset restores the stored waveform geometry`() {
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformTraceDensity = 2
        state.waveformTraceLength = 320
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)
        val density = waveformField<JSlider>(effectsPanel, "densitySlider")
        val traceLength = waveformField<JSlider>(effectsPanel, "traceLengthSlider")

        density.value = 4
        traceLength.value = 640
        assertTrue(effectsPanel.isModified())

        effectsPanel.reset()

        assertEquals(2, density.value)
        assertEquals(320, traceLength.value)
        assertFalse(effectsPanel.isModified())
    }

    @Test
    fun `waveform geometry controls apply together`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        waveformField<JComboBox<*>>(effectsPanel, "shapeCombo").selectedItem = GlowShape.WAVEFORM.displayName
        waveformField<JSlider>(effectsPanel, "densitySlider").value = 4
        waveformField<JSlider>(effectsPanel, "traceLengthSlider").value = 640
        waveformField<JComboBox<*>>(effectsPanel, "baselineCombo").selectedItem =
            WaveformBaseline.CENTERED.displayName

        effectsPanel.apply()

        assertEquals(4, state.waveformTraceDensity)
        assertEquals(640, state.waveformTraceLength)
        assertEquals(WaveformBaseline.CENTERED.name, state.waveformBaseline)
        assertFalse(effectsPanel.isModified())
    }

    @Test
    fun `shape-only apply preserves noncanonical dormant preset fields`() {
        state.glowPreset = GlowPreset.WHISPER.name
        state.glowStyle = GlowStyle.GRADIENT.name
        state.setIntensityForStyle(GlowStyle.GRADIENT, 47)
        state.setWidthForStyle(GlowStyle.GRADIENT, 13)
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        waveformField<JComboBox<*>>(effectsPanel, "shapeCombo").selectedItem = GlowShape.WAVEFORM.displayName
        effectsPanel.apply()

        assertEquals(GlowPreset.WHISPER.name, state.glowPreset)
        assertEquals(GlowStyle.GRADIENT.name, state.glowStyle)
        assertEquals(47, state.getIntensityForStyle(GlowStyle.GRADIENT))
        assertEquals(13, state.getWidthForStyle(GlowStyle.GRADIENT))
    }

    @Test
    fun `locked waveform layout stays visible disabled and clean`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        state.glowEnabled = false
        state.glowShape = GlowShape.WAVEFORM.name
        val effectsPanel = AyuIslandsEffectsPanel()
        val dialogPanel = buildDialogPanel(effectsPanel)
        val shape = waveformField<JComboBox<*>>(effectsPanel, "shapeCombo")
        val direction = waveformField<JComboBox<*>>(effectsPanel, "directionCombo")
        val baseline = waveformField<JComboBox<*>>(effectsPanel, "baselineCombo")
        val density = waveformField<JSlider>(effectsPanel, "densitySlider")
        val densityControl = densityControl(dialogPanel)
        val traceLength = waveformField<JSlider>(effectsPanel, "traceLengthSlider")
        val amplitude = waveformField<JSlider>(effectsPanel, "amplitudeSlider")
        val targetCheckboxes = islandCheckboxes(effectsPanel).values.toList()

        assertTrue(direction.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(baseline.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(densityControl.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(traceLength.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(amplitude.isEffectivelyVisibleWithin(dialogPanel))
        assertFalse(shape.isEnabled)
        assertFalse(direction.isEnabled)
        assertFalse(baseline.isEnabled)
        assertFalse(densityControl.isEnabled)
        assertFalse(traceLength.isEnabled)
        assertFalse(amplitude.isEnabled)
        assertTrue(targetCheckboxes.isNotEmpty())
        assertTrue(targetCheckboxes.all { !it.isEnabled })

        shape.selectedItem = GlowShape.SOLID.displayName
        baseline.selectedItem = WaveformBaseline.CENTERED.displayName
        density.value = 4
        traceLength.value = 640
        amplitude.value = 12
        assertFalse(effectsPanel.isModified(), "locked waveform preview cannot dirty pending settings")
    }

    private fun buildDialogPanel(panel: AyuIslandsEffectsPanel) =
        com.intellij.ui.dsl.builder
            .panel {
                panel.buildGlowPanel(this@panel)
            }

    @Suppress("UNCHECKED_CAST")
    private fun presetSegmented(panel: AyuIslandsEffectsPanel): SegmentedButton<GlowPreset> {
        val field = AyuIslandsEffectsPanel::class.java.getDeclaredField("presetSegmentedButton")
        field.isAccessible = true
        return field.get(panel) as? SegmentedButton<GlowPreset>
            ?: error("Glow preset segmented button must be created")
    }

    @Suppress("UNCHECKED_CAST")
    private fun placementSegmented(
        panel: AyuIslandsEffectsPanel,
        fieldName: String,
    ): SegmentedButton<GlowPlacement> {
        val field = AyuIslandsEffectsPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(panel) as? SegmentedButton<GlowPlacement>
            ?: error("Glow placement segmented button '$fieldName' must be created")
    }

    @Suppress("UNCHECKED_CAST")
    private fun islandCheckboxes(panel: AyuIslandsEffectsPanel): Map<String, JCheckBox> {
        val field = AyuIslandsEffectsPanel::class.java.getDeclaredField("islandCheckboxes")
        field.isAccessible = true
        return field.get(panel) as? Map<String, JCheckBox>
            ?: error("Glow target checkboxes must be created")
    }

    private fun <T : Component> field(
        panel: AyuIslandsEffectsPanel,
        fieldName: String,
    ): T {
        val field = AyuIslandsEffectsPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(panel) as? T ?: error("$fieldName must be created")
    }

    private fun <T : Component> waveformField(
        panel: AyuIslandsEffectsPanel,
        fieldName: String,
    ): T = waveformModelField(panel, fieldName)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> waveformModelField(
        panel: AyuIslandsEffectsPanel,
        fieldName: String,
    ): T {
        val controlsField = AyuIslandsEffectsPanel::class.java.getDeclaredField("waveformControls")
        controlsField.isAccessible = true
        val controls = controlsField.get(panel) ?: error("waveformControls must be created")
        val field = controls.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(controls) as? T ?: error("$fieldName must be created")
    }

    private fun densityControl(dialogPanel: Container): Component =
        descendants(dialogPanel, JLabel::class.java)
            .first { it.text == "Spike density" }
            .labelFor
            ?: error("Spike density control must be linked to its row label")

    private fun performFocusedAction(
        component: JComponent,
        keyCode: Int,
    ) {
        val keyStroke = KeyStroke.getKeyStroke(keyCode, 0)
        val actionKey = component.getInputMap(JComponent.WHEN_FOCUSED).get(keyStroke)
        val action = component.actionMap[actionKey] ?: error("No focused action for $keyStroke")
        action.actionPerformed(ActionEvent(component, ActionEvent.ACTION_PERFORMED, actionKey.toString()))
    }

    private fun <T : Component> descendants(
        container: Container,
        type: Class<T>,
    ): List<T> =
        buildList {
            fun visit(component: Component) {
                if (type.isInstance(component)) add(type.cast(component))
                if (component is Container) {
                    component.components.forEach(::visit)
                }
            }
            visit(container)
        }

    private fun layoutRecursively(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutRecursively)
    }

    private fun Component.boundsIn(root: Container): Rectangle =
        SwingUtilities.convertRectangle(requireNotNull(parent), bounds, root)

    private fun SliderTickStrip.paintedTicks(): List<PaintedTick> {
        val image = BufferedImage(TICK_STRIP_WIDTH, preferredSize.height, BufferedImage.TYPE_INT_ARGB)
        setSize(image.width, image.height)
        val graphics = image.createGraphics()
        try {
            paint(graphics)
        } finally {
            graphics.dispose()
        }
        val columnHeights =
            (0 until image.width).map { x ->
                (0 until image.height).count { y -> image.getRGB(x, y).ushr(24) != 0 }
            }
        return buildList {
            var start = -1
            var currentHeight = 0
            for ((x, height) in columnHeights.withIndex()) {
                if (height > 0) {
                    if (start < 0) start = x
                    currentHeight = maxOf(currentHeight, height)
                } else if (start >= 0) {
                    add(PaintedTick((start + x - 1) / 2, currentHeight))
                    start = -1
                    currentHeight = 0
                }
            }
            if (start >= 0) add(PaintedTick((start + columnHeights.lastIndex) / 2, currentHeight))
        }
    }

    private fun render(component: JComponent): BufferedImage {
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun pixelDifference(
        first: BufferedImage,
        second: BufferedImage,
        bounds: Rectangle,
    ): Int =
        (bounds.y until bounds.y + bounds.height).sumOf { y ->
            (bounds.x until bounds.x + bounds.width).count { x -> first.getRGB(x, y) != second.getRGB(x, y) }
        }

    private fun expectedTicks(
        slider: JSlider,
        scale: TickScale,
    ): List<PaintedTick> {
        val trackStart = JBUI.scale(TICK_TRACK_INSET)
        val trackWidth = TICK_STRIP_WIDTH - trackStart * 2 - 1
        val sliderSpan = slider.maximum - slider.minimum
        return scale.values.map { value ->
            val fraction = (value - slider.minimum).toFloat() / sliderSpan
            val height = if (value in scale.majorValues) MAJOR_TICK_HEIGHT else MINOR_TICK_HEIGHT
            PaintedTick(trackStart + (trackWidth * fraction).roundToInt(), JBUI.scale(height))
        }
    }

    private fun Component.isEffectivelyVisibleWithin(root: Component): Boolean {
        var current: Component? = this
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return current === root && root.isVisible
    }

    private companion object {
        const val MAJOR_TICK_HEIGHT = 7
        const val MINOR_TICK_HEIGHT = 4
        const val READOUT_INSET = 12
        const val TEST_PANEL_WIDTH = 800
        const val TICK_TRACK_INSET = 7
        const val TICK_STRIP_WIDTH = 320
    }

    private data class TickScale(
        val values: List<Int>,
        val majorValues: Set<Int>,
    )

    private data class PaintedTick(
        val x: Int,
        val height: Int,
    )
}
