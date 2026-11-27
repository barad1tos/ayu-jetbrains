package dev.ayuislands.studio.ui

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.font.FontEditorLimits
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontStatus
import dev.ayuislands.font.FontWeight
import dev.ayuislands.settings.FontPreviewComponent
import dev.ayuislands.settings.fontWeightCombo
import dev.ayuislands.studio.model.StudioFontEdit
import dev.ayuislands.studio.session.StudioFontSettings
import dev.ayuislands.studio.session.StudioFontTerminal
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

internal class StudioFontControls(
    customizeExpanded: Boolean,
    private val onAction: (StudioFontAction) -> Unit,
    private val onEdit: (StudioFontEdit) -> Unit,
) : JPanel(BorderLayout()) {
    private val editable = AtomicBooleanProperty(false)
    private val enabled = AtomicBooleanProperty(false)
    private val available = AtomicBooleanProperty(false)
    private val unavailable = AtomicBooleanProperty(false)
    private val custom = AtomicBooleanProperty(false)
    private val selected = AtomicProperty<FontPreset?>(null)
    private val enabledToggle = JBCheckBox("Apply font preset")
    private val ligatures = JBCheckBox("Ligatures")
    private val console = JBCheckBox("Also apply to console")
    private val size =
        dimensionSpinner(
            FontPreset.AMBIENT.fontSize.toDouble(),
            FontEditorLimits.MIN_SIZE.toDouble(),
            FontEditorLimits.MAX_SIZE.toDouble(),
            1.0,
        )
    private val spacing =
        dimensionSpinner(
            FontPreset.AMBIENT.lineSpacing.toDouble(),
            FontEditorLimits.MIN_SPACING,
            FontEditorLimits.MAX_SPACING,
            FontEditorLimits.SPACING_STEP,
        )
    private val weight = fontWeightCombo()
    private val family = ComboBox<String>().apply { prototypeDisplayValue = FontPreset.CUSTOM.fontFamily }
    private val summary = JBLabel()
    private val unavailableLabel = JBLabel()
    private val sample = FontPreviewComponent().apply { accessibleContext?.accessibleName = "Font preview" }
    private val resources = FontResourceControls(::manage)
    private val manual = FontManualInstallControls(::emitAction)
    private var choices = emptyList<String>()
    private var isRendering = false

    init {
        bindEvents()
        val typography = fields("Size" to size, "Spacing" to spacing, "Weight" to weight)
        val customFamily = fields("Font" to family)
        add(
            panel {
                row {
                    cell(enabledToggle).enabledIf(editable)
                    link("Reset defaults") { emit(StudioFontEdit.Reset) }.enabledIf(editable)
                }
                row("Preset") {
                    segmentedButton<FontPreset?>(FontPreset.entries) { text = it?.displayName.orEmpty() }
                        .bind(selected)
                }.visibleIf(enabled).enabledIf(editable)
                row { cell(unavailableLabel) }.visibleIf(unavailable)
                row { cell(customFamily).resizableColumn().align(Align.FILL) }.visibleIf(custom).enabledIf(editable)
                row { cell(summary) }.visibleIf(available)
                row { cell(sample).resizableColumn().align(Align.FILL) }
                row { cell(resources).resizableColumn().align(Align.FILL) }
                row { cell(manual).resizableColumn().align(Align.FILL) }
                val customize =
                    collapsibleGroup("Customize") {
                        row { cell(typography).resizableColumn().align(Align.FILL) }.enabledIf(editable)
                        row {
                            cell(ligatures)
                            cell(console)
                        }.enabledIf(editable)
                        row { link("Reapply preset") { emit(StudioFontEdit.Reapply) } }.enabledIf(editable)
                    }
                customize.expanded = customizeExpanded
                customize.addExpandedListener { onAction(StudioFontAction.DiscloseCustomize(it)) }
                customize.visibleIf(available)
            },
            BorderLayout.CENTER,
        )
    }

    fun render(
        state: StudioFontSettings,
        availability: StudioFontAvailability,
        isEditable: Boolean,
        manualInstall: Boolean,
        terminal: StudioFontTerminal,
    ) {
        isRendering = true
        try {
            val resolved = state.resolved
            editable.set(isEditable)
            enabled.set(state.enabled)
            available.set(state.enabled && resolved != null)
            unavailable.set(state.enabled && resolved == null)
            custom.set(state.enabled && resolved?.preset == FontPreset.CUSTOM)
            enabledToggle.isSelected = state.enabled
            selected.set(resolved?.preset)
            val canCustomize = isEditable && available.get()
            for (control in listOf(size, spacing, weight, ligatures, console)) control.isEnabled = canCustomize
            family.isEnabled = isEditable && custom.get()
            unavailableLabel.text = "Saved preset unavailable (${state.presetName.orEmpty()})"
            if (resolved != null) renderSettings(resolved, availability)
            val preset = resolved?.preset?.takeIf { state.enabled && it.isCurated }
            val status = preset?.let { availability.curated[it]?.status }
            resources.render(preset, status, isEditable)
            manual.render(preset.takeIf { manualInstall && status == FontStatus.NOT_INSTALLED }, terminal, isEditable)
        } finally {
            isRendering = false
        }
    }

    fun showPreview(preview: StudioFontSample?) {
        sample.isVisible = preview != null
        if (preview != null) {
            sample.updateSample(preview.settings, preview.family, preview.installed, preview.accent)
        }
    }

    private fun bindEvents() {
        enabledToggle.addActionListener { emit(StudioFontEdit.Enable(enabledToggle.isSelected)) }
        selected.afterChange { preset -> preset?.let { emit(StudioFontEdit.Preset(it)) } }
        ligatures.addActionListener { emit(StudioFontEdit.Ligatures(ligatures.isSelected)) }
        console.addActionListener { emit(StudioFontEdit.Console(console.isSelected)) }
        size.addChangeListener { emit(StudioFontEdit.Size((size.value as Number).toFloat())) }
        spacing.addChangeListener { emit(StudioFontEdit.Spacing((spacing.value as Number).toFloat())) }
        weight.addActionListener { (weight.selectedItem as? FontWeight)?.let { emit(StudioFontEdit.Weight(it)) } }
        family.addActionListener { (family.selectedItem as? String)?.let { emit(StudioFontEdit.Family(it)) } }
    }

    private fun renderSettings(
        settings: FontSettings,
        availability: StudioFontAvailability,
    ) {
        val ligatureLabel = if (settings.enableLigatures) "ligatures" else "no ligatures"
        summary.text =
            "${availability.familyFor(settings)} · ${settings.fontSize} pt · " +
            "${settings.weight.displayName} · ${settings.lineSpacing}× · $ligatureLabel"
        size.value = settings.fontSize.toDouble()
        spacing.value = settings.lineSpacing.toDouble()
        ligatures.isSelected = settings.enableLigatures
        console.isSelected = settings.applyToConsole
        weight.selectedItem = settings.weight
        if (settings.preset != FontPreset.CUSTOM) return
        val availableFamilies = (availability.families + settings.fontFamily).distinct()
        if (availableFamilies != choices) {
            choices = availableFamilies
            family.model = DefaultComboBoxModel(choices.toTypedArray())
        }
        family.selectedItem = settings.fontFamily
        family.toolTipText = "Font family ${settings.fontFamily}"
    }

    private fun emit(edit: StudioFontEdit) {
        if (!isRendering && editable.get()) onEdit(edit)
    }

    private fun manage(
        preset: FontPreset,
        operation: StudioFontOperation,
    ) = emitAction(StudioFontAction.ManageFont(preset, operation))

    private fun emitAction(action: StudioFontAction) {
        if (!isRendering && editable.get()) onAction(action)
    }
}

