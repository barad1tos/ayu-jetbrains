package dev.ayuislands.studio.prototype

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.syntax.SyntaxPreset
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

private const val INSPECTOR_WIDTH = 300
private const val INSPECTOR_MARGIN = 12
private const val CONTROL_GAP = 6
private const val SLIDER_WIDTH = 190
private const val MAX_INTENSITY = 100
private const val MIN_FONT_SIZE = 8.0
private const val MAX_FONT_SIZE = 30.0
private const val INITIAL_FONT_SIZE = 14.0

/** Keep controls alive while the selected surface and local draft change. */
internal class StudioPrototypeInspector(
    private val session: StudioPrototypeSession,
    private val onChanged: () -> Unit,
) : JPanel(CardLayout()) {
    private val accentPresets =
        named(JComboBox(AYU_ACCENT_PRESETS.map { "${it.name} ${it.hex}" }.toTypedArray()), "Accent preset")
    private val accentHex = named(JTextField(), "Accent HEX")
    private val accentValidation = comment(" ")
    private val fontSize =
        named(JSpinner(SpinnerNumberModel(INITIAL_FONT_SIZE, MIN_FONT_SIZE, MAX_FONT_SIZE, 1.0)), "Font size")
    private val glowEnabled = named(JCheckBox("Glow enabled"), "Glow enabled")
    private val glowStyle = named(JComboBox(GlowStyle.entries.map { it.displayName }.toTypedArray()), "Glow style")
    private val glowIntensity = named(JSlider(0, MAX_INTENSITY), "Glow intensity")
    private val glowReadout = JLabel()
    private val syntaxPreset =
        named(JComboBox(SyntaxPreset.entries.map { it.displayName }.toTypedArray()), "Syntax preset")
    private val vcsIntensity = named(JSlider(0, MAX_INTENSITY), "VCS diff intensity")
    private val vcsReadout = JLabel()
    private var isRefreshing = false

    init {
        name = "Studio inspector"
        getAccessibleContext().accessibleName = name
        preferredSize = JBUI.size(INSPECTOR_WIDTH, INSPECTOR_WIDTH)
        minimumSize = Dimension(0, 0)
        border =
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(INSPECTOR_MARGIN),
            )
        add(accentCard(), StudioPrototypeSection.ACCENT.name)
        add(
            card("Typography", row("Font size", fontSize), comment("Textual sample only; the painter keeps its font.")),
            StudioPrototypeSection.FONT.name,
        )
        add(
            card(
                "Editor frame / Glow",
                glowEnabled,
                row("Style", glowStyle),
                row("Intensity", sliderRow(glowIntensity, glowReadout)),
                comment("Each style keeps its own intensity."),
            ),
            StudioPrototypeSection.GLOW.name,
        )
        add(
            card(
                "Editor colors / Syntax",
                row("Preset", syntaxPreset),
                comment("Textual sample only; colors are not simulated."),
            ),
            StudioPrototypeSection.SYNTAX.name,
        )
        add(
            card(
                "Version control",
                row("Diff intensity", sliderRow(vcsIntensity, vcsReadout)),
                comment("Textual sample only; diff colors are not simulated."),
            ),
            StudioPrototypeSection.VCS.name,
        )
        bindControls()
    }

    fun refresh(section: StudioPrototypeSection) {
        isRefreshing = true
        try {
            val appearance = session.draft
            accentPresets.selectedIndex = AYU_ACCENT_PRESETS.indexOfFirst { it.hex.equals(appearance.accentHex, true) }
            if (accentHex.text != appearance.accentHex) accentHex.text = appearance.accentHex
            fontSize.value = appearance.fontSize.toDouble()
            glowEnabled.isSelected = appearance.glowEnabled
            glowStyle.selectedIndex = GlowStyle.entries.indexOf(appearance.glowStyle)
            glowIntensity.value = appearance.glowIntensities.getValue(appearance.glowStyle)
            glowReadout.text = "${glowIntensity.value}%"
            syntaxPreset.selectedIndex = SyntaxPreset.entries.indexOf(appearance.syntaxPreset)
            vcsIntensity.value = appearance.vcsDiffIntensity
            vcsReadout.text = "${vcsIntensity.value}%"
            (layout as CardLayout).show(this, section.name)
        } finally {
            isRefreshing = false
        }
    }

    private fun accentCard(): JPanel {
        val useHex = named(JButton("Use HEX"), "Use HEX")
        useHex.addActionListener {
            val entered = accentHex.text.trim()
            if (Regex("#[0-9a-fA-F]{6}").matches(entered)) {
                accentValidation.text = " "
                edit { it.copy(accentHex = entered.uppercase()) }
            } else {
                accentValidation.text = "Enter a HEX color, for example #FFCD66."
            }
        }
        return card("Accent", row("Preset", accentPresets), row("HEX color", accentHex), useHex, accentValidation)
    }

    private fun bindControls() {
        accentPresets.addActionListener {
            val index = accentPresets.selectedIndex
            if (index >= 0) edit { it.copy(accentHex = AYU_ACCENT_PRESETS[index].hex) }
        }
        fontSize.addChangeListener { edit { it.copy(fontSize = (fontSize.value as Number).toFloat()) } }
        glowEnabled.addActionListener { edit { it.copy(glowEnabled = glowEnabled.isSelected) } }
        glowStyle.addActionListener { edit { it.copy(glowStyle = GlowStyle.entries[glowStyle.selectedIndex]) } }
        glowIntensity.addChangeListener {
            edit { it.copy(glowIntensities = it.glowIntensities + (it.glowStyle to glowIntensity.value)) }
        }
        syntaxPreset.addActionListener {
            edit {
                it.copy(
                    syntaxPreset = SyntaxPreset.entries[syntaxPreset.selectedIndex],
                )
            }
        }
        vcsIntensity.addChangeListener { edit { it.copy(vcsDiffIntensity = vcsIntensity.value) } }
    }

    private fun edit(transform: (StudioPrototypeAppearance) -> StudioPrototypeAppearance) {
        if (!isRefreshing) {
            session.editAppearance(transform)
            onChanged()
        }
    }

    private fun card(
        title: String,
        vararg controls: JComponent,
    ): JPanel {
        val contents = JPanel()
        contents.layout = BoxLayout(contents, BoxLayout.Y_AXIS)
        val heading = JLabel(title)
        heading.font = heading.font.deriveFont(Font.BOLD)
        for (component in listOf(heading) + controls) {
            component.alignmentX = LEFT_ALIGNMENT
            component.maximumSize = Dimension(Int.MAX_VALUE, component.preferredSize.height + JBUI.scale(CONTROL_GAP))
            contents.add(component)
        }
        return JPanel(BorderLayout()).apply { add(contents, BorderLayout.NORTH) }
    }

    private fun row(
        label: String,
        control: JComponent,
    ): JPanel =
        JPanel(BorderLayout(0, JBUI.scale(CONTROL_GAP))).apply {
            add(JLabel(label).apply { labelFor = control }, BorderLayout.NORTH)
            add(control, BorderLayout.CENTER)
            border = JBUI.Borders.emptyTop(CONTROL_GAP)
        }

    private fun sliderRow(
        slider: JSlider,
        readout: JLabel,
    ): JPanel =
        JPanel(BorderLayout(JBUI.scale(CONTROL_GAP), 0)).apply {
            slider.preferredSize = JBUI.size(SLIDER_WIDTH, slider.preferredSize.height)
            add(slider, BorderLayout.CENTER)
            add(readout, BorderLayout.EAST)
        }

    private fun <T : JComponent> named(
        component: T,
        label: String,
    ): T =
        component.apply {
            name = label
            accessibleContext.accessibleName = label
        }

    private fun comment(text: String): JTextArea =
        JTextArea(text, 2, 0).apply {
            isEditable = false
            isFocusable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            font = JLabel().font
        }
}
