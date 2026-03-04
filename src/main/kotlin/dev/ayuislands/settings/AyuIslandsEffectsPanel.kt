package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.licensing.LicenseChecker
import java.awt.Color
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JSlider

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
 * sliders, glow targets, tab mode, focus ring, and floating panels.
 * Called from [AyuIslandsConfigurable] into a single tab pane.
 */
@Suppress("TooManyFunctions") // Settings panel with grouped UI builders
class AyuIslandsEffectsPanel : AyuIslandsSettingsPanel {
    // Pending state (applied on OK/Apply, not live)
    private var pendingGlowEnabled: Boolean = false
    private var pendingPreset: GlowPreset = GlowPreset.WHISPER
    private var pendingStyle: GlowStyle = GlowStyle.SOFT
    private var pendingIntensity: MutableMap<GlowStyle, Int> = mutableMapOf()
    private var pendingWidth: MutableMap<GlowStyle, Int> = mutableMapOf()
    private var pendingAnimation: GlowAnimation = GlowAnimation.NONE
    private var pendingIslandToggles: MutableMap<String, Boolean> = mutableMapOf()
    private var pendingTabMode: String = "UNDERLINE"
    private var pendingFocusRing: Boolean = true
    private var pendingFloatingPanels: Boolean = false

    // Stored state (for isModified comparison)
    private var storedGlowEnabled: Boolean = false
    private var storedPreset: GlowPreset = GlowPreset.WHISPER
    private var storedStyle: GlowStyle = GlowStyle.SOFT
    private var storedIntensity: Map<GlowStyle, Int> = emptyMap()
    private var storedWidth: Map<GlowStyle, Int> = emptyMap()
    private var storedAnimation: GlowAnimation = GlowAnimation.NONE
    private var storedIslandToggles: Map<String, Boolean> = emptyMap()
    private var storedTabMode: String = "UNDERLINE"
    private var storedFocusRing: Boolean = true
    private var storedFloatingPanels: Boolean = false

    // Observable property for Custom mode visibility
    private val customModeVisible = AtomicBooleanProperty(false)

