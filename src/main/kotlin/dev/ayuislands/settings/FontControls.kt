package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontPanelEvent
import dev.ayuislands.font.FontPanelView
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontTerminalAction
import dev.ayuislands.font.FontWeight
import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.ui.StudioResponsiveLayout
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** Shared native controls. Observations and effects are supplied by the owning settings session. */
internal class FontControls(
    private val onEvent: (FontPanelEvent) -> Unit,
) : JPanel(BorderLayout()) {
    private var rendered: FontPanelView? = null
    private var isRendering = false
    private val editing = AtomicBooleanProperty(false)
    private val enabled = AtomicBooleanProperty(false)
    private val known = AtomicBooleanProperty(false)
    private val custom = AtomicBooleanProperty(false)
    private val sampleVisible = AtomicBooleanProperty(false)
    private val missing = AtomicBooleanProperty(false)
    private val installed = AtomicBooleanProperty(false)
    private val corrupted = AtomicBooleanProperty(false)
    private val manual = AtomicBooleanProperty(false)
    private val resourceAllowed = AtomicBooleanProperty(false)
    private val resourceNotice = AtomicBooleanProperty(false)
    private val preset = AtomicProperty<FontPreset?>(null)
    private val enable = JCheckBox("Apply font preset")
    private val family = ComboBox<String>()
    private val summary = JLabel()
    private val sample = FontPreviewComponent()
    private val notice = JLabel()
    private val brew = JLabel()
    private val terminal = ComboBox<String>()
    private val sizeSpinner = JSpinner(SpinnerNumberModel(FontPreset.AMBIENT.fontSize.toInt(), MIN_SIZE, MAX_SIZE, 1))
    private val spacing =
        JSpinner(SpinnerNumberModel(FontPreset.AMBIENT.lineSpacing.toDouble(), MIN_SPACING, MAX_SPACING, SPACING_STEP))
    private val weight = ComboBox(FontWeight.entries.toTypedArray())
    private val ligatures = JCheckBox("Ligatures")
    private val console = JCheckBox("Also apply to console")

    init {
        family.renderer = literalRenderer()
        terminal.renderer = literalRenderer()
        weight.renderer =
            object : SimpleListCellRenderer<FontWeight>() {
                override fun customize(
                    list: JList<out FontWeight>,
                    value: FontWeight?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    text = value?.displayName.orEmpty()
                }
            }
        preset.afterChange { chosen -> if (chosen != null) emit(FontPanelEvent.Preset(chosen)) }
        enable.addActionListener { emit(FontPanelEvent.Enable(enable.isSelected)) }
        family.addActionListener { (family.selectedItem as? String)?.let { emit(FontPanelEvent.Family(it)) } }
        sizeSpinner.addChangeListener { emit(FontPanelEvent.Size((sizeSpinner.value as Number).toFloat())) }
        spacing.addChangeListener { emit(FontPanelEvent.Spacing((spacing.value as Number).toFloat())) }
        weight.addActionListener { (weight.selectedItem as? FontWeight)?.let { emit(FontPanelEvent.Weight(it)) } }
        ligatures.addActionListener { emit(FontPanelEvent.Ligatures(ligatures.isSelected)) }
        console.addActionListener { emit(FontPanelEvent.Console(console.isSelected)) }
        terminal.addActionListener {
            val choice = terminal.selectedItem as? String ?: return@addActionListener
            emit(FontPanelEvent.Terminal(TERMINALS.entries.singleOrNull { it.value == choice }?.key ?: choice))
        }
        add(panel { buildRows() }, BorderLayout.CENTER)
    }

    fun render(view: FontPanelView) {
        isRendering = true
        try {
            rendered = view
            editing.set(view.isEditable)
            enabled.set(view.isEnabled)
            known.set(view.isEnabled && view.selection.settings != null)
            custom.set(view.isEnabled && view.selection.settings?.preset == FontPreset.CUSTOM)
            enable.isSelected = view.isEnabled
            preset.set(view.selection.settings?.preset)
            updateTypography(view)
            updateResources(view)
            val preview = view.preview
            val canPreview = preview != null && usableDimensions(preview.settings)
            sampleVisible.set(canPreview)
            if (canPreview) sample.updatePreview(preview)
        } finally {
            isRendering = false
        }
    }

    private fun Panel.buildRows() {
        row { comment("Set editor font, size, line spacing, and ligatures from a curated preset") }
        row {
            cell(enable)
            actionLink("Reset defaults") { emit(FontPanelEvent.Reset) }
        }.enabledIf(editing)
        row {
            label("Preset")
            segmentedButton<FontPreset?>(FontPreset.entries) { choice -> text = choice?.displayName.orEmpty() }
                .maxButtonsCount(FontPreset.entries.size)
                .bind(preset)
        }.visibleIf(enabled).enabledIf(editing)
        row {
            label("Font:")
            cell(family).resizableColumn().align(Align.FILL)
        }.visibleIf(custom).enabledIf(editing)
        row { cell(summary).resizableColumn() }.visibleIf(enabled)
        row { cell(sample).resizableColumn().align(Align.FILL) }.visibleIf(sampleVisible)
        buildResources()
        collapsibleGroup("Customize") {
            row { cell(typography()).resizableColumn().align(Align.FILL) }
            row {
                cell(ligatures)
                cell(console)
            }
            row {
                actionLink("Reapply preset") { emit(FontPanelEvent.Reapply) }
                    .comment("Applies the current preset to this color scheme when you click Apply.")
            }
        }.visibleIf(known).enabledIf(editing)
    }

    private fun Panel.buildResources() {
        row { cell(notice) }.visibleIf(resourceNotice)
        row {
            actionLink("Install automatically") { emitResource(FontAction.INSTALL) }
            comment("Downloads, installs, and registers the font with no restart")
        }.visibleIf(missing).enabledIf(resourceAllowed)
        row {
            label("Installed ✓")
            actionLink("Reinstall") { emitResource(FontAction.REINSTALL) }
            actionLink("Delete") { emitResource(FontAction.UNINSTALL) }
            comment("Reinstall re-downloads the font; Delete removes the installed files (restart required)")
        }.visibleIf(installed).enabledIf(resourceAllowed)
        row {
            label("Installed file missing")
            actionLink("Reinstall") { emitResource(FontAction.REINSTALL) }
        }.visibleIf(corrupted).enabledIf(resourceAllowed)
        row { cell(brew) }.visibleIf(manual)
        row {
            actionLink("Copy") { selectedEntry()?.let { emit(FontPanelEvent.CopyCommand(it.preset)) } }
            actionLink("Run in Terminal") {
                selectedEntry()?.let { entry ->
                    emit(FontPanelEvent.OpenTerminal(entry.preset, requireNotNull(rendered).resources.terminal))
                }
            }
            cell(terminal)
        }.visibleIf(manual).enabledIf(resourceAllowed)
    }

    private fun typography(): JPanel =
        JPanel(StudioResponsiveLayout()).apply {
            add(JLabel("Size:").apply { labelFor = sizeSpinner })
            add(sizeSpinner)
            add(JLabel("Spacing:").apply { labelFor = spacing })
            add(spacing)
            add(JLabel("Weight:").apply { labelFor = weight })
            add(weight)
        }

    private fun updateTypography(view: FontPanelView) {
        val settings = view.selection.settings
        summary.text =
            if (settings == null) {
                "Preset '${view.selection.rawName}' is unavailable. Choose an available preset to change fonts."
            } else {
                val ligatureText = if (settings.enableLigatures) "ligatures" else "no ligatures"
                val sizeText = settings.fontSize.toString().removeSuffix(".0")
                "Font: ${settings.fontFamily} · ${sizeText}pt · " +
                    "${settings.weight.displayName} · ${settings.lineSpacing}× · $ligatureText"
            }
        if (settings == null) return
        val choices = view.resources.installedFamilies.toMutableList()
        if (settings.fontFamily !in choices) choices.add(settings.fontFamily)
        family.model = DefaultComboBoxModel(choices.toTypedArray())
        family.selectedItem = settings.fontFamily
        sizeSpinner.model = numberModel(settings.fontSize, MIN_SIZE.toDouble(), MAX_SIZE.toDouble(), 1)
        spacing.model = numberModel(settings.lineSpacing, MIN_SPACING, MAX_SPACING, SPACING_STEP)
        ligatures.isSelected = settings.enableLigatures
        console.isSelected = settings.applyToConsole
        weight.selectedItem = settings.weight
    }

    private fun updateResources(view: FontPanelView) {
        val entry = selectedEntry()
        val availability = view.resources.availability
        val curated = view.isEnabled && entry != null
        missing.set(curated && availability == FontAvailability.MISSING_CURATED)
        installed.set(curated && availability == FontAvailability.AVAILABLE)
        corrupted.set(curated && availability == FontAvailability.CORRUPTED)
        manual.set(missing.get() && view.resources.isMac)
        resourceAllowed.set(view.resources.canManage)
        val status = availabilityText(availability, entry?.familyName.orEmpty())
        notice.text = status
        resourceNotice.set(view.isEnabled && view.selection.settings != null && status.isNotEmpty())
        brew.text = entry?.let { "Or via Homebrew: brew install --cask ${it.brewCaskSlug}" }.orEmpty()
        val requestedTerminal = view.resources.terminal ?: "BUILTIN"
        val terminalLabel = TERMINALS[requestedTerminal] ?: requestedTerminal
        val choices = TERMINALS.values.toMutableList()
        if (terminalLabel !in choices) choices.add(terminalLabel)
        terminal.model = DefaultComboBoxModel(choices.toTypedArray())
        terminal.selectedItem = terminalLabel
    }

    private fun emit(event: FontPanelEvent) {
        val view = rendered ?: return
        if (isRendering) return
        val allowed =
            when (event) {
                is FontPanelEvent.Resource, is FontTerminalAction -> view.resources.canManage
                else -> view.isEditable
            }
        if (allowed) onEvent(event)
    }

    private fun emitResource(action: FontAction) {
        val entry = selectedEntry() ?: return
        val view = requireNotNull(rendered)
        if (!view.isEnabled) return
        val permitted =
            when (action) {
                FontAction.INSTALL -> missing.get()
                FontAction.REINSTALL -> installed.get() || corrupted.get()
                FontAction.UNINSTALL -> installed.get()
            }
        if (permitted) emit(FontPanelEvent.Resource(entry.preset, action))
    }

    private fun selectedEntry(): FontCatalog.Entry? =
        rendered
            ?.selection
            ?.settings
            ?.preset
            ?.let(FontCatalog::forPreset)

    private companion object {
        const val MIN_SIZE = 8
        const val MAX_SIZE = 30
        const val MIN_SPACING = 0.8
        const val MAX_SPACING = 2.0
        const val SPACING_STEP = 0.1
        val TERMINALS = linkedMapOf("BUILTIN" to "Built-in Terminal", "SYSTEM" to "System Terminal")
    }
}

