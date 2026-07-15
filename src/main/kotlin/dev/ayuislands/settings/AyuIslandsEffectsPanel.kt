package dev.ayuislands.settings

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.licensing.LicenseChecker
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JSlider
import com.intellij.ui.dsl.builder.panel as dslPanel

private data class SliderConfig(
    val label: String,
    val min: Int,
    val max: Int,
    val initialValue: Int,
    val majorTick: Int,
    val minorTick: Int = 0,
)

private data class PlacementRowConfig(
    val label: String,
    val items: List<GlowPlacement>,
    val licensed: Boolean,
    val visible: AtomicBooleanProperty,
    val badgeAnchorId: String? = null,
)

/**
 * Glow effects configuration rendered in a single Glow tab.
 *
 * [buildGlowPanel] renders the master toggle, preset selection, style/animation controls,
 * sliders, and glow targets.
 * Called from [AyuIslandsConfigurable] into a single tab pane.
 *
 * Pending/stored bookkeeping is delegated to a [SettingsSection] over the
 * immutable [GlowSettings] snapshot.
 */
@Suppress("TooManyFunctions") // Settings panel with grouped UI builders
class AyuIslandsEffectsPanel : AyuIslandsSettingsPanel {
    private val section =
        SettingsSection(initial = GlowSettings()) {
            val state = AyuIslandsSettings.getInstance().state
            loadGlowSettings(state, migratePresetIfNeeded(state), ISLAND_IDS)
        }

    private val visibility = GlowVisibility()

    // UI components
    private var masterToggle: JCheckBox? = null
    private var intensitySlider: JSlider? = null
    private var widthSlider: JSlider? = null
    private var styleCombo: ComboBox<String>? = null
    private var animationCombo: ComboBox<String>? = null
    private var waveformControls: WaveformSettingsControls? = null
    private var intensityValueLabel: JLabel? = null
    private var widthValueLabel: JLabel? = null
    private val islandCheckboxes = mutableMapOf<String, JCheckBox>()
    private var animationDescriptionLabel: javax.swing.JEditorPane? = null
    private var presetSegmentedButton: SegmentedButton<GlowPreset>? = null
    private var editorPlacementSegmented: SegmentedButton<GlowPlacement>? = null
    private var toolWindowPlacementSegmented: SegmentedButton<GlowPlacement>? = null
    private var glowGroupPanel: GlowGroupPanel? = null

    // Live-preview seam (same idea as the Appearance theme preview): a
    // placement click restyles live overlays before Apply; reset/dispose
    // reverts to stored state. Tests inject recorders.
    internal var placementPreview: (GlowPlacement, GlowPlacement) -> Unit = { editor, toolWindow ->
        forEachOpenGlowManager { it.previewPlacements(editor, toolWindow) }
    }
    internal var placementPreviewRevert: () -> Unit = {
        forEachOpenGlowManager { it.previewPlacements(null, null) }
    }

    // Suppress listener events during programmatic updates
    private var suppressListeners = false
    private var stateLoaded = false
    private var controlsLicensed = false