    // UI components
    private var masterToggle: JCheckBox? = null
    private var intensitySlider: JSlider? = null
    private var widthSlider: JSlider? = null
    private var styleCombo: ComboBox<String>? = null
    private var animationCombo: ComboBox<String>? = null
    private var tabModeCombo: ComboBox<String>? = null
    private var intensityValueLabel: JLabel? = null
    private var widthValueLabel: JLabel? = null
    private val islandCheckboxes = mutableMapOf<String, JCheckBox>()
    private var animationDescriptionLabel: JLabel? = null
    private var focusRingCheckbox: JCheckBox? = null
    private var floatingCheckbox: JCheckBox? = null
    private var presetSegmentedButton: SegmentedButton<GlowPreset>? = null
    private var glowPreview: GlowGroupPanel? = null

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
        val state = AyuIslandsSettings.getInstance().state
        val migratedPreset = migratePresetIfNeeded(state)
        loadStateIntoPending(state, migratedPreset)
        copyPendingToStored()
        stateLoaded = true
    }

    // Glow tab content

    fun buildGlowPanel(panel: Panel) {
        ensureStateLoaded()
        val licensed = LicenseChecker.isLicensedOrGrace()

        panel.row {
            comment("Neon glow effects around editor islands and UI elements.")
        }

        buildMasterToggleRow(panel, licensed)
        buildStyleGroup(panel, licensed)
        buildTargetsGroup(panel, licensed)
        buildExtrasGroup(panel, licensed)

        updateControlStates()
    }

    private fun buildMasterToggleRow(
        panel: Panel,
        licensed: Boolean,
    ) {
        panel.row {
            val cb = checkBox("Enable glow")
            cb.component.isSelected = pendingGlowEnabled
            cb.component.isEnabled = licensed
            cb.component.addActionListener {
                pendingGlowEnabled = cb.component.isSelected
                updateControlStates()
            }
            masterToggle = cb.component

            if (!licensed) {
                @Suppress("DialogTitleCapitalization")
                link("Get Ayu Islands Pro") {
                    LicenseChecker.requestLicense(
                        "Unlock per-element accent toggles and neon glow effects",
                    )
                }
            }

            if (licensed) {
                link("Reset defaults") {
                    applyPresetValues(GlowPreset.WHISPER)
                    pendingPreset = GlowPreset.WHISPER
                    refreshAllControls()
                }
            }
        }

        if (!licensed) {
            val variant = AyuVariant.detect() ?: AyuVariant.MIRAGE
            val accentHex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
            val preview = GlowGroupPanel()
            preview.preferredSize = Dimension(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            preview.updateFromPreset(
                GlowStyle.SOFT,
                GlowPreset.WHISPER.intensity ?: DEFAULT_INTENSITY,
                GlowPreset.WHISPER.width ?: DEFAULT_WIDTH,
                try {
                    Color.decode(accentHex)
                } catch (_: NumberFormatException) {
                    Color.decode(FALLBACK_COLOR_HEX)
                },
                true,
            )
            glowPreview = preview
            panel.row {
                cell(preview).align(Align.FILL)
            }
        }
    }

    private fun buildStyleGroup(
        panel: Panel,
        licensed: Boolean,
    ) {
        panel.group("Style") {
            buildPresetRow(this)
            buildStyleComboRow(this, licensed)
            buildSliderRow(
                group = this,
                config =
                    SliderConfig(
                        label = "Intensity",
                        min = 0,
                        max = MAX_INTENSITY,
                        initialValue = pendingIntensity[pendingStyle] ?: DEFAULT_INTENSITY,
                        majorTick = INTENSITY_MAJOR_TICK,
                        minorTick = INTENSITY_MINOR_TICK,
                    ),
                onValueChanged = { pendingIntensity[pendingStyle] = it },
                onCreated = { slider, label ->
                    intensitySlider = slider
                    intensityValueLabel = label
                },
            )
            buildSliderRow(
                group = this,
                config =
                    SliderConfig(
                        label = "Width (px)",
                        min = MIN_WIDTH,
                        max = MAX_WIDTH,
                        initialValue = pendingWidth[pendingStyle] ?: DEFAULT_WIDTH,
                        majorTick = WIDTH_MAJOR_TICK,
                    ),
                onValueChanged = { pendingWidth[pendingStyle] = it },
                onCreated = { slider, label ->
                    widthSlider = slider
                    widthValueLabel = label
                },
            )
            buildAnimationRows(this, licensed)
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
            segmented.selectedItem = pendingPreset
            @Suppress("UnstableApiUsage")
            segmented.whenItemSelected { preset ->
                if (!suppressListeners) {
                    pendingPreset = preset
                    if (preset != GlowPreset.CUSTOM) {
                        applyPresetValues(preset)
                        refreshSliders()
                        refreshStyleAndAnimation()
                    }
                    customModeVisible.set(preset == GlowPreset.CUSTOM)
                    updateControlStates()
                }
            }
            presetSegmentedButton = segmented
        }
    }

    private fun buildStyleComboRow(
        group: Panel,
        licensed: Boolean,
    ) {
        group
            .row {
                label("Style")
                val model = DefaultComboBoxModel(GlowStyle.entries.map { it.displayName }.toTypedArray())
                val combo = ComboBox(model)
                combo.selectedItem = pendingStyle.displayName
                combo.isEnabled = licensed && pendingGlowEnabled
                combo.addActionListener {
                    if (!suppressListeners) {
                        val selectedName = combo.selectedItem as? String ?: return@addActionListener
                        val style = GlowStyle.entries.first { it.displayName == selectedName }
                        pendingStyle = style
                        switchToCustom()
                        refreshSliders()
                    }
                }
                styleCombo = combo
                cell(combo)
            }.visibleIf(customModeVisible)
    }

    private fun buildSliderRow(
        group: Panel,
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
                val valueLabel = JLabel("${slider.value}")
                slider.addChangeListener {
                    if (!suppressListeners) {
                        onValueChanged(slider.value)
                        switchToCustom()
                    }
                    valueLabel.text = "${slider.value}"
                }
                onCreated(slider, valueLabel)
                cell(slider).resizableColumn().align(Align.FILL)
                cell(valueLabel)
            }.visibleIf(customModeVisible)
    }

    private fun buildAnimationRows(
        group: Panel,
        licensed: Boolean,
    ) {
        group
            .row {
                label("Animation")
                val model = DefaultComboBoxModel(GlowAnimation.entries.map { it.displayName }.toTypedArray())
                val combo = ComboBox(model)
                combo.selectedItem = pendingAnimation.displayName
                combo.isEnabled = licensed && pendingGlowEnabled
                combo.addActionListener {
                    if (!suppressListeners) {
                        val selectedName = combo.selectedItem as? String ?: return@addActionListener
                        pendingAnimation = GlowAnimation.entries.first { it.displayName == selectedName }
                        switchToCustom()
                        updateAnimationDescription()
                    }
                }
                animationCombo = combo
                cell(combo)
            }.visibleIf(customModeVisible)
        group
            .row {
                val descLabel = JLabel(animationDescription(pendingAnimation))
                descLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                descLabel.font = JBUI.Fonts.smallFont()
                animationDescriptionLabel = descLabel
                cell(descLabel)
            }.visibleIf(customModeVisible)
    }

    private fun buildTargetsGroup(
        panel: Panel,
        licensed: Boolean,
    ) {
        panel.group("Targets") {
            row {
                link("Enable all") {
                    for (key in pendingIslandToggles.keys) {
                        pendingIslandToggles[key] = true
                    }
                    refreshIslandCheckboxes()
                }.enabled(licensed && pendingGlowEnabled)
                link("Disable all") {
                    for (key in pendingIslandToggles.keys) {
                        pendingIslandToggles[key] = false
                    }
                    refreshIslandCheckboxes()
                }.enabled(licensed && pendingGlowEnabled)
            }

            buildIslandCheckboxRow(this, "Editor", licensed)

            row { label("Sidebar").bold() }
            buildIslandCheckboxRow(this, "Project", licensed)

            row { label("Panel").bold() }
            twoColumnsRow(
                {
                    panel {
                        buildIslandCheckboxRow(this, "Terminal", licensed)
                        buildIslandCheckboxRow(this, "Run", licensed)
                        buildIslandCheckboxRow(this, "Debug", licensed)
                    }
                },
                {
                    panel {
                        buildIslandCheckboxRow(this, "Git", licensed)
                        buildIslandCheckboxRow(this, "Services", licensed)
                    }
                },
            )
        }
    }

    private fun buildExtrasGroup(
        panel: Panel,
        licensed: Boolean,
    ) {
        panel.group("Extras") {
            row {
                label("Active tab")
                val model = DefaultComboBoxModel(GlowTabMode.entries.map { it.displayName }.toTypedArray())
                val combo = ComboBox(model)
                combo.selectedItem = GlowTabMode.fromName(pendingTabMode).displayName
                combo.isEnabled = licensed && pendingGlowEnabled
                combo.addActionListener {
                    if (!suppressListeners) {
                        val selectedName = combo.selectedItem as? String ?: return@addActionListener
                        pendingTabMode = GlowTabMode.entries.first { it.displayName == selectedName }.name
                    }
                }
                tabModeCombo = combo
                cell(combo)
            }
            row {
                val cb = checkBox("Focused input glow ring")
                cb.component.isSelected = pendingFocusRing
                cb.component.isEnabled = licensed && pendingGlowEnabled
                cb.component.addActionListener {
                    pendingFocusRing = cb.component.isSelected
                }
                focusRingCheckbox = cb.component
                cb.comment("Subtle glow around focused text fields")
            }
            row {
                val cb = checkBox("Glow on floating panels")
                cb.component.isSelected = pendingFloatingPanels
                cb.component.isEnabled = licensed && pendingGlowEnabled
                cb.component.addActionListener {
                    pendingFloatingPanels = cb.component.isSelected
                }
                floatingCheckbox = cb.component
                cb.comment("Apply glow to undocked/floating tool windows")
            }
        }
    }

    private fun buildIslandCheckboxRow(
        panel: Panel,
        islandId: String,
        licensed: Boolean,
    ) {
        panel.row {
            val cb = checkBox(islandId)
            cb.component.isSelected = pendingIslandToggles[islandId] ?: false
            cb.component.isEnabled = licensed && pendingGlowEnabled
            cb.component.addActionListener {
                pendingIslandToggles[islandId] = cb.component.isSelected
            }
            islandCheckboxes[islandId] = cb.component
        }
    }

    // Preset helpers

    private fun applyPresetValues(preset: GlowPreset) {
        val style = preset.style ?: return
        val intensity = preset.intensity ?: return
        val width = preset.width ?: return
        val animation = preset.animation ?: return

        pendingStyle = style
        pendingIntensity[style] = intensity
        pendingWidth[style] = width
        pendingAnimation = animation
    }

    private fun switchToCustom() {
        if (pendingPreset != GlowPreset.CUSTOM) {
            pendingPreset = GlowPreset.CUSTOM
            customModeVisible.set(true)
            suppressListeners = true
            presetSegmentedButton?.selectedItem = GlowPreset.CUSTOM
            suppressListeners = false
        }
    }

    private fun refreshSliders() {
        suppressListeners = true
        val intensity = pendingIntensity[pendingStyle] ?: pendingStyle.defaultIntensity
        val width = pendingWidth[pendingStyle] ?: pendingStyle.defaultWidth
        intensitySlider?.value = intensity
        widthSlider?.value = width
        intensityValueLabel?.text = "$intensity"
        widthValueLabel?.text = "$width"
        suppressListeners = false
    }

    private fun refreshStyleAndAnimation() {
        suppressListeners = true
        styleCombo?.selectedItem = pendingStyle.displayName
        animationCombo?.selectedItem = pendingAnimation.displayName
        updateAnimationDescription()
        suppressListeners = false
    }

    private fun refreshIslandCheckboxes() {
        for ((id, cb) in islandCheckboxes) {
            cb.isSelected = pendingIslandToggles[id] ?: false
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
        animationDescriptionLabel?.text = animationDescription(pendingAnimation)
    }

    // State management

    private fun migratePresetIfNeeded(state: AyuIslandsState): String {
        if (state.glowPreset != null) return state.glowPreset!!
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        val intensity = state.getIntensityForStyle(style)
        val width = state.getWidthForStyle(style)
        val animation = GlowAnimation.fromName(state.glowAnimation ?: GlowAnimation.NONE.name)
        return GlowPreset.detect(style, intensity, width, animation).name
    }

    private fun loadStateIntoPending(
        state: AyuIslandsState,
        presetName: String = state.glowPreset ?: GlowPreset.WHISPER.name,
    ) {
        pendingGlowEnabled = state.glowEnabled
        pendingPreset = GlowPreset.fromName(presetName)
        pendingStyle = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        pendingAnimation = GlowAnimation.fromName(state.glowAnimation ?: GlowAnimation.NONE.name)

        pendingIntensity.clear()
        pendingWidth.clear()
        for (style in GlowStyle.entries) {
            pendingIntensity[style] = state.getIntensityForStyle(style)
            pendingWidth[style] = state.getWidthForStyle(style)
        }

        pendingIslandToggles.clear()
        val islandIds = listOf("Editor", "Project", "Terminal", "Run", "Debug", "Git", "Services")
        for (id in islandIds) {
            pendingIslandToggles[id] = state.isIslandEnabled(id)
        }

        pendingTabMode = state.glowTabMode ?: "UNDERLINE"
        pendingFocusRing = state.glowFocusRing
        pendingFloatingPanels = state.glowFloatingPanels

        customModeVisible.set(pendingPreset == GlowPreset.CUSTOM)
    }

    private fun copyPendingToStored() {
        storedGlowEnabled = pendingGlowEnabled
        storedPreset = pendingPreset
        storedStyle = pendingStyle
        storedIntensity = pendingIntensity.toMap()
        storedWidth = pendingWidth.toMap()
        storedAnimation = pendingAnimation
        storedIslandToggles = pendingIslandToggles.toMap()
        storedTabMode = pendingTabMode
        storedFocusRing = pendingFocusRing
        storedFloatingPanels = pendingFloatingPanels
    }

    private fun updateControlStates() {
        val enabled = pendingGlowEnabled && LicenseChecker.isLicensedOrGrace()

        intensitySlider?.isEnabled = enabled
        widthSlider?.isEnabled = enabled
        focusRingCheckbox?.isEnabled = enabled
        floatingCheckbox?.isEnabled = enabled
        styleCombo?.isEnabled = enabled
        animationCombo?.isEnabled = enabled
        tabModeCombo?.isEnabled = enabled
        presetSegmentedButton?.enabled(enabled)

        for ((_, cb) in islandCheckboxes) {
            cb.isEnabled = enabled
        }
    }

    private fun refreshAllControls() {
        suppressListeners = true
        presetSegmentedButton?.selectedItem = pendingPreset
        customModeVisible.set(pendingPreset == GlowPreset.CUSTOM)
        styleCombo?.selectedItem = pendingStyle.displayName
        animationCombo?.selectedItem = pendingAnimation.displayName
        tabModeCombo?.selectedItem = GlowTabMode.fromName(pendingTabMode).displayName
        refreshSliders()
        refreshIslandCheckboxes()
        masterToggle?.isSelected = pendingGlowEnabled
        focusRingCheckbox?.isSelected = pendingFocusRing
        floatingCheckbox?.isSelected = pendingFloatingPanels
        updateAnimationDescription()
        suppressListeners = false
        updateControlStates()
    }

    // AyuIslandsSettingsPanel contract

    override fun isModified(): Boolean =
        pendingGlowEnabled != storedGlowEnabled ||
            pendingPreset != storedPreset ||
            pendingStyle != storedStyle ||
            pendingIntensity != storedIntensity ||
            pendingWidth != storedWidth ||
            pendingAnimation != storedAnimation ||
            pendingIslandToggles != storedIslandToggles ||
            pendingTabMode != storedTabMode ||
            pendingFocusRing != storedFocusRing ||
            pendingFloatingPanels != storedFloatingPanels

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state

        state.glowEnabled = pendingGlowEnabled
        state.glowPreset = pendingPreset.name

        // Write-through: preset values → raw state fields (GlowOverlayManager reads raw fields)
        if (pendingPreset != GlowPreset.CUSTOM) {
            applyPresetValues(pendingPreset)
        }

        state.glowStyle = pendingStyle.name
        state.glowAnimation = pendingAnimation.name

        for (style in GlowStyle.entries) {
            state.setIntensityForStyle(style, pendingIntensity[style] ?: style.defaultIntensity)
            state.setWidthForStyle(style, pendingWidth[style] ?: style.defaultWidth)
        }

        val islandIds = listOf("Editor", "Project", "Terminal", "Run", "Debug", "Git", "Services")
        for (id in islandIds) {
            state.setIslandEnabled(id, pendingIslandToggles[id] ?: false)
        }

        state.glowTabMode = pendingTabMode
        state.glowFocusRing = pendingFocusRing
        state.glowFloatingPanels = pendingFloatingPanels

        copyPendingToStored()
    }

    override fun reset() {
        pendingGlowEnabled = storedGlowEnabled
        pendingPreset = storedPreset
        pendingStyle = storedStyle
        pendingIntensity = storedIntensity.toMutableMap()
        pendingWidth = storedWidth.toMutableMap()
        pendingAnimation = storedAnimation
        pendingIslandToggles = storedIslandToggles.toMutableMap()
        pendingTabMode = storedTabMode
        pendingFocusRing = storedFocusRing
        pendingFloatingPanels = storedFloatingPanels

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
        private const val PREVIEW_WIDTH = 300
        private const val PREVIEW_HEIGHT = 60
    }
}