private fun numberModel(
    value: Float,
    minimum: Double,
    maximum: Double,
    step: Number,
): SpinnerNumberModel {
    val actual = value.toDouble()
    if (!actual.isFinite()) return SpinnerNumberModel(actual, null, null, step)
    val lower = minOf(minimum, actual)
    val upper = maxOf(maximum, actual)
    return if (step is Int && actual == actual.toInt().toDouble()) {
        SpinnerNumberModel(actual.toInt(), lower.toInt(), upper.toInt(), step)
    } else {
        SpinnerNumberModel(actual, lower, upper, step)
    }
}

private fun usableDimensions(settings: FontSettings): Boolean =
    settings.fontSize.isFinite() && settings.fontSize > 0 && settings.lineSpacing.isFinite() && settings.lineSpacing > 0

private fun literalRenderer(): ColoredListCellRenderer<String> =
    object : ColoredListCellRenderer<String>() {
        override fun customizeCellRenderer(
            list: JList<out String>,
            value: String?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            append(value.orEmpty())
        }
    }

private fun availabilityText(
    availability: FontAvailability,
    family: String,
): String =
    when (availability) {
        FontAvailability.MISSING_CURATED -> "Install $family to use this font."
        FontAvailability.MISSING_CUSTOM -> "Choose an installed font family, or install this font manually."
        FontAvailability.CORRUPTED -> "Reinstall the missing font files to use this font."
        FontAvailability.RESTART_REQUIRED -> "Restart the IDE to use the installed font."
        FontAvailability.UNCONFIRMED -> "Font availability has not been confirmed."
        FontAvailability.AVAILABLE -> ""
    }

/** Row visibility stays authoritative when a disabled action becomes enabled again. */
private fun Row.actionLink(
    text: String,
    action: () -> Unit,
): Cell<ActionLink> = link(text) { action() }.applyToComponent { autoHideOnDisable = false }