    /** Not used — the Glow panel is built via [buildGlowPanel]. */
    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) { /* Glow panel is built via buildGlowPanel instead */ }

    private fun ensureStateLoaded() {
        if (stateLoaded) return
        section.load()
        stateLoaded = true
    }

    // Glow tab content

    fun buildGlowPanel(panel: Panel) {
        ensureStateLoaded()
        val gate =
            PremiumFeatureGate(
                featureName = "Glow",
                lockedDescription =
                    "Glow is a Pro feature. " +
                        "Preview style, animation, width, intensity, and target controls here.",
                requestMessage = "Unlock glow effects",
            )
        controlsLicensed = gate.isUnlocked
        refreshVisibility()

        buildStyleGroup(panel, gate)

        updateControlStates()
    }

    private fun buildMasterToggleRow(
        panel: Panel,
        licensed: Boolean,
    ) {
        panel.row {
            val cb = checkBox("Enable glow")
            cb.component.isSelected = section.pending.enabled
            cb.component.isEnabled = licensed
            cb.component.addActionListener {
                section.update { it.copy(enabled = cb.component.isSelected) }
                updateControlStates()
                updateGlowGroupPanel()
            }
            masterToggle = cb.component

            if (licensed) {
                link("Reset defaults") {
                    section.update(GlowSettings::withDefaults)
                    refreshAllControls()
                }
            }
        }

        // Preview panel removed: the main GlowGroupPanel border serves as a live preview
    }

    private fun buildStyleGroup(
        panel: Panel,
        gate: PremiumFeatureGate,
    ) {
        val licensed = gate.isUnlocked
        val glowPanel = GlowGroupPanel()
        glowGroupPanel = glowPanel

        val innerContent =
            dslPanel {
                row {
                    comment("Neon glow effects around editor islands and UI elements.")
                }
                premiumFeatureNotice(gate)
                buildMasterToggleRow(this, licensed)
                group("Style") {
                    buildWaveformControls(this, gate)
                    buildPresetRow(this)
                    buildStyleComboRow(this, gate)
                    buildSliderRow(
                        group = this,
                        gate = gate,
                        config =
                            SliderConfig(
                                label = "Intensity",
                                min = 0,
                                max = MAX_INTENSITY,
                                initialValue = section.pending.intensity[section.pending.style] ?: DEFAULT_INTENSITY,
                                majorTick = INTENSITY_MAJOR_TICK,
                                minorTick = INTENSITY_MINOR_TICK,
                            ),
                        onValueChanged = { value ->
                            section.update { it.copy(intensity = it.intensity + (it.style to value)) }
                            updateGlowGroupPanel()
                        },
                        onCreated = { slider, label ->
                            intensitySlider = slider
                            intensityValueLabel = label
                        },
                    )
                    buildSliderRow(
                        group = this,
                        gate = gate,
                        config =
                            SliderConfig(
                                label = "Width (px)",
                                min = MIN_WIDTH,
                                max = MAX_WIDTH,
                                initialValue = section.pending.width[section.pending.style] ?: DEFAULT_WIDTH,
                                majorTick = WIDTH_MAJOR_TICK,
                            ),
                        onValueChanged = { value ->
                            section.update { it.copy(width = it.width + (it.style to value)) }
                            updateGlowGroupPanel()
                        },
                        onCreated = { slider, label ->
                            widthSlider = slider
                            widthValueLabel = label
                        },
                    )
                    buildAnimationRows(this, gate)
                }
                buildTargetsGroup(this, gate)
            }
        innerContent.isOpaque = false

        glowPanel.add(innerContent, BorderLayout.CENTER)
        updateGlowGroupPanel()

        panel.row {
            cell(glowPanel).resizableColumn().align(Align.FILL)
        }
    }

    private fun buildPresetRow(group: Panel) {
        group
            .row {
                label("Preset")
                val segmented =
                    segmentedButton(GlowPreset.entries) { preset ->
                        text = preset.displayName
                    }
                segmented.maxButtonsCount(GlowPreset.entries.size)
                segmented.selectedItem = section.pending.preset
                @Suppress("UnstableApiUsage")
                segmented.whenItemSelected { preset ->
                    if (!suppressListeners && LicenseChecker.isLicensedOrGrace()) {
                        section.update { current ->
                            if (preset == GlowPreset.CUSTOM) {
                                current.copy(preset = preset)
                            } else {
                                current.withPresetValues(preset).copy(preset = preset)
                            }
                        }
                        if (preset != GlowPreset.CUSTOM) {
                            refreshSliders()
                            refreshStyleAndAnimation()
                        }
                        refreshVisibility()
                        updateControlStates()
                        updateGlowGroupPanel()
                    }
                }
                presetSegmentedButton = segmented
            }.visibleIf(visibility.solidShape)
    }

    private fun buildWaveformControls(
        group: Panel,
        gate: PremiumFeatureGate,
    ) {
        val pending = section.pending
        waveformControls =
            WaveformSettingsControls(
                initial = pending.waveformValue(),
                gate = gate,
                visibility =
                    WaveformControlVisibility(
                        visibility.waveform,
                        visibility.direction,
                        visibility.loopDuration,
                    ),
                onChange = { waveform ->
                    section.update {
                        it.copy(
                            shape = waveform.shape,
                            waveformMotion = waveform.motion,
                            waveformDirection = waveform.direction,
                            waveformBaseline = waveform.baseline,
                            waveformTraceDensity = waveform.traceDensity,
                            waveformTraceLength = waveform.traceLength,
                            waveformAmplitude = waveform.amplitude,
                            waveformIntensity = waveform.intensity,
                            waveformLoopSeconds = waveform.loopSeconds,
                        )
                    }
                    refreshVisibility()
                    updateControlStates()
                    updateGlowGroupPanel()
                },
            ).also { it.build(group) }
    }

    private fun buildStyleComboRow(
        group: Panel,
        gate: PremiumFeatureGate,
    ) {
        val licensed = gate.isUnlocked
        group
            .row {
                label("Style")
                val model = DefaultComboBoxModel(GlowStyle.entries.map { it.displayName }.toTypedArray())
                val combo = ComboBox(model)
                combo.selectedItem = section.pending.style.displayName
                combo.isEnabled = licensed && section.pending.enabled
                combo.addActionListener {
                    if (!suppressListeners) {
                        val selectedName = combo.selectedItem as? String ?: return@addActionListener
                        val style = GlowStyle.entries.first { it.displayName == selectedName }
                        section.update { it.copy(style = style) }
                        switchToCustom()
                        refreshSliders()
                    }
                }
                styleCombo = combo
                cell(combo)
            }.visibleIf(visibility.solidControls)
    }

    private fun buildSliderRow(
        group: Panel,
        gate: PremiumFeatureGate,
        config: SliderConfig,
        onValueChanged: (Int) -> Unit,
        onCreated: (JSlider, JLabel) -> Unit,
    ) {
        group
            .row {
                label(config.label)
                val slider = JSlider(config.min, config.max, config.initialValue)
                slider.paintTicks = true
                slider.majorTickSpacing = config.majorTick
                if (config.minorTick > 0) slider.minorTickSpacing = config.minorTick
                slider.applyPremiumLock(gate, section.pending.enabled)
                val valueLabel = JLabel("${slider.value}")
                slider.addChangeListener {
                    if (!suppressListeners && gate.isUnlocked) {
                        onValueChanged(slider.value)
                        switchToCustom()
                    }
                    valueLabel.text = "${slider.value}"
                }
                onCreated(slider, valueLabel)
                cell(slider).resizableColumn().align(Align.FILL)
                cell(valueLabel)
            }.visibleIf(visibility.solidControls)
    }

    private fun buildAnimationRows(
        group: Panel,
        gate: PremiumFeatureGate,
    ) {
        val licensed = gate.isUnlocked
        group
            .row {
                label("Animation")
                val model = DefaultComboBoxModel(GlowAnimation.entries.map { it.displayName }.toTypedArray())
                val combo = ComboBox(model)
                combo.selectedItem = section.pending.animation.displayName
                combo.isEnabled = licensed && section.pending.enabled
                combo.addActionListener {
                    if (!suppressListeners) {
                        val selectedName = combo.selectedItem as? String ?: return@addActionListener
                        val animation = GlowAnimation.entries.first { it.displayName == selectedName }
                        section.update { it.copy(animation = animation) }
                        switchToCustom()
                        updateAnimationDescription()
                    }
                }
                animationCombo = combo
                cell(combo)
            }.visibleIf(visibility.solidControls)
        group
            .row {
                val descCell = comment(animationDescription(section.pending.animation))
                animationDescriptionLabel = descCell.component
            }.visibleIf(visibility.solidControls)
    }

    private fun buildTargetsGroup(
        panel: Panel,
        gate: PremiumFeatureGate,
    ) {
        val licensed = gate.isUnlocked
        val targetsGroup =
            panel
                .collapsibleGroup("Targets") {
                    row {
                        comment(
                            "Fine-tune where glow appears. All targets are enabled by default.",
                        )
                    }
                    row {
                        link("Enable all") {
                            section.update { s -> s.copy(islandToggles = s.islandToggles.mapValues { true }) }
                            refreshIslandCheckboxes()
                        }.enabled(licensed && section.pending.enabled)
                        link("Disable all") {
                            section.update { s -> s.copy(islandToggles = s.islandToggles.mapValues { false }) }
                            refreshIslandCheckboxes()
                        }.enabled(licensed && section.pending.enabled)
                    }
                    editorPlacementSegmented =
                        buildPlacementRow(
                            PlacementRowConfig(
                                label = "Editor placement",
                                items = listOf(GlowPlacement.ISLAND, GlowPlacement.SIDE_EDGES),
                                licensed = licensed,
                                badgeAnchorId = "glow-placement",
                                visible = visibility.placement,
                            ),
                        ) { placement ->
                            section.update { it.copy(editorPlacement = placement) }
                            pushPlacementPreview()
                        }
                    editorPlacementSegmented?.selectedItem = section.pending.editorPlacement
                    toolWindowPlacementSegmented =
                        buildPlacementRow(
                            PlacementRowConfig(
                                label = "Tool window placement",
                                items = listOf(GlowPlacement.ISLAND, GlowPlacement.SIDE_EDGES),
                                licensed = licensed,
                                visible = visibility.placement,
                            ),
                        ) { placement ->
                            section.update { it.copy(toolWindowPlacement = placement) }
                            pushPlacementPreview()
                        }
                    toolWindowPlacementSegmented?.selectedItem = section.pending.toolWindowPlacement
                    row {
                        comment("Island glows the full frame; Side edges glows only the left and right strips.")
                    }.visibleIf(visibility.placement)
                    row {
                        comment("Waveform runs the full island perimeter.")
                    }.visibleIf(visibility.waveform)
                    // Headers row
                    threeColumnsRow(
                        { label("Workspace").bold() },
                        { label("Output").bold() },
                        { label("VCS").bold() },
                    )
                    // Row 1: Editor | Terminal | Git
                    threeColumnsRow(
                        { islandCheckbox("Editor", licensed) },
                        { islandCheckbox("Terminal", licensed) },
                        { islandCheckbox("Git", licensed) },
                    )
                    // Row 2: Project | Run | Services
                    threeColumnsRow(
                        { islandCheckbox("Project", licensed) },
                        { islandCheckbox("Run", licensed) },
                        { islandCheckbox("Services", licensed) },
                    )
                    // Row 3: (empty) | Debug | (empty)
                    threeColumnsRow(
                        { },
                        { islandCheckbox("Debug", licensed) },
                        { },
                    )
                }
        // Show the placement badge only when target controls and placement rows
        // are both visible; waveform uses the same targets without placement.
        targetsGroup.bindNewSettingBadge("glow-placement") {
            visibility.targets.get() && visibility.placement.get()
        }
        targetsGroup.visibleIf(visibility.targets)
    }

    private fun Panel.buildPlacementRow(
        config: PlacementRowConfig,
        onSelected: (GlowPlacement) -> Unit,
    ): SegmentedButton<GlowPlacement> {
        lateinit var segmented: SegmentedButton<GlowPlacement>
        row {
            label(config.label)
            segmented =
                segmentedButton(config.items) { placement ->
                    text = placement.displayName
                }
            segmented.maxButtonsCount(config.items.size)
            segmented.enabled(config.licensed && section.pending.enabled)
            @Suppress("UnstableApiUsage")
            segmented.whenItemSelected { placement ->
                if (!suppressListeners && LicenseChecker.isLicensedOrGrace()) {
                    onSelected(placement)
                }
            }
            config.badgeAnchorId?.let { newFeatureBadge(it) }
        }.visibleIf(config.visible)
        return segmented
    }

    private fun Row.islandCheckbox(
        islandId: String,
        licensed: Boolean,
    ) {
        val cb = checkBox(islandId)
        cb.component.isSelected = section.pending.islandToggles[islandId] ?: false
        cb.component.isEnabled = licensed && section.pending.enabled
        cb.component.addActionListener {
            section.update { it.copy(islandToggles = it.islandToggles + (islandId to cb.component.isSelected)) }
        }
        islandCheckboxes[islandId] = cb.component
    }

    // Preset helpers

    private fun switchToCustom() {
        if (section.pending.preset != GlowPreset.CUSTOM) {
            section.update { it.copy(preset = GlowPreset.CUSTOM) }
            refreshVisibility()
            suppressListeners = true
            presetSegmentedButton?.selectedItem = GlowPreset.CUSTOM
            suppressListeners = false
        }
        updateGlowGroupPanel()
    }

    private fun refreshSliders() {
        suppressListeners = true
        val pending = section.pending
        val intensity = pending.intensity[pending.style] ?: pending.style.defaultIntensity
        val width = pending.width[pending.style] ?: pending.style.defaultWidth
        intensitySlider?.value = intensity
        widthSlider?.value = width
        intensityValueLabel?.text = "$intensity"
        widthValueLabel?.text = "$width"
        suppressListeners = false
    }

    private fun refreshStyleAndAnimation() {
        suppressListeners = true
        styleCombo?.selectedItem = section.pending.style.displayName
        animationCombo?.selectedItem = section.pending.animation.displayName
        updateAnimationDescription()
        suppressListeners = false
    }

    private fun refreshIslandCheckboxes() {
        for ((id, cb) in islandCheckboxes) {
            cb.isSelected = section.pending.islandToggles[id] ?: false
        }
    }

    // Animation

    private fun animationDescription(animation: GlowAnimation): String =
        when (animation) {
            GlowAnimation.NONE -> "Static glow with no animation."
            GlowAnimation.PULSE -> "Sharp rhythmic brightening every 2 seconds."
            GlowAnimation.BREATHE -> "Slow sinusoidal swell over 4 seconds."
            GlowAnimation.REACTIVE -> "Responds to typing and IDE actions."
        }

    private fun updateAnimationDescription() {
        animationDescriptionLabel?.text = animationDescription(section.pending.animation)
    }

    // State management

    private fun migratePresetIfNeeded(state: AyuIslandsState): String {
        state.glowPreset?.let { return it }
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        val intensity = state.getIntensityForStyle(style)
        val width = state.getWidthForStyle(style)
        val animation = GlowAnimation.fromName(state.glowAnimation ?: GlowAnimation.NONE.name)
        return GlowPreset.detect(style, intensity, width, animation).name
    }

    private fun resolveAccentColor(): Color {
        val variant = AyuVariant.detect()
        val settings = AyuIslandsSettings.getInstance()
        val hex = if (variant != null) settings.getAccentForVariant(variant) else FALLBACK_COLOR_HEX
        return try {
            Color.decode(hex)
        } catch (_: NumberFormatException) {
            Color.decode(FALLBACK_COLOR_HEX)
        }
    }

    private fun updateGlowGroupPanel() {
        val panel = glowGroupPanel ?: return
        val pending = section.pending
        val style = pending.style
        val intensity = pending.intensity[style] ?: style.defaultIntensity
        val width = pending.width[style] ?: style.defaultWidth
        val color = resolveAccentColor()
        val visible = pending.enabled || !LicenseChecker.isLicensedOrGrace()
        panel.updatePreview(
            GlowPreview(
                shape = pending.shape,
                style = style,
                intensity = intensity,
                width = width,
                color = color,
                visible = visible,
                waveformConfig =
                    WaveformConfig(
                        motion = pending.waveformMotion,
                        direction = pending.waveformDirection,
                        baseline = pending.waveformBaseline,
                        traceDensity = pending.waveformTraceDensity,
                        traceLength = pending.waveformTraceLength,
                        amplitude = pending.waveformAmplitude,
                        intensity = pending.waveformIntensity,
                        loopSeconds = pending.waveformLoopSeconds,
                    ),
            ),
        )
    }

    private fun updateControlStates() {
        val enabled = section.pending.enabled && LicenseChecker.isLicensedOrGrace()

        intensitySlider?.isEnabled = enabled
        widthSlider?.isEnabled = enabled
        styleCombo?.isEnabled = enabled
        animationCombo?.isEnabled = enabled
        waveformControls?.setEnabled(enabled)
        presetSegmentedButton?.enabled(enabled)
        editorPlacementSegmented?.enabled(enabled)
        toolWindowPlacementSegmented?.enabled(enabled)

        for ((_, cb) in islandCheckboxes) {
            cb.isEnabled = enabled
        }
    }

    private fun refreshAllControls() {
        suppressListeners = true
        presetSegmentedButton?.selectedItem = section.pending.preset
        editorPlacementSegmented?.selectedItem = section.pending.editorPlacement
        toolWindowPlacementSegmented?.selectedItem = section.pending.toolWindowPlacement
        styleCombo?.selectedItem = section.pending.style.displayName
        animationCombo?.selectedItem = section.pending.animation.displayName
        waveformControls?.refresh(section.pending.waveformValue())
        refreshSliders()
        refreshIslandCheckboxes()
        masterToggle?.isSelected = section.pending.enabled
        updateAnimationDescription()
        suppressListeners = false
        refreshVisibility()
        updateControlStates()
        updateGlowGroupPanel()
    }

    // AyuIslandsSettingsPanel contract

    override fun isModified(): Boolean = section.isModified()

    override fun apply() {
        if (!isModified()) return
        if (!LicenseChecker.isLicensedOrGrace()) return

        section.commit { pending, _ ->
            val state = AyuIslandsSettings.getInstance().state
            state.glowEnabled = pending.enabled
            state.glowShape = pending.shape.name
            state.glowPreset = pending.preset.name
            state.glowStyle = pending.style.name
            state.glowAnimation = pending.animation.name

            for (style in GlowStyle.entries) {
                state.setIntensityForStyle(style, pending.intensity[style] ?: style.defaultIntensity)
                state.setWidthForStyle(style, pending.width[style] ?: style.defaultWidth)
            }

            for (id in ISLAND_IDS) {
                state.setIslandEnabled(id, pending.islandToggles[id] ?: false)
            }

            state.glowEditorPlacement = pending.editorPlacement.name
            state.glowToolWindowPlacement = pending.toolWindowPlacement.name
            state.waveformMotion = pending.waveformMotion.name
            state.waveformDirection = pending.waveformDirection.name
            state.waveformBaseline = pending.waveformBaseline.name
            state.waveformTraceDensity = pending.waveformTraceDensity
            state.waveformTraceLength = pending.waveformTraceLength
            state.waveformAmplitude = pending.waveformAmplitude
            state.waveformIntensity = pending.waveformIntensity
            state.waveformLoopSeconds = pending.waveformLoopSeconds
        }
    }

    override fun reset() {
        section.resetToStored()
        refreshAllControls()
        placementPreviewRevert()
    }

    override fun dispose() {
        // A previewed-but-not-applied placement must not outlive the dialog.
        placementPreviewRevert()
    }

    private fun pushPlacementPreview() {
        placementPreview(section.pending.editorPlacement, section.pending.toolWindowPlacement)
    }

    private fun refreshVisibility() {
        visibility.refresh(section.pending, controlsLicensed)
    }

    companion object {
        private const val MAX_INTENSITY = 100
        private const val DEFAULT_INTENSITY = 20
        private const val INTENSITY_MAJOR_TICK = 25
        private const val INTENSITY_MINOR_TICK = 5
        private const val MIN_WIDTH = 2
        private const val MAX_WIDTH = 16
        private const val DEFAULT_WIDTH = 4
        private const val WIDTH_MAJOR_TICK = 2
        private const val FALLBACK_COLOR_HEX = "#FFCC66"
        private val ISLAND_IDS = listOf("Editor", "Project", "Terminal", "Run", "Debug", "Git", "Services")
    }
}

private fun forEachOpenGlowManager(action: (GlowOverlayManager) -> Unit) {
    for (openProject in ProjectManager.getInstance().openProjects) {
        try {
            action(GlowOverlayManager.getInstance(openProject))
        } catch (exception: RuntimeException) {
            logger<AyuIslandsEffectsPanel>()
                .warn("Glow placement preview failed for project ${openProject.name}", exception)
        }
    }
}
