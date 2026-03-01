package dev.ayuislands.settings

import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowAnimator
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.licensing.LicenseChecker
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Hashtable
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JTabbedPane
import javax.swing.UIManager

/**
 * Tabbed effects panel with Style, Targets, Animation, and Presets tabs.
 *
 * Replaces the single glow checkbox with a rich configuration UI for
 * glow appearance, target panels, animation effects, and saved presets.
 */
class AyuIslandsEffectsPanel : AyuIslandsSettingsPanel() {

    // Pending state (applied on OK/Apply, not live)
    private var pendingGlowEnabled: Boolean = false
    private var pendingStyle: GlowStyle = GlowStyle.SOFT
    private var pendingIntensity: MutableMap<GlowStyle, Int> = mutableMapOf()
    private var pendingWidth: MutableMap<GlowStyle, Int> = mutableMapOf()
    private var pendingAnimation: GlowAnimation = GlowAnimation.NONE
    private var pendingIslandToggles: MutableMap<String, Boolean> = mutableMapOf()
    private var pendingUserPresets: MutableList<GlowPreset> = mutableListOf()
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
    private var storedUserPresets: List<GlowPreset> = emptyList()
    private var storedTabMode: String = "UNDERLINE"
    private var storedFocusRing: Boolean = true
    private var storedFloatingPanels: Boolean = false

    // UI components
    private var tabbedPane: JTabbedPane? = null
    private var masterToggle: JCheckBox? = null
    private var intensitySlider: JSlider? = null
    private var widthSlider: JSlider? = null
    private var animationCombo: JComboBox<String>? = null
    private var presetCombo: JComboBox<String>? = null
    private var saveButton: JButton? = null
    private var deleteButton: JButton? = null
    private var renameButton: JButton? = null
    private var duplicateWarning: JLabel? = null
    private val islandCheckboxes = mutableMapOf<String, JCheckBox>()
    private val styleSwatches = mutableMapOf<GlowStyle, StyleSwatchPanel>()
    private var animationDescriptionLabel: JLabel? = null

    // Preview animation
    private var previewAnimator: GlowAnimator? = null

    // Callbacks for cross-panel communication
    var onGlowChanged: (() -> Unit)? = null
    var onStyleChanged: (() -> Unit)? = null
    var onAnimationChanged: (() -> Unit)? = null

    // Suppress listener events during programmatic updates
    private var suppressListeners = false

    companion object {
        private const val ANIMATION_TAB_INDEX = 2
    }

    override fun buildPanel(panel: Panel, variant: AyuVariant) {
        val state = AyuIslandsSettings.getInstance().state
        val licensed = LicenseChecker.isLicensedOrGrace()

        loadStateIntoPending(state)
        copyPendingToStored()

        // Master glow toggle — always visible at top of Effects content
        panel.row {
            val cb = checkBox("Enable Glow")
            cb.component.isSelected = pendingGlowEnabled
            cb.component.isEnabled = licensed
            cb.component.addActionListener {
                val wasEnabled = pendingGlowEnabled
                pendingGlowEnabled = cb.component.isSelected

                // First-time enable: auto-apply Balanced preset
                if (!wasEnabled && pendingGlowEnabled && !storedGlowEnabled) {
                    val onboardingState = AyuIslandsSettings.getInstance().state
                    if (!onboardingState.glowOnboardingShown) {
                        loadPreset(GlowPreset.BALANCED)
                        onStyleChanged?.invoke()
                    }
                }

                updateControlStates()
                onGlowChanged?.invoke()
            }
            masterToggle = cb.component

            if (!licensed) {
                link("Get Ayu Islands Pro") {
                    LicenseChecker.requestLicense(
                        "Unlock glow effects and custom accent colors"
                    )
                }
            }
        }

        // Inner tabs for glow configuration
        val tabs = JTabbedPane()
        tabbedPane = tabs

        tabs.addTab("Style", buildStyleTab(licensed))
        tabs.addTab("Targets", buildTargetsTab(licensed))
        tabs.addTab("Animation", buildAnimationTab(licensed))
        tabs.addTab("Presets", buildPresetsTab(licensed))

        tabs.addChangeListener {
            if (tabs.selectedIndex != ANIMATION_TAB_INDEX) {
                stopAnimationPreview()
            }
            onStyleChanged?.invoke()
        }

        // Let layout manager determine size — do NOT set preferredSize.
        // IMPORTANT: Do NOT wrap in FlowLayout — it honors preferredSize and
        // reintroduces width constraints. Use DSL cell with FILL alignment instead.
        panel.row {
            cell(tabs)
                .resizableColumn()
                .align(Align.FILL)
        }

        updateControlStates()
    }

