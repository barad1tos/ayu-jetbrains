package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.licensing.LicenseChecker
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JSlider
import com.intellij.ui.dsl.builder.panel as dslPanel

/**
 * Glow effects configuration rendered in a single Glow tab.
 *
 * [buildGlowPanel] renders the master toggle, preset selection, style/animation controls,
 * sliders, glow targets, tab mode, focus ring, and floating panels.
 * Called from [AyuIslandsConfigurable] into a single tab pane.
 */
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
    private var presetButtonBar: PresetButtonBar? = null
    private var glowGroupPanel: GlowGroupPanel? = null

    // Suppress listener events during programmatic updates
    private var suppressListeners = false
    private var stateLoaded = false

    /** Not used — the glow panel is built via [buildGlowPanel]. */
    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) { /* Glow panel is built via buildGlowPanel instead */ }

    // Glow tab content

    fun buildGlowPanel(panel: Panel) {
        if (!stateLoaded) {
            val state = AyuIslandsSettings.getInstance().state
            loadStateIntoPending(state)
            copyPendingToStored()
            stateLoaded = true
        }
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
                updateGlowGroupPanel()
            }
            masterToggle = cb.component

            if (!licensed) {
                @Suppress("DialogTitleCapitalization")
                link("Get Ayu Islands Pro") {
                    LicenseChecker.requestLicense(
                        "Unlock glow effects and custom accent colors",
                    )
                }
            }

            link("Reset defaults") {
                applyPresetValues(GlowPreset.WHISPER)
                pendingPreset = GlowPreset.WHISPER
                refreshAllControls()
            }
        }
    }

    private fun buildStyleGroup(
        panel: Panel,
        licensed: Boolean,
    ) {
        val glowPanel = GlowGroupPanel()
        glowGroupPanel = glowPanel

        val innerContent =
            dslPanel {
                group("Style") {
                    buildPresetRow(this)
                    buildStyleComboRow(this, licensed)
                    buildSliderRow(
                        "Intensity",
                        0..MAX_INTENSITY,
                        INTENSITY_MAJOR_TICK to INTENSITY_MINOR_TICK,
                        pendingIntensity[pendingStyle] ?: DEFAULT_INTENSITY,
                    ) { slider, valueLabel ->
                        intensitySlider = slider
                        intensityValueLabel = valueLabel
                        { pendingIntensity[pendingStyle] = slider.value }
                    }
                    buildSliderRow(
                        "Width (px)",
                        MIN_WIDTH..MAX_WIDTH,
                        WIDTH_MAJOR_TICK to 0,
                        pendingWidth[pendingStyle] ?: DEFAULT_WIDTH,
                    ) { slider, valueLabel ->
                        widthSlider = slider
                        widthValueLabel = valueLabel
                        { pendingWidth[pendingStyle] = slider.value }
                    }
                    buildAnimationRows(this, licensed)
                }
            }

        glowPanel.add(innerContent, BorderLayout.CENTER)
        updateGlowGroupPanel()

        panel.row {
            cell(glowPanel)
                .resizableColumn()
                .align(Align.FILL)
        }
    }

    private fun buildPresetRow(group: Panel) {
        group.row {
            label("Preset")
            val bar =
                PresetButtonBar(
                    onPresetSelected = { preset ->
                        if (!suppressListeners) {
                            pendingPreset = preset
                            if (preset != GlowPreset.CUSTOM) {
                                applyPresetValues(preset)
                                refreshSlidersAndCombos()
                            }
                            customModeVisible.set(preset == GlowPreset.CUSTOM)
                            updateGlowGroupPanel()
                            updateControlStates()
                        }
                    },
                    onPresetWithAnimation = { preset, animation ->
                        if (!suppressListeners) {
                            pendingPreset = preset
                            applyPresetValues(preset)
                            pendingAnimation = animation
                            refreshSlidersAndCombos()
                            customModeVisible.set(false)
                            updateGlowGroupPanel()
                            updateControlStates()
                        }
                    },
                )
            bar.selectedPreset = pendingPreset
            presetButtonBar = bar
            cell(bar)
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
                        refreshSlidersAndCombos()
                    }
                }
                styleCombo = combo
                cell(combo)
            }.visibleIf(customModeVisible)
    }

    private fun Panel.buildSliderRow(
        labelText: String,
        range: IntRange,
        tickSpacing: Pair<Int, Int>,
        initial: Int,
        bind: (JSlider, JLabel) -> () -> Unit,
    ) {
        val (majorTick, minorTick) = tickSpacing
        row {
            label(labelText)
            val slider = JSlider(range.first, range.last, initial)
            slider.paintTicks = true
            slider.majorTickSpacing = majorTick
            if (minorTick > 0) slider.minorTickSpacing = minorTick
            val valueLabel = JLabel("${slider.value}")
            val onChanged = bind(slider, valueLabel)
            slider.addChangeListener {
                if (!suppressListeners) {
                    onChanged()
                    switchToCustom()
                }
                valueLabel.text = "${slider.value}"
            }
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
                        animationDescriptionLabel?.text = animationDescription(pendingAnimation)
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
                    for ((id, cb) in islandCheckboxes) cb.isSelected = pendingIslandToggles[id] ?: false
                }.enabled(licensed && pendingGlowEnabled)
                link("Disable all") {
                    for (key in pendingIslandToggles.keys) {
                        pendingIslandToggles[key] = false
                    }
                    for ((id, cb) in islandCheckboxes) cb.isSelected = pendingIslandToggles[id] ?: false
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
            buildToggleRow(
                "Focused input glow ring" to "Subtle glow around focused text fields",
                licensed,
                pendingFocusRing,
                { v -> pendingFocusRing = v },
            ) { focusRingCheckbox = it }
            buildToggleRow(
                "Glow on floating panels" to "Apply glow to undocked/floating tool windows",
                licensed,
                pendingFloatingPanels,
                { v -> pendingFloatingPanels = v },
            ) { floatingCheckbox = it }
        }
    }

    private fun Panel.buildToggleRow(
        labelAndComment: Pair<String, String>,
        licensed: Boolean,
        initialValue: Boolean,
        onChanged: (Boolean) -> Unit,
        bindCheckbox: (JCheckBox) -> Unit,
    ) {
        val (label, comment) = labelAndComment
        row {
            val cb = checkBox(label)
            cb.component.isSelected = initialValue
            cb.component.isEnabled = licensed && pendingGlowEnabled
            cb.component.addActionListener { onChanged(cb.component.isSelected) }
            bindCheckbox(cb.component)
            cb.comment(comment)
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
            presetButtonBar?.selectedPreset = GlowPreset.CUSTOM
            suppressListeners = false
            updateGlowGroupPanel()
        }
    }

    private fun updateGlowGroupPanel() {
        val panel = glowGroupPanel ?: return
        val style = pendingStyle
        val intensity = pendingIntensity[style] ?: style.defaultIntensity
        val width = pendingWidth[style] ?: style.defaultWidth
        val color = resolveAccentColor()

        panel.updateFromPreset(
            style = style,
            intensity = intensity,
            width = width,
            color = color,
            visible = pendingGlowEnabled,
        )
    }

    private fun resolveAccentColor(): Color {
        val variant = AyuVariant.detect()
        val settings = AyuIslandsSettings.getInstance()
        val hex = if (variant != null) settings.getAccentForVariant(variant) else DEFAULT_ACCENT_HEX
        return try {
            Color.decode(hex)
        } catch (_: NumberFormatException) {
            Color.decode(DEFAULT_ACCENT_HEX)
        }
    }

    private fun refreshSlidersAndCombos() {
        suppressListeners = true
        val intensity = pendingIntensity[pendingStyle] ?: pendingStyle.defaultIntensity
        val width = pendingWidth[pendingStyle] ?: pendingStyle.defaultWidth
        intensitySlider?.value = intensity
        widthSlider?.value = width
        intensityValueLabel?.text = "$intensity"
        widthValueLabel?.text = "$width"
        styleCombo?.selectedItem = pendingStyle.displayName
        animationCombo?.selectedItem = pendingAnimation.displayName
        animationDescriptionLabel?.text = animationDescription(pendingAnimation)
        suppressListeners = false
    }

    // State management

    private fun loadStateIntoPending(state: AyuIslandsState) {
        pendingGlowEnabled = state.glowEnabled
        pendingStyle = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        pendingPreset =
            if (state.glowPreset != null) {
                GlowPreset.fromName(state.glowPreset!!)
            } else {
                val intensity = state.getIntensityForStyle(pendingStyle)
                val width = state.getWidthForStyle(pendingStyle)
                val animation = GlowAnimation.fromName(state.glowAnimation ?: GlowAnimation.NONE.name)
                GlowPreset.detect(pendingStyle, intensity, width, animation)
            }
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

        listOfNotNull(
            intensitySlider,
            widthSlider,
            focusRingCheckbox,
            floatingCheckbox,
            styleCombo,
            animationCombo,
            tabModeCombo,
        ).forEach { it.isEnabled = enabled }
        presetButtonBar?.setAllEnabled(enabled)
        islandCheckboxes.values.forEach { it.isEnabled = enabled }
    }

    private fun refreshAllControls() {
        suppressListeners = true
        presetButtonBar?.selectedPreset = pendingPreset
        customModeVisible.set(pendingPreset == GlowPreset.CUSTOM)
        tabModeCombo?.selectedItem = GlowTabMode.fromName(pendingTabMode).displayName
        refreshSlidersAndCombos()
        for ((id, cb) in islandCheckboxes) cb.isSelected = pendingIslandToggles[id] ?: false
        masterToggle?.isSelected = pendingGlowEnabled
        focusRingCheckbox?.isSelected = pendingFocusRing
        floatingCheckbox?.isSelected = pendingFloatingPanels
        suppressListeners = false
        updateControlStates()
        updateGlowGroupPanel()
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
        private const val DEFAULT_INTENSITY = 35
        private const val INTENSITY_MAJOR_TICK = 25
        private const val INTENSITY_MINOR_TICK = 5
        private const val MIN_WIDTH = 2
        private const val MAX_WIDTH = 16
        private const val DEFAULT_WIDTH = 8
        private const val WIDTH_MAJOR_TICK = 2
        private const val DEFAULT_ACCENT_HEX = "#FFCC66"

        private fun animationDescription(animation: GlowAnimation): String =
            when (animation) {
                GlowAnimation.NONE -> "Static glow with no animation."
                GlowAnimation.PULSE -> "Sharp rhythmic brightening every 2 seconds."
                GlowAnimation.BREATHE -> "Slow sinusoidal swell over 4 seconds."
                GlowAnimation.REACTIVE -> "Responds to typing and IDE actions."
            }
    }
}