private class FontResourceControls(
    private val onManage: (FontPreset, StudioFontOperation) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEADING, StudioUiTokens.spacing().fieldGap, 0)) {
    private val status = JBLabel()
    private val install = action("Install font", StudioFontOperation.INSTALL)
    private val reinstall = action("Reinstall", StudioFontOperation.REINSTALL)
    private val delete = action("Delete", StudioFontOperation.DELETE)
    private var preset: FontPreset? = null

    init {
        isOpaque = false
        add(status)
        add(install)
        add(reinstall)
        add(delete)
    }

    fun render(
        preset: FontPreset?,
        observed: FontStatus?,
        editable: Boolean,
    ) {
        this.preset = preset
        isVisible = preset != null && observed != null
        status.text =
            when (observed) {
                FontStatus.HEALTHY -> "Installed"
                FontStatus.CORRUPTED -> "Installed file missing"
                FontStatus.NOT_INSTALLED, null -> ""
            }
        status.isVisible = status.text.isNotEmpty()
        install.isVisible = observed == FontStatus.NOT_INSTALLED
        reinstall.isVisible = observed == FontStatus.HEALTHY || observed == FontStatus.CORRUPTED
        delete.isVisible = observed == FontStatus.HEALTHY
        for (link in listOf(install, reinstall, delete)) link.isEnabled = editable
    }

    private fun action(
        label: String,
        operation: StudioFontOperation,
    ): ActionLink =
        ActionLink(label) { event ->
            val link = event.source as? ActionLink
            if (link?.isVisible == true && link.isEnabled) preset?.let { onManage(it, operation) }
        }
}

