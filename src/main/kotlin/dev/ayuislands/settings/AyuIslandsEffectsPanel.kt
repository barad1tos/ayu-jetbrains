package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
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
    private data class GlowSettings(
        val enabled: Boolean = false,
        val preset: GlowPreset = GlowPreset.WHISPER,
        val style: GlowStyle = GlowStyle.SOFT,
        val intensity: Map<GlowStyle, Int> = emptyMap(),
        val width: Map<GlowStyle, Int> = emptyMap(),
        val animation: GlowAnimation = GlowAnimation.NONE,
        val islandToggles: Map<String, Boolean> = emptyMap(),
        val editorPlacement: GlowPlacement = GlowPlacement.ISLAND,
        val toolWindowPlacement: GlowPlacement = GlowPlacement.ISLAND,
    ) {
        /**
         * Folds [preset]'s canonical style/intensity/width/animation into the
         * snapshot. Returns `this` unchanged for presets without canonical
         * values (i.e. [GlowPreset.CUSTOM]).
         */
        fun withPresetValues(preset: GlowPreset): GlowSettings {
            val presetStyle = preset.style ?: return this
            val presetIntensity = preset.intensity ?: return this
            val presetWidth = preset.width ?: return this
            val presetAnimation = preset.animation ?: return this
            return copy(
                style = presetStyle,
                intensity = intensity + (presetStyle to presetIntensity),
                width = width + (presetStyle to presetWidth),
                animation = presetAnimation,
            )
        }
    }

    private val section =
        SettingsSection(initial = GlowSettings()) {
            val state = AyuIslandsSettings.getInstance().state
            GlowSettings(
                enabled = state.glowEnabled,
                preset = GlowPreset.fromName(migratePresetIfNeeded(state)),
                style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name),
                intensity = GlowStyle.entries.associateWith { state.getIntensityForStyle(it) },
                width = GlowStyle.entries.associateWith { state.getWidthForStyle(it) },
                animation = GlowAnimation.fromName(state.glowAnimation ?: GlowAnimation.NONE.name),
                islandToggles = ISLAND_IDS.associateWith { state.isIslandEnabled(it) },
                editorPlacement = GlowPlacement.forEditor(state.glowEditorPlacement),
                toolWindowPlacement = GlowPlacement.forToolWindow(state.glowToolWindowPlacement),
            )
        }

    // Observable property for Custom mode visibility
    private val customModeVisible = AtomicBooleanProperty(false)

    // UI components
    private var masterToggle: JCheckBox? = null
    private var intensitySlider: JSlider? = null
    private var widthSlider: JSlider? = null
    private var styleCombo: ComboBox<String>? = null
    private var animationCombo: ComboBox<String>? = null
    private var intensityValueLabel: JLabel? = null
    private var widthValueLabel: JLabel? = null
    private val islandCheckboxes = mutableMapOf<String, JCheckBox>()
    private var animationDescriptionLabel: javax.swing.JEditorPane? = null
    private var presetSegmentedButton: SegmentedButton<GlowPreset>? = null
    private var editorPlacementSegmented: SegmentedButton<GlowPlacement>? = null
    private var toolWindowPlacementSegmented: SegmentedButton<GlowPlacement>? = null
    private var glowGroupPanel: GlowGroupPanel? = null

    // Suppress listener events during programmatic updates
    private var suppressListeners = false
    private var stateLoaded = false

    /** Not used — the Glow panel is built via [buildGlowPanel]. */
    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) { /* Glow panel is built via buildGlowPanel instead */ }

    private fun ensureStateLoaded() {
        if (stateLoaded) return
        section.load()
        customModeVisible.set(section.pending.preset == GlowPreset.CUSTOM)
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
                    section.update {
                        it.withPresetValues(GlowPreset.WHISPER).copy(preset = GlowPreset.WHISPER)
                    }
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
                buildTargetsGroup(this, gate, customModeVisible)
            }

        glowPanel.add(innerContent, BorderLayout.CENTER)
        updateGlowGroupPanel()

        panel.row {
            cell(glowPanel).resizableColumn().align(Align.FILL)
        }
    }

    private fun buildPresetRow(group: Panel) {
        group.row {
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
                    customModeVisible.set(preset == GlowPreset.CUSTOM)
                    updateControlStates()
                    updateGlowGroupPanel()
                }
            }
            presetSegmentedButton = segmented
        }
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
            }.visibleIfUnlockedOrPreview(customModeVisible, gate)
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
            }.visibleIfUnlockedOrPreview(customModeVisible, gate)
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
            }.visibleIfUnlockedOrPreview(customModeVisible, gate)
        group
            .row {
                val descCell = comment(animationDescription(section.pending.animation))
                animationDescriptionLabel = descCell.component
            }.visibleIfUnlockedOrPreview(customModeVisible, gate)
    }

    private fun buildTargetsGroup(
        panel: Panel,
        gate: PremiumFeatureGate,
        customVisible: AtomicBooleanProperty,
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
                            labelText = "Editor placement",
                            items = listOf(GlowPlacement.ISLAND, GlowPlacement.TAB_BAR),
                            licensed = licensed,
                            badgeAnchorId = "glow-placement",
                        ) { placement -> section.update { it.copy(editorPlacement = placement) } }
                    editorPlacementSegmented?.selectedItem = section.pending.editorPlacement
                    toolWindowPlacementSegmented =
                        buildPlacementRow(
                            labelText = "Tool window placement",
                            items = listOf(GlowPlacement.ISLAND, GlowPlacement.SIDE_EDGES),
                            licensed = licensed,
                        ) { placement -> section.update { it.copy(toolWindowPlacement = placement) } }
                    toolWindowPlacementSegmented?.selectedItem = section.pending.toolWindowPlacement
                    row {
                        comment("Island glows the full frame; Under tabs and Side edges glow only that strip.")
                    }
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
        targetsGroup.bindNewSettingBadge("glow-placement")
        targetsGroup.visibleIfUnlockedOrPreview(customVisible, gate)
    }

    private fun Panel.buildPlacementRow(
        labelText: String,
        items: List<GlowPlacement>,
        licensed: Boolean,
        badgeAnchorId: String? = null,
        onSelected: (GlowPlacement) -> Unit,
    ): SegmentedButton<GlowPlacement> {
        lateinit var segmented: SegmentedButton<GlowPlacement>
        row {
            label(labelText)
            segmented =
                segmentedButton(items) { placement ->
                    text = placement.displayName
                }
            segmented.maxButtonsCount(items.size)
            segmented.enabled(licensed && section.pending.enabled)
            @Suppress("UnstableApiUsage")
            segmented.whenItemSelected { placement ->
                if (!suppressListeners && LicenseChecker.isLicensedOrGrace()) {
                    onSelected(placement)
                }
            }
            badgeAnchorId?.let { newFeatureBadge(it) }
        }
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
            customModeVisible.set(true)
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
        panel.updateFromPreset(style, intensity, width, color, visible)
    }

    private fun updateControlStates() {
        val enabled = section.pending.enabled && LicenseChecker.isLicensedOrGrace()

        intensitySlider?.isEnabled = enabled
        widthSlider?.isEnabled = enabled
        styleCombo?.isEnabled = enabled
        animationCombo?.isEnabled = enabled
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
        customModeVisible.set(section.pending.preset == GlowPreset.CUSTOM)
        styleCombo?.selectedItem = section.pending.style.displayName
        animationCombo?.selectedItem = section.pending.animation.displayName
        refreshSliders()
        refreshIslandCheckboxes()
        masterToggle?.isSelected = section.pending.enabled
        updateAnimationDescription()
        suppressListeners = false
        updateControlStates()
        updateGlowGroupPanel()
    }

    // AyuIslandsSettingsPanel contract

    override fun isModified(): Boolean = section.isModified()

    override fun apply() {
        if (!isModified()) return
        if (!LicenseChecker.isLicensedOrGrace()) return

        // Write-through: preset values → raw state fields (GlowOverlayManager reads
        // raw fields). Folding before commit lets stored converge onto the folded
        // pending values, exactly like the previous copy-pending-to-stored did.
        section.update { if (it.preset == GlowPreset.CUSTOM) it else it.withPresetValues(it.preset) }

        section.commit { pending, _ ->
            val state = AyuIslandsSettings.getInstance().state
            state.glowEnabled = pending.enabled
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
        }
    }

    override fun reset() {
        section.resetToStored()
        refreshAllControls()
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