    // Style tab

    private fun buildStyleTab(licensed: Boolean): JPanel {
        val tabPanel = JPanel(BorderLayout(0, JBUI.scale(8)))
        tabPanel.border = BorderFactory.createEmptyBorder(
            JBUI.scale(8), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8)
        )

        // Style swatches row
        val swatchRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        for (style in GlowStyle.entries) {
            val swatch = StyleSwatchPanel(style, style == pendingStyle)
            swatch.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (!licensed || !pendingGlowEnabled) return
                    selectStyle(style)
                }
            })
            swatchRow.add(swatch)
            styleSwatches[style] = swatch
        }
        tabPanel.add(swatchRow, BorderLayout.NORTH)

        // Sliders panel
        val slidersPanel = JPanel()
        slidersPanel.layout = BoxLayout(slidersPanel, BoxLayout.Y_AXIS)

        // Intensity slider
        val intensityLabel = JLabel("Intensity")
        intensityLabel.alignmentX = 0f
        slidersPanel.add(intensityLabel)
        slidersPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val intensitySlider = JSlider(0, 100, pendingIntensity[pendingStyle] ?: 40)
        intensitySlider.paintTicks = true
        intensitySlider.paintLabels = true
        intensitySlider.majorTickSpacing = 50
        intensitySlider.minorTickSpacing = 10
        val intensityLabels = Hashtable<Int, JLabel>()
        intensityLabels[0] = JLabel("Low")
        intensityLabels[50] = JLabel("Med")
        intensityLabels[100] = JLabel("High")
        intensitySlider.labelTable = intensityLabels
        intensitySlider.addChangeListener {
            if (!suppressListeners) {
                pendingIntensity[pendingStyle] = intensitySlider.value
                onGlowChanged?.invoke()
            }
        }
        this.intensitySlider = intensitySlider
        intensitySlider.alignmentX = 0f
        slidersPanel.add(intensitySlider)
        slidersPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Width slider
        val widthLabel = JLabel("Width (px)")
        widthLabel.alignmentX = 0f
        slidersPanel.add(widthLabel)
        slidersPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val widthSlider = JSlider(4, 32, pendingWidth[pendingStyle] ?: 10)
        widthSlider.paintTicks = true
        widthSlider.paintLabels = true
        widthSlider.majorTickSpacing = 7
        widthSlider.minorTickSpacing = 1
        widthSlider.addChangeListener {
            if (!suppressListeners) {
                pendingWidth[pendingStyle] = widthSlider.value
                onGlowChanged?.invoke()
            }
        }
        this.widthSlider = widthSlider
        widthSlider.alignmentX = 0f
        slidersPanel.add(widthSlider)

        tabPanel.add(slidersPanel, BorderLayout.CENTER)

        // Reset button
        val resetPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        val resetButton = JButton("Reset Style Defaults")
        resetButton.addActionListener {
            pendingIntensity[pendingStyle] = pendingStyle.defaultIntensity
            pendingWidth[pendingStyle] = pendingStyle.defaultWidth
            refreshSliders()
            onGlowChanged?.invoke()
        }
        resetPanel.add(resetButton)
        tabPanel.add(resetPanel, BorderLayout.SOUTH)

        return tabPanel
    }

    private fun selectStyle(style: GlowStyle) {
        pendingStyle = style
        for ((swatchStyle, swatch) in styleSwatches) {
            swatch.selected = swatchStyle == style
            swatch.repaint()
        }
        refreshSliders()
        onStyleChanged?.invoke()
    }

    private fun refreshSliders() {
        suppressListeners = true
        intensitySlider?.value = pendingIntensity[pendingStyle] ?: pendingStyle.defaultIntensity
        widthSlider?.value = pendingWidth[pendingStyle] ?: pendingStyle.defaultWidth
        suppressListeners = false
    }

    // Targets tab

    private fun buildTargetsTab(licensed: Boolean): JPanel {
        val tabPanel = JPanel()
        tabPanel.layout = BoxLayout(tabPanel, BoxLayout.Y_AXIS)
        tabPanel.border = BorderFactory.createEmptyBorder(
            JBUI.scale(8), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8)
        )

        // Island groups
        val groups = listOf(
            "Editor Tools" to listOf("Editor"),
            "Navigation" to listOf("Project"),
            "Build & Run" to listOf("Run", "Debug", "Terminal"),
            "Version Control" to listOf("Git", "Services"),
        )

        for ((groupName, islands) in groups) {
            val groupLabel = JLabel(groupName)
            groupLabel.font = groupLabel.font.deriveFont(java.awt.Font.BOLD)
            groupLabel.alignmentX = 0f
            tabPanel.add(groupLabel)
            tabPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

            for (islandId in islands) {
                val cb = JCheckBox(islandId)
                cb.isSelected = pendingIslandToggles[islandId] ?: false
                cb.isEnabled = licensed && pendingGlowEnabled
                cb.addActionListener {
                    pendingIslandToggles[islandId] = cb.isSelected
                    onGlowChanged?.invoke()
                }
                islandCheckboxes[islandId] = cb
                cb.alignmentX = 0f
                tabPanel.add(cb)
            }
            tabPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        // Enable All / Disable All buttons
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        val enableAllButton = JButton("Enable All")
        enableAllButton.addActionListener {
            for (key in pendingIslandToggles.keys) {
                pendingIslandToggles[key] = true
            }
            refreshIslandCheckboxes()
            onGlowChanged?.invoke()
        }
        buttonRow.add(enableAllButton)

        val disableAllButton = JButton("Disable All")
        disableAllButton.addActionListener {
            for (key in pendingIslandToggles.keys) {
                pendingIslandToggles[key] = false
            }
            refreshIslandCheckboxes()
            onGlowChanged?.invoke()
        }
        buttonRow.add(disableAllButton)
        buttonRow.alignmentX = 0f
        tabPanel.add(buttonRow)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(16)))

        // Tab glow mode
        val tabGlowLabel = JLabel("Active Tab Glow")
        tabGlowLabel.font = tabGlowLabel.font.deriveFont(java.awt.Font.BOLD)
        tabGlowLabel.alignmentX = 0f
        tabPanel.add(tabGlowLabel)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val tabModeRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        val tabModeLabel = JLabel("Mode:")
        tabModeRow.add(tabModeLabel)
        val tabModeCombo = JComboBox(GlowTabMode.entries.map { it.displayName }.toTypedArray())
        tabModeCombo.selectedItem = GlowTabMode.fromName(pendingTabMode).displayName
        tabModeCombo.isEnabled = licensed && pendingGlowEnabled
        tabModeCombo.addActionListener {
            if (!suppressListeners) {
                val selectedIndex = tabModeCombo.selectedIndex.coerceIn(0, GlowTabMode.entries.size - 1)
                pendingTabMode = GlowTabMode.entries[selectedIndex].name
                onGlowChanged?.invoke()
            }
        }
        tabModeRow.add(tabModeCombo)
        tabModeRow.alignmentX = 0f
        tabPanel.add(tabModeRow)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Focus-ring glow
        val focusRingLabel = JLabel("Input Glow")
        focusRingLabel.font = focusRingLabel.font.deriveFont(java.awt.Font.BOLD)
        focusRingLabel.alignmentX = 0f
        tabPanel.add(focusRingLabel)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val focusRingCheckbox = JCheckBox("Focused input glow ring")
        focusRingCheckbox.isSelected = pendingFocusRing
        focusRingCheckbox.isEnabled = licensed && pendingGlowEnabled
        focusRingCheckbox.addActionListener {
            pendingFocusRing = focusRingCheckbox.isSelected
            onGlowChanged?.invoke()
        }
        focusRingCheckbox.alignmentX = 0f
        tabPanel.add(focusRingCheckbox)

        val focusRingHint = JLabel("Subtle glow around focused text fields and inputs")
        focusRingHint.foreground = UIManager.getColor("Label.disabledForeground")
        focusRingHint.font = JBUI.Fonts.smallFont()
        focusRingHint.alignmentX = 0f
        tabPanel.add(focusRingHint)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Floating panels toggle
        val floatingLabel = JLabel("Floating Panels")
        floatingLabel.font = floatingLabel.font.deriveFont(java.awt.Font.BOLD)
        floatingLabel.alignmentX = 0f
        tabPanel.add(floatingLabel)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val floatingCheckbox = JCheckBox("Glow on floating panels")
        floatingCheckbox.isSelected = pendingFloatingPanels
        floatingCheckbox.isEnabled = licensed && pendingGlowEnabled
        floatingCheckbox.addActionListener {
            pendingFloatingPanels = floatingCheckbox.isSelected
            onGlowChanged?.invoke()
        }
        floatingCheckbox.alignmentX = 0f
        tabPanel.add(floatingCheckbox)

        val floatingHint = JLabel("Apply glow to undocked/floating tool windows")
        floatingHint.foreground = UIManager.getColor("Label.disabledForeground")
        floatingHint.font = JBUI.Fonts.smallFont()
        floatingHint.alignmentX = 0f
        tabPanel.add(floatingHint)

        return tabPanel
    }

    private fun refreshIslandCheckboxes() {
        for ((id, cb) in islandCheckboxes) {
            cb.isSelected = pendingIslandToggles[id] ?: false
        }
    }

    // Animation tab

    private fun buildAnimationTab(licensed: Boolean): JPanel {
        val tabPanel = JPanel()
        tabPanel.layout = BoxLayout(tabPanel, BoxLayout.Y_AXIS)
        tabPanel.border = BorderFactory.createEmptyBorder(
            JBUI.scale(8), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8)
        )

        val animLabel = JLabel("Animation Type")
        animLabel.alignmentX = 0f
        tabPanel.add(animLabel)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val displayNames = GlowAnimation.entries.map { it.displayName }.toTypedArray()
        val combo = JComboBox(displayNames)
        combo.selectedIndex = GlowAnimation.entries.indexOf(pendingAnimation).coerceAtLeast(0)
        combo.isEnabled = licensed && pendingGlowEnabled
        combo.addActionListener {
            if (!suppressListeners) {
                val selectedIndex = combo.selectedIndex.coerceIn(0, GlowAnimation.entries.size - 1)
                pendingAnimation = GlowAnimation.entries[selectedIndex]
                updateAnimationDescription()
                onAnimationChanged?.invoke()
            }
        }
        animationCombo = combo
        combo.alignmentX = 0f
        combo.maximumSize = Dimension(JBUI.scale(200), combo.preferredSize.height)
        tabPanel.add(combo)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        val descLabel = JLabel(animationDescription(pendingAnimation))
        descLabel.foreground = UIManager.getColor("Label.disabledForeground")
        descLabel.alignmentX = 0f
        animationDescriptionLabel = descLabel
        tabPanel.add(descLabel)

        // Vertical glue to push content to top
        tabPanel.add(Box.createVerticalGlue())

        return tabPanel
    }

    private fun animationDescription(animation: GlowAnimation): String = when (animation) {
        GlowAnimation.NONE -> "Static glow with no animation."
        GlowAnimation.PULSE -> "Sharp rhythmic brightening every 2 seconds."
        GlowAnimation.BREATHE -> "Slow sinusoidal swell over 4 seconds."
        GlowAnimation.REACTIVE -> "Responds to typing and IDE actions."
    }

    private fun updateAnimationDescription() {
        animationDescriptionLabel?.text = animationDescription(pendingAnimation)
    }

    // Presets tab

    private fun buildPresetsTab(licensed: Boolean): JPanel {
        val tabPanel = JPanel()
        tabPanel.layout = BoxLayout(tabPanel, BoxLayout.Y_AXIS)
        tabPanel.border = BorderFactory.createEmptyBorder(
            JBUI.scale(8), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8)
        )

        val presetLabel = JLabel("Preset")
        presetLabel.alignmentX = 0f
        tabPanel.add(presetLabel)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val combo = JComboBox<String>()
        refreshPresetComboModel(combo)
        combo.isEnabled = licensed && pendingGlowEnabled
        combo.addActionListener {
            if (!suppressListeners) {
                val selectedName = combo.selectedItem as? String ?: return@addActionListener
                val preset = findPresetByName(selectedName) ?: return@addActionListener
                loadPreset(preset)
                updatePresetButtonStates()
            }
        }
        presetCombo = combo
        combo.alignmentX = 0f
        combo.maximumSize = Dimension(JBUI.scale(250), combo.preferredSize.height)
        tabPanel.add(combo)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Buttons row
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))

        val saveBtn = JButton("Save As...")
        saveBtn.addActionListener { savePresetAction() }
        saveButton = saveBtn
        buttonRow.add(saveBtn)

        val deleteBtn = JButton("Delete")
        deleteBtn.addActionListener { deletePresetAction() }
        deleteButton = deleteBtn
        buttonRow.add(deleteBtn)

        val renameBtn = JButton("Rename...")
        renameBtn.addActionListener { renamePresetAction() }
        renameButton = renameBtn
        buttonRow.add(renameBtn)

        buttonRow.alignmentX = 0f
        tabPanel.add(buttonRow)
        tabPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        // Duplicate warning label (hidden by default)
        val warning = JLabel()
        warning.foreground = Color(0xCC, 0x66, 0x00)
        warning.isVisible = false
        warning.alignmentX = 0f
        duplicateWarning = warning
        tabPanel.add(warning)

        tabPanel.add(Box.createVerticalGlue())

        updatePresetButtonStates()

        return tabPanel
    }

    private fun refreshPresetComboModel(combo: JComboBox<String>) {
        val model = DefaultComboBoxModel<String>()
        for (preset in GlowPreset.BUILT_IN) {
            model.addElement(preset.name)
        }
        for (preset in pendingUserPresets) {
            model.addElement(preset.name)
        }
        combo.model = model
    }

    private fun findPresetByName(name: String): GlowPreset? {
        return GlowPreset.BUILT_IN.firstOrNull { it.name == name }
            ?: pendingUserPresets.firstOrNull { it.name == name }
    }

    private fun isBuiltInPresetSelected(): Boolean {
        val selectedName = presetCombo?.selectedItem as? String ?: return true
        return GlowPreset.BUILT_IN.any { it.name == selectedName }
    }

    private fun updatePresetButtonStates() {
        val isBuiltIn = isBuiltInPresetSelected()
        deleteButton?.isEnabled = !isBuiltIn
        renameButton?.isEnabled = !isBuiltIn
    }

    private fun savePresetAction() {
        val name = Messages.showInputDialog(
            "Enter preset name:",
            "Save Preset",
            null,
        ) ?: return

        if (name.isBlank()) return

        // Check for duplicates
        val allNames = GlowPreset.BUILT_IN.map { it.name } + pendingUserPresets.map { it.name }
        if (name in allNames) {
            duplicateWarning?.text = "A preset named \"$name\" already exists."
            duplicateWarning?.isVisible = true
            return
        }

        duplicateWarning?.isVisible = false

        val newPreset = buildCurrentPresetState(name)
        pendingUserPresets.add(newPreset)

        val combo = presetCombo ?: return
        refreshPresetComboModel(combo)
        combo.selectedItem = name
        updatePresetButtonStates()
    }

    private fun deletePresetAction() {
        val selectedName = presetCombo?.selectedItem as? String ?: return
        if (isBuiltInPresetSelected()) return

        pendingUserPresets.removeAll { it.name == selectedName }
        val combo = presetCombo ?: return
        refreshPresetComboModel(combo)
        if (combo.itemCount > 0) combo.selectedIndex = 0
        updatePresetButtonStates()
    }

    private fun renamePresetAction() {
        val selectedName = presetCombo?.selectedItem as? String ?: return
        if (isBuiltInPresetSelected()) return

        val newName = Messages.showInputDialog(
            "Enter new name:",
            "Rename Preset",
            null,
            selectedName,
            null,
        ) ?: return

        if (newName.isBlank() || newName == selectedName) return

        val allNames = GlowPreset.BUILT_IN.map { it.name } +
            pendingUserPresets.filter { it.name != selectedName }.map { it.name }
        if (newName in allNames) {
            duplicateWarning?.text = "A preset named \"$newName\" already exists."
            duplicateWarning?.isVisible = true
            return
        }

        duplicateWarning?.isVisible = false

        val index = pendingUserPresets.indexOfFirst { it.name == selectedName }
        if (index >= 0) {
            pendingUserPresets[index] = pendingUserPresets[index].copy(name = newName)
        }

        val combo = presetCombo ?: return
        refreshPresetComboModel(combo)
        combo.selectedItem = newName
        updatePresetButtonStates()
    }

    // Helpers

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

        pendingUserPresets.clear()
        val serialized = state.glowUserPresets
        if (!serialized.isNullOrBlank()) {
            pendingUserPresets.addAll(GlowPreset.deserializePresets(serialized))
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
        storedUserPresets = pendingUserPresets.map { it.copy() }
        storedTabMode = pendingTabMode
        storedFocusRing = pendingFocusRing
        storedFloatingPanels = pendingFloatingPanels
    }

    private fun updateControlStates() {
        val enabled = pendingGlowEnabled && LicenseChecker.isLicensedOrGrace()

        // Dim tabs content when glow is off
        intensitySlider?.isEnabled = enabled
        widthSlider?.isEnabled = enabled
        animationCombo?.isEnabled = enabled
        presetCombo?.isEnabled = enabled
        saveButton?.isEnabled = enabled
        deleteButton?.isEnabled = enabled && !isBuiltInPresetSelected()
        renameButton?.isEnabled = enabled && !isBuiltInPresetSelected()

        for ((_, cb) in islandCheckboxes) {
            cb.isEnabled = enabled
        }

        for ((_, swatch) in styleSwatches) {
            swatch.isEnabled = enabled
            swatch.cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            swatch.repaint()
        }
    }

    private fun stopAnimationPreview() {
        previewAnimator?.stop()
    }

    private fun loadPreset(preset: GlowPreset) {
        suppressListeners = true
        pendingStyle = preset.style
        pendingIntensity[preset.style] = preset.intensity
        pendingWidth[preset.style] = preset.width
        pendingAnimation = preset.animation

        // Update island toggles from preset
        for (id in pendingIslandToggles.keys) {
            pendingIslandToggles[id] = id in preset.enabledIslands
        }

        refreshAllControls()
        suppressListeners = false
        onGlowChanged?.invoke()
    }

    private fun refreshAllControls() {
        // Style swatches
        for ((style, swatch) in styleSwatches) {
            swatch.selected = style == pendingStyle
            swatch.repaint()
        }

        // Sliders
        refreshSliders()

        // Animation combo
        animationCombo?.selectedIndex = GlowAnimation.entries.indexOf(pendingAnimation).coerceAtLeast(0)
        updateAnimationDescription()

        // Island checkboxes
        refreshIslandCheckboxes()

        // Master toggle
        masterToggle?.isSelected = pendingGlowEnabled

        updateControlStates()
    }

    private fun buildCurrentPresetState(name: String): GlowPreset {
        return GlowPreset(
            name = name,
            style = pendingStyle,
            intensity = pendingIntensity[pendingStyle] ?: pendingStyle.defaultIntensity,
            width = pendingWidth[pendingStyle] ?: pendingStyle.defaultWidth,
            animation = pendingAnimation,
            enabledIslands = pendingIslandToggles.filter { it.value }.keys.toSet(),
        )
    }

    // Public accessors

    fun isGlowEnabled(): Boolean = pendingGlowEnabled

    fun getCurrentStyle(): GlowStyle = pendingStyle

    fun getCurrentIntensity(): Int = pendingIntensity[pendingStyle] ?: pendingStyle.defaultIntensity

    fun getCurrentWidth(): Int = pendingWidth[pendingStyle] ?: pendingStyle.defaultWidth

    fun getCurrentAnimation(): GlowAnimation = pendingAnimation

    fun getActiveTabIndex(): Int = tabbedPane?.selectedIndex ?: 0

    fun getIslandToggles(): Map<String, Boolean> = pendingIslandToggles.toMap()

    fun getEffectsTabbedPane(): javax.swing.JTabbedPane? = tabbedPane

    // AyuIslandsSettingsPanel contract

    override fun isModified(): Boolean {
        if (pendingGlowEnabled != storedGlowEnabled) return true
        if (pendingStyle != storedStyle) return true
        if (pendingIntensity != storedIntensity) return true
        if (pendingWidth != storedWidth) return true
        if (pendingAnimation != storedAnimation) return true
        if (pendingIslandToggles != storedIslandToggles) return true
        if (pendingUserPresets != storedUserPresets) return true
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

        state.glowUserPresets = GlowPreset.serializePresets(pendingUserPresets)

        state.glowTabMode = pendingTabMode
        state.glowFocusRing = pendingFocusRing
        state.glowFloatingPanels = pendingFloatingPanels

        // Mark onboarding shown if glow was enabled
        if (pendingGlowEnabled) {
            state.glowOnboardingShown = true
        }

        stopAnimationPreview()
        copyPendingToStored()
    }

    override fun reset() {
        pendingGlowEnabled = storedGlowEnabled
        pendingStyle = storedStyle
        pendingIntensity = storedIntensity.toMutableMap()
        pendingWidth = storedWidth.toMutableMap()
        pendingAnimation = storedAnimation
        pendingIslandToggles = storedIslandToggles.toMutableMap()
        pendingUserPresets = storedUserPresets.map { it.copy() }.toMutableList()
        pendingTabMode = storedTabMode
        pendingFocusRing = storedFocusRing
        pendingFloatingPanels = storedFloatingPanels

        refreshAllControls()
    }

    /** Mini swatch showing a glow style preview with selection highlight. */
    private class StyleSwatchPanel(
        private val style: GlowStyle,
        var selected: Boolean,
    ) : JPanel() {

        init {
            preferredSize = Dimension(JBUI.scale(60), JBUI.scale(40))
            toolTipText = style.displayName
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isOpaque = false
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val bg = UIManager.getColor("Panel.background") ?: Color(0x2B, 0x2D, 0x30)
                val accent = UIManager.getColor("Component.focusColor") ?: Color(0xFF, 0xCC, 0x66)

                // Background
                g2.color = if (isEnabled) bg.darker() else bg
                g2.fillRoundRect(0, 0, width, height, 6, 6)

                // Mini glow preview
                if (isEnabled) {
                    val glowColor = Color(accent.red, accent.green, accent.blue, 80)
                    g2.color = glowColor
                    val inset = 4
                    g2.drawRoundRect(inset, inset, width - 2 * inset, height - 2 * inset, 4, 4)
                }

                // Selection border
                if (selected) {
                    g2.color = accent
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
                }

                // Label
                g2.color = if (isEnabled) Color.WHITE else Color.GRAY
                g2.font = JBUI.Fonts.miniFont()
                val textWidth = g2.fontMetrics.stringWidth(style.displayName)
                g2.drawString(style.displayName, (width - textWidth) / 2, height - JBUI.scale(6))
            } finally {
                g2.dispose()
            }
        }
    }
}