private class FontManualInstallControls(
    private val onAction: (StudioFontAction) -> Unit,
) : JPanel(BorderLayout()) {
    private val command = JBLabel()
    private val terminal = ComboBox(StudioFontTerminal.entries.map { it.displayName }.toTypedArray())
    private val copy = ActionLink("Copy") { offeredPreset?.let { onAction(StudioFontAction.CopyInstallCommand(it)) } }
    private val open =
        ActionLink("Open Terminal") {
            offeredPreset?.let { onAction(StudioFontAction.OpenInstallTerminal(it, selectedTerminal())) }
        }
    private var offeredPreset: FontPreset? = null

    init {
        isOpaque = false
        terminal.addActionListener { onAction(StudioFontAction.SelectTerminal(selectedTerminal())) }
        add(
            panel {
                row { cell(command) }
                row {
                    cell(copy)
                    cell(open)
                    cell(fields("Terminal" to terminal)).resizableColumn().align(Align.FILL)
                }
            },
            BorderLayout.CENTER,
        )
    }

    fun render(
        preset: FontPreset?,
        selectedTerminal: StudioFontTerminal,
        editable: Boolean,
    ) {
        val text = preset?.let(::fontInstallCommand)
        offeredPreset = preset.takeIf { text != null }
        isVisible = text != null
        command.text = text.orEmpty()
        terminal.selectedItem = selectedTerminal.displayName
        terminal.isEnabled = editable
        copy.isEnabled = editable
        open.isEnabled = editable
    }

    private fun selectedTerminal(): StudioFontTerminal =
        StudioFontTerminal.entries.getOrNull(terminal.selectedIndex) ?: StudioFontTerminal.BUILTIN
}

private fun fields(vararg entries: Pair<String, JComponent>): JPanel =
    JPanel(StudioResponsiveLayout()).apply {
        isOpaque = false
        for ((label, control) in entries) {
            add(JBLabel(label).apply { labelFor = control })
            add(control)
        }
    }

private fun dimensionSpinner(
    value: Double,
    minimum: Double,
    maximum: Double,
    step: Double,
): JSpinner =
    JSpinner(SpinnerNumberModel(value, minimum, maximum, step)).apply {
        editor = JSpinner.NumberEditor(this, "0.##")
    }
