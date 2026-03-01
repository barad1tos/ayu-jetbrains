package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.licensing.LicenseChecker
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
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.UIManager

/**
 * Glow effects configuration split across Glow and Advanced tabs.
 *
 * [buildGlowPanel] renders the master toggle, style swatches, sliders, and animation.
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
    private val animationSwatches = mutableMapOf<GlowAnimation, StyleSwatchPanel>()
    private val islandCheckboxes = mutableMapOf<String, JCheckBox>()
    private val styleSwatches = mutableMapOf<GlowStyle, StyleSwatchPanel>()
    private var animationDescriptionLabel: JLabel? = null
    private val tabModeSwatches = mutableMapOf<GlowTabMode, StyleSwatchPanel>()
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

        // Style swatches
        panel.group("Style") {
            row {
                val swatchRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
                for (style in GlowStyle.entries) {
                    val swatch = StyleSwatchPanel(style.displayName, style == pendingStyle)
                    swatch.addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(event: MouseEvent) {
                            if (!licensed || !pendingGlowEnabled) return
                            selectStyle(style)
                        }
                    })
                    swatchRow.add(swatch)
                    styleSwatches[style] = swatch
                }
                cell(swatchRow)
            }
        }

        // Intensity slider
        panel.row {
            label("Intensity")
        }
        panel.row {
            val slider = JSlider(0, 100, pendingIntensity[pendingStyle] ?: 40)
            slider.paintTicks = true
            slider.paintLabels = true
            slider.majorTickSpacing = 50
            slider.minorTickSpacing = 10
            val labels = Hashtable<Int, JLabel>()
            labels[0] = JLabel("Low")
            labels[50] = JLabel("Med")
            labels[100] = JLabel("High")
            slider.labelTable = labels
            slider.addChangeListener {
                if (!suppressListeners) {
                    pendingIntensity[pendingStyle] = slider.value
                }
            }
            intensitySlider = slider
            cell(slider).resizableColumn().align(Align.FILL)
        }

        // Width slider
        panel.row {
            label("Width (px)")
        }
        panel.row {
            val slider = JSlider(4, 32, pendingWidth[pendingStyle] ?: 10)
            slider.paintTicks = true
            slider.paintLabels = true
            slider.majorTickSpacing = 7
            slider.minorTickSpacing = 1
            slider.addChangeListener {
                if (!suppressListeners) {
                    pendingWidth[pendingStyle] = slider.value
                }
            }
            widthSlider = slider
            cell(slider).resizableColumn().align(Align.FILL)
        }

        // Animation
        panel.group("Animation") {
            row {
                val swatchRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
                for (animation in GlowAnimation.entries) {
                    val swatch = StyleSwatchPanel(animation.displayName, animation == pendingAnimation)
                    swatch.addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(event: MouseEvent) {
                            if (!licensed || !pendingGlowEnabled) return
                            if (suppressListeners) return
                            pendingAnimation = animation
                            for ((anim, sw) in animationSwatches) {
                                sw.selected = anim == animation
                                sw.repaint()
                            }
                            updateAnimationDescription()
                        }
                    })
                    swatchRow.add(swatch)
                    animationSwatches[animation] = swatch
                }
                cell(swatchRow)
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
            label("Active Tab Glow")
        }
        panel.row {
            val swatchRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
            swatchRow.isOpaque = false
            val currentTabMode = GlowTabMode.fromName(pendingTabMode)
            for (mode in GlowTabMode.entries) {
                val swatch = StyleSwatchPanel(mode.displayName, mode == currentTabMode)
                swatch.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (!licensed || !pendingGlowEnabled) return
                        if (suppressListeners) return
                        pendingTabMode = mode.name
                        for ((m, sw) in tabModeSwatches) {
                            sw.selected = m == mode
                            sw.repaint()
                        }
                    }
                })
                swatchRow.add(swatch)
                tabModeSwatches[mode] = swatch
            }
            cell(swatchRow)
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
        for ((swatchStyle, swatch) in styleSwatches) {
            swatch.selected = swatchStyle == style
            swatch.repaint()
        }
        refreshSliders()
    }

    private fun refreshSliders() {
        suppressListeners = true
        intensitySlider?.value = pendingIntensity[pendingStyle] ?: pendingStyle.defaultIntensity
        widthSlider?.value = pendingWidth[pendingStyle] ?: pendingStyle.defaultWidth
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

        for ((_, cb) in islandCheckboxes) {
            cb.isEnabled = enabled
        }

        for ((_, swatch) in styleSwatches) {
            swatch.isEnabled = enabled
            swatch.cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            swatch.repaint()
        }

        for ((_, swatch) in animationSwatches) {
            swatch.isEnabled = enabled
            swatch.cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            swatch.repaint()
        }

        for ((_, swatch) in tabModeSwatches) {
            swatch.isEnabled = enabled
            swatch.cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            swatch.repaint()
        }
    }

    private fun refreshAllControls() {
        // Style swatches
        for ((style, swatch) in styleSwatches) {
            swatch.selected = style == pendingStyle
            swatch.repaint()
        }

        refreshSliders()

        // Animation swatches
        for ((anim, swatch) in animationSwatches) {
            swatch.selected = anim == pendingAnimation
            swatch.repaint()
        }
        updateAnimationDescription()

        // Island checkboxes
        refreshIslandCheckboxes()

        // Master toggle
        masterToggle?.isSelected = pendingGlowEnabled

        // Tab mode swatches
        val currentTabMode = GlowTabMode.fromName(pendingTabMode)
        for ((mode, swatch) in tabModeSwatches) {
            swatch.selected = mode == currentTabMode
            swatch.repaint()
        }
        focusRingCheckbox?.isSelected = pendingFocusRing
        floatingCheckbox?.isSelected = pendingFloatingPanels

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

    /** Mini swatch button with selection highlight, used for Style and Animation selectors. */
    private class StyleSwatchPanel(
        private val displayName: String,
        var selected: Boolean,
    ) : JPanel() {

        init {
            preferredSize = Dimension(JBUI.scale(60), JBUI.scale(40))
            toolTipText = displayName
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

                g2.color = if (isEnabled) bg.darker() else bg
                g2.fillRoundRect(0, 0, width, height, 6, 6)

                if (isEnabled) {
                    val glowColor = Color(accent.red, accent.green, accent.blue, 80)
                    g2.color = glowColor
                    val inset = 4
                    g2.drawRoundRect(inset, inset, width - 2 * inset, height - 2 * inset, 4, 4)
                }

                if (selected) {
                    g2.color = accent
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
                }

                g2.color = if (isEnabled) Color.WHITE else Color.GRAY
                g2.font = JBUI.Fonts.miniFont()
                val textWidth = g2.fontMetrics.stringWidth(displayName)
                g2.drawString(displayName, (width - textWidth) / 2, height - JBUI.scale(6))
            } finally {
                g2.dispose()
            }
        }
    }
}
