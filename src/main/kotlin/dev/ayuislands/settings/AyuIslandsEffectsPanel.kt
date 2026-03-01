package dev.ayuislands.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.licensing.LicenseChecker
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JSlider

/**
 * Glow effects configuration split across Glow and Advanced tabs.
 *
 * [buildGlowPanel] renders the master toggle, style/animation ComboBoxes, and sliders.
 * [buildAdvancedPanel] renders targets, tab mode, focus ring, and floating panels.
 * Both are called from [AyuIslandsConfigurable] into separate tab panes.
 */
class AyuIslandsEffectsPanel : AyuIslandsSettingsPanel() {

    // Pending state (applied on OK/Apply, not live)
    private var pendingGlowEnabled: Boolean = false
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
    private var storedStyle: GlowStyle = GlowStyle.SOFT
    private var storedIntensity: Map<GlowStyle, Int> = emptyMap()
    private var storedWidth: Map<GlowStyle, Int> = emptyMap()
    private var storedAnimation: GlowAnimation = GlowAnimation.NONE
    private var storedIslandToggles: Map<String, Boolean> = emptyMap()
    private var storedTabMode: String = "UNDERLINE"
    private var storedFocusRing: Boolean = true
    private var storedFloatingPanels: Boolean = false

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

    // Suppress listener events during programmatic updates
    private var suppressListeners = false
    private var stateLoaded = false

    /** Not used — Glow and Advanced panels are built via dedicated methods. */
    override fun buildPanel(panel: Panel, variant: AyuVariant) {
        // no-op: use buildGlowPanel / buildAdvancedPanel instead
    }

    private fun ensureStateLoaded() {
        if (stateLoaded) return
        val state = AyuIslandsSettings.getInstance().state
        loadStateIntoPending(state)
        copyPendingToStored()
        stateLoaded = true
    }

    // Glow tab content

    fun buildGlowPanel(panel: Panel, variant: AyuVariant) {
        ensureStateLoaded()
        val licensed = LicenseChecker.isLicensedOrGrace()

        panel.row {
            comment("Neon glow effects around editor islands and UI elements.")
        }

        // Master toggle + Pro link
        panel.row {
            val cb = checkBox("Enable Glow")
            cb.component.isSelected = pendingGlowEnabled
            cb.component.isEnabled = licensed
            cb.component.addActionListener {
                pendingGlowEnabled = cb.component.isSelected
                updateControlStates()
            }
            masterToggle = cb.component

            if (!licensed) {
                link("Get Ayu Islands Pro") {
                    LicenseChecker.requestLicense(
                        "Unlock glow effects and custom accent colors"
                    )
                }
            }

            // Reset defaults link (right-aligned)
            link("Reset defaults") {
                pendingIntensity[pendingStyle] = pendingStyle.defaultIntensity
                pendingWidth[pendingStyle] = pendingStyle.defaultWidth
                refreshSliders()
            }
        }

        // Style, sliders, and animation
        panel.group("Style") {
            // Style ComboBox row
            row {
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
                        refreshSliders()
                    }
                }
                styleCombo = combo
                cell(combo)
            }

            // Intensity slider with numeric value
            row {
                label("Intensity")
                val slider = JSlider(0, 100, pendingIntensity[pendingStyle] ?: 40)
                slider.paintTicks = true
                slider.majorTickSpacing = 25
                slider.minorTickSpacing = 5
                val valueLabel = JLabel("${slider.value}")
                slider.addChangeListener {
                    if (!suppressListeners) {
                        pendingIntensity[pendingStyle] = slider.value
                    }
                    valueLabel.text = "${slider.value}"
                }
                intensitySlider = slider
                intensityValueLabel = valueLabel
                cell(slider).resizableColumn().align(Align.FILL)
                cell(valueLabel)
            }

            // Width slider with numeric value
            row {
                label("Width (px)")
                val slider = JSlider(4, 32, pendingWidth[pendingStyle] ?: 10)
                slider.paintTicks = true
                slider.majorTickSpacing = 7
                slider.minorTickSpacing = 1
                val valueLabel = JLabel("${slider.value}")
                slider.addChangeListener {
                    if (!suppressListeners) {
                        pendingWidth[pendingStyle] = slider.value
                    }
                    valueLabel.text = "${slider.value}"
                }
                widthSlider = slider
                widthValueLabel = valueLabel
                cell(slider).resizableColumn().align(Align.FILL)
                cell(valueLabel)
            }

