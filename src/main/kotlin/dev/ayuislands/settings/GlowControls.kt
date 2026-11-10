package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import java.awt.BorderLayout
import javax.swing.JCheckBox
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider

/** Shared controls and local contour; native services and persistence belong to the owning adapter. */
internal class GlowControls(
    private val onEvent: (GlowPanelEvent) -> Unit,
) : JPanel(BorderLayout()) {
    private var rendered: GlowPanelView? = null
    private var isRendering = false
    private val visibility = GlowVisibility()
    private val editing = AtomicBooleanProperty(false)
    private val featureEditing = AtomicBooleanProperty(false)
    private val licensed = AtomicBooleanProperty(false)
    private val locked = AtomicBooleanProperty(false)
    private val preset = AtomicProperty(GlowPreset.WHISPER)
    private val editor = AtomicProperty(GlowPlacement.ISLAND)
    private val toolWindow = AtomicProperty(GlowPlacement.ISLAND)
    private val masterToggle = JCheckBox("Enable glow")
    private val glowGroupPanel = GlowGroupPanel()
    private val badges = mutableMapOf<String, Cell<JLabel>>()
    private val islandCheckboxes = mutableMapOf<String, JCheckBox>()
    private var presetSegmentedButton: SegmentedButton<GlowPreset>? = null
    private var editorPlacement: SegmentedButton<GlowPlacement>? = null
    private var toolWindowPlacement: SegmentedButton<GlowPlacement>? = null
    private val styleCombo = ComboBox(GlowStyle.entries.map { it.displayName }.toTypedArray())
    private val animationCombo = ComboBox(GlowAnimation.entries.map { it.displayName }.toTypedArray())
    private val intensitySlider = JSlider(0, MAX_INTENSITY, GlowStyle.SOFT.defaultIntensity)
    private val widthSlider = JSlider(MIN_WIDTH, MAX_WIDTH, GlowStyle.SOFT.defaultWidth)
    private val intensityValueLabel = JLabel()
    private val widthValueLabel = JLabel()
    private var animationDescriptionLabel: JEditorPane? = null
    private val premiumGate = PremiumFeatureGate("Glow", GLOW_DESCRIPTION, "Unlock glow effects", isUnlocked = true)
    private val waveformControls =
        WaveformSettingsControls(
            GlowSettings().waveformValue(),
            premiumGate,
            WaveformControlVisibility(visibility.waveform),
            ::emit,
        )

    init {
        preset.afterChange { emit(GlowPanelEvent.Preset(it)) }
        editor.afterChange { emit(GlowPanelEvent.EditorPlacement(it)) }
        toolWindow.afterChange { emit(GlowPanelEvent.ToolWindowPlacement(it)) }
        masterToggle.addActionListener { emit(GlowPanelEvent.Enable(masterToggle.isSelected)) }
        styleCombo.addActionListener {
            GlowStyle.entries.firstOrNull { it.displayName == styleCombo.selectedItem }?.let {
                emit(GlowPanelEvent.Style(it))
            }
        }
        animationCombo.addActionListener {
            GlowAnimation.entries.firstOrNull { it.displayName == animationCombo.selectedItem }?.let {
                emit(GlowPanelEvent.Animation(it))
            }
        }
        intensitySlider.addChangeListener { emit(GlowPanelEvent.Intensity(intensitySlider.value)) }
        widthSlider.addChangeListener { emit(GlowPanelEvent.Width(widthSlider.value)) }
        val content = panel { buildContent() }.apply { isOpaque = false }
        glowGroupPanel.add(content, BorderLayout.CENTER)
        add(glowGroupPanel, BorderLayout.CENTER)
    }

    fun render(view: GlowPanelView) {
        isRendering = true
        try {
            rendered = view
            val settings = view.settings
            editing.set(view.isEditable && view.isLicensed)
            featureEditing.set(editing.get() && settings.enabled)
            val observedGate = premiumGate.copy(isUnlocked = view.isLicensed)
            intensitySlider.applyPremiumLock(observedGate, featureEditing.get())
            widthSlider.applyPremiumLock(observedGate, featureEditing.get())
            licensed.set(view.isLicensed)
            locked.set(!view.isLicensed)
            visibility.refresh(settings, view.isLicensed)
            masterToggle.isSelected = settings.enabled
            preset.set(settings.preset)
            editor.set(settings.editorPlacement)
            toolWindow.set(settings.toolWindowPlacement)
            updateSolid(settings)
            islandCheckboxes.forEach { (id, checkbox) -> checkbox.isSelected = settings.isIslandEnabled(id) }
            waveformControls.refresh(settings.waveformValue(), view.isLicensed, view.pendingBadges)
            waveformControls.setEnabled(featureEditing.get())
            badges.forEach { (id, cell) ->
                val pending = id in view.pendingBadges
                cell.component.text = if (pending) "New" else ""
                cell.visible(pending)
            }
            glowGroupPanel.updatePreview(view.preview)
        } finally {
            isRendering = false
        }
    }

    private fun Panel.buildContent() {
        row { comment("Neon glow effects around editor islands and UI elements.") }
        row { comment(GLOW_DESCRIPTION) }.visibleIf(locked)
        row { glowLink("Unlock Pro") { emit(GlowPanelEvent.Unlock) } }.visibleIf(locked)
        row {
            cell(masterToggle).enabledIf(editing)
            glowLink("Reset defaults") { emit(GlowPanelEvent.Reset) }.visibleIf(licensed).enabledIf(editing)
        }
        group("Style") {
            waveformControls.build(this)
            row("Preset") {
                presetSegmentedButton =
                    segmentedButton(GlowPreset.entries) { text = it.displayName }
                        .maxButtonsCount(GlowPreset.entries.size)
                        .bind(preset)
            }.visibleIf(visibility.solidShape).enabledIf(featureEditing)
            buildSolid()
        }
        buildPlacements()
        buildTargets()
    }

    private fun Panel.buildSolid() {
        row("Style") { cell(styleCombo) }.visibleIf(visibility.solidControls).enabledIf(featureEditing)
        buildSlider("Intensity", intensitySlider, intensityValueLabel, INTENSITY_MAJOR_TICK, INTENSITY_MINOR_TICK)
        buildSlider("Width (px)", widthSlider, widthValueLabel, WIDTH_MAJOR_TICK)
        row("Animation") { cell(animationCombo) }.visibleIf(visibility.solidControls).enabledIf(featureEditing)
        row { animationDescriptionLabel = comment(animationDescription(GlowAnimation.NONE)).component }
            .visibleIf(visibility.solidControls)
    }

    private fun Panel.buildSlider(
        title: String,
        slider: JSlider,
        readout: JLabel,
        majorTick: Int,
        minorTick: Int = 0,
    ) {
        row(title) {
            slider.paintTicks = true
            slider.majorTickSpacing = majorTick
            slider.minorTickSpacing = minorTick
            cell(slider).resizableColumn().align(Align.FILL)
            cell(readout)
        }.visibleIf(visibility.solidControls).enabledIf(featureEditing)
    }

    private fun Panel.buildPlacements() {
        group("Placement") {
            row("Editor placement") {
                editorPlacement =
                    segmentedButton(GlowPlacement.entries) { text = it.displayName }
                        .maxButtonsCount(GlowPlacement.entries.size)
                        .bind(editor)
                badges["glow-placement"] = newFeatureBadge(false)
            }.enabledIf(featureEditing)
            row("Tool window placement") {
                toolWindowPlacement =
                    segmentedButton(GlowPlacement.entries) { text = it.displayName }
                        .maxButtonsCount(GlowPlacement.entries.size)
                        .bind(toolWindow)
            }.enabledIf(featureEditing)
            row { comment("Island glows the full frame; Side edges glows only the left and right strips.") }
        }.visibleIf(visibility.solidShape)
    }

    private fun Panel.buildTargets() {
        collapsibleGroup("Targets") {
            row { comment("Fine-tune where glow appears. All targets are enabled by default.") }
            row {
                glowLink("Enable all") { emit(GlowPanelEvent.AllIslands(true)) }
                glowLink("Disable all") { emit(GlowPanelEvent.AllIslands(false)) }
            }.enabledIf(featureEditing)
            row { comment("Waveform runs the full island perimeter.") }.visibleIf(visibility.waveform)
            threeColumnsRow({ label("Workspace").bold() }, { label("Output").bold() }, { label("VCS").bold() })
            threeColumnsRow({ island("Editor") }, { island("Terminal") }, { island("Git") })
            threeColumnsRow({ island("Project") }, { island("Run") }, { island("Services") })
            threeColumnsRow({}, { island("Debug") }, {})
        }.visibleIf(visibility.targets)
    }

    private fun Row.island(id: String) {
        val checkbox = JCheckBox(id)
        checkbox.addActionListener { emit(GlowPanelEvent.Island(id, checkbox.isSelected)) }
        islandCheckboxes[id] = checkbox
        cell(checkbox).enabledIf(featureEditing)
    }

    private fun updateSolid(settings: GlowSettings) {
        styleCombo.selectedItem = settings.style.displayName
        animationCombo.selectedItem = settings.animation.displayName
        val intensity = settings.intensity[settings.style] ?: settings.style.defaultIntensity
        val strokeWidth = settings.width[settings.style] ?: settings.style.defaultWidth
        intensitySlider.minimum = minOf(0, intensity)
        intensitySlider.maximum = maxOf(MAX_INTENSITY, intensity)
        intensitySlider.value = intensity
        widthSlider.minimum = minOf(MIN_WIDTH, strokeWidth)
        widthSlider.maximum = maxOf(MAX_WIDTH, strokeWidth)
        widthSlider.value = strokeWidth
        intensityValueLabel.text = intensity.toString()
        widthValueLabel.text = strokeWidth.toString()
        animationDescriptionLabel?.text = animationDescription(settings.animation)
    }

    private fun emit(event: GlowPanelEvent) {
        val view = rendered ?: return
        if (isRendering) return
        val permitted =
            when (event) {
                GlowPanelEvent.Unlock -> !view.isLicensed
                is GlowPanelEvent.BadgeSeen -> featureEditing.get()
                is GlowPanelEvent.Enable, GlowPanelEvent.Reset -> editing.get()
                else -> featureEditing.get()
            }
        if (permitted) onEvent(event)
    }

    private companion object {
        const val GLOW_DESCRIPTION =
            "Glow is a Pro feature. Preview style, animation, width, intensity, and target controls here."
        const val MAX_INTENSITY = 100
        const val INTENSITY_MAJOR_TICK = 25
        const val INTENSITY_MINOR_TICK = 5
        const val MIN_WIDTH = 2
        const val MAX_WIDTH = 16
        const val WIDTH_MAJOR_TICK = 2
    }
}

private fun animationDescription(animation: GlowAnimation): String =
    when (animation) {
        GlowAnimation.NONE -> "Static glow with no animation."
        GlowAnimation.PULSE -> "Sharp rhythmic brightening every 2 seconds."
        GlowAnimation.BREATHE -> "Slow sinusoidal swell over 4 seconds."
        GlowAnimation.REACTIVE -> "Responds to typing and IDE actions."
    }

private fun Row.glowLink(
    text: String,
    action: () -> Unit,
): Cell<ActionLink> = link(text) { action() }.applyToComponent { autoHideOnDisable = false }