            // Animation ComboBox
            row {
                label("Animation")
                val model = DefaultComboBoxModel(GlowAnimation.entries.map { it.displayName }.toTypedArray())
                val combo = ComboBox(model)
                combo.selectedItem = pendingAnimation.displayName
                combo.isEnabled = licensed && pendingGlowEnabled
                combo.addActionListener {
                    if (!suppressListeners) {
                        val selectedName = combo.selectedItem as? String ?: return@addActionListener
                        pendingAnimation = GlowAnimation.entries.first { it.displayName == selectedName }
                        updateAnimationDescription()
                    }
                }
                animationCombo = combo
                cell(combo)
            }
            row {
                val descLabel = JLabel(animationDescription(pendingAnimation))
                descLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                descLabel.font = JBUI.Fonts.smallFont()
                animationDescriptionLabel = descLabel
                cell(descLabel)
            }
        }

        updateControlStates()
    }

    // Advanced tab content

    fun buildAdvancedPanel(panel: Panel, variant: AyuVariant) {
        ensureStateLoaded()
        val licensed = LicenseChecker.isLicensedOrGrace()

        panel.row {
            comment("Fine-tune glow targets and additional effects.")

            if (!licensed) {
                link("Get Ayu Islands Pro") {
                    LicenseChecker.requestLicense(
                        "Unlock glow effects and custom accent colors"
                    )
                }
            }
        }

        // Glow targets
        val groups = listOf(
            "Editor Tools" to listOf("Editor"),
            "Navigation" to listOf("Project"),
            "Build & Run" to listOf("Run", "Debug", "Terminal"),
            "Version Control" to listOf("Git", "Services"),
        )

        panel.group("Glow Targets") {
            // Enable All / Disable All links in header row
            row {
                link("Enable All") {
                    for (key in pendingIslandToggles.keys) {
                        pendingIslandToggles[key] = true
                    }
                    refreshIslandCheckboxes()
                }.enabled(licensed && pendingGlowEnabled)
                link("Disable All") {
                    for (key in pendingIslandToggles.keys) {
                        pendingIslandToggles[key] = false
                    }
                    refreshIslandCheckboxes()
                }.enabled(licensed && pendingGlowEnabled)
            }

            twoColumnsRow(
                {
                    panel {
                        for ((groupName, islands) in groups.take(2)) {
                            row { label(groupName).bold() }
                            for (islandId in islands) {
                                buildIslandCheckboxRow(this, islandId, licensed)
                            }
                        }
                    }
                },
                {
                    panel {
                        for ((groupName, islands) in groups.drop(2)) {
                            row { label(groupName).bold() }
                            for (islandId in islands) {
                                buildIslandCheckboxRow(this, islandId, licensed)
                            }
                        }
                    }
                },
            )
        }

        // Active Tab Glow
        panel.row {
            label("Active Tab")
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

        // Focus ring
        panel.row {
            val cb = checkBox("Focused input glow ring")
            cb.component.isSelected = pendingFocusRing
            cb.component.isEnabled = licensed && pendingGlowEnabled
            cb.component.addActionListener {
                pendingFocusRing = cb.component.isSelected
            }
            focusRingCheckbox = cb.component
            cb.comment("Subtle glow around focused text fields")
        }

        // Floating panels
        panel.row {
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

    private fun buildIslandCheckboxRow(panel: Panel, islandId: String, licensed: Boolean) {
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

    // Style selection

    private fun selectStyle(style: GlowStyle) {
        pendingStyle = style
        suppressListeners = true
        styleCombo?.selectedItem = style.displayName
        suppressListeners = false
        refreshSliders()
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

    private fun refreshIslandCheckboxes() {
        for ((id, cb) in islandCheckboxes) {
            cb.isSelected = pendingIslandToggles[id] ?: false
        }
    }

    // Animation

    private fun animationDescription(animation: GlowAnimation): String = when (animation) {
        GlowAnimation.NONE -> "Static glow with no animation."
        GlowAnimation.PULSE -> "Sharp rhythmic brightening every 2 seconds."
        GlowAnimation.BREATHE -> "Slow sinusoidal swell over 4 seconds."
        GlowAnimation.REACTIVE -> "Responds to typing and IDE actions."
    }

    private fun updateAnimationDescription() {
        animationDescriptionLabel?.text = animationDescription(pendingAnimation)
    }

    // State management

    private fun loadStateIntoPending(state: AyuIslandsState) {
        pendingGlowEnabled = state.glowEnabled
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
    }

    private fun copyPendingToStored() {
        storedGlowEnabled = pendingGlowEnabled
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

        for ((_, cb) in islandCheckboxes) {
            cb.isEnabled = enabled
        }
    }

    private fun refreshAllControls() {
        suppressListeners = true
        styleCombo?.selectedItem = pendingStyle.displayName
        animationCombo?.selectedItem = pendingAnimation.displayName
        tabModeCombo?.selectedItem = GlowTabMode.fromName(pendingTabMode).displayName
        refreshSliders()
        refreshIslandCheckboxes()
        masterToggle?.isSelected = pendingGlowEnabled
        focusRingCheckbox?.isSelected = pendingFocusRing
        floatingCheckbox?.isSelected = pendingFloatingPanels
        suppressListeners = false
        updateControlStates()
    }

    // AyuIslandsSettingsPanel contract

    override fun isModified(): Boolean {
        if (pendingGlowEnabled != storedGlowEnabled) return true
        if (pendingStyle != storedStyle) return true
        if (pendingIntensity != storedIntensity) return true
        if (pendingWidth != storedWidth) return true
        if (pendingAnimation != storedAnimation) return true
        if (pendingIslandToggles != storedIslandToggles) return true
        if (pendingTabMode != storedTabMode) return true
        if (pendingFocusRing != storedFocusRing) return true
        if (pendingFloatingPanels != storedFloatingPanels) return true
        return false
    }

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state

        state.glowEnabled = pendingGlowEnabled
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

        if (pendingGlowEnabled) {
            state.glowOnboardingShown = true
        }

        copyPendingToStored()
    }

    override fun reset() {
        pendingGlowEnabled = storedGlowEnabled
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

}
