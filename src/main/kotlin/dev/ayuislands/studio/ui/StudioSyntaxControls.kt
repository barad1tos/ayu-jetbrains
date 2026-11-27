package dev.ayuislands.studio.ui

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.WrapLayout
import dev.ayuislands.settings.CapabilityStatus
import dev.ayuislands.settings.SyntaxCategoryAvailability
import dev.ayuislands.settings.SyntaxControlGrid
import dev.ayuislands.settings.SyntaxIntensityReadout
import dev.ayuislands.settings.SyntaxStyleControl
import dev.ayuislands.studio.model.StudioSyntaxEdit
import dev.ayuislands.studio.model.StudioSyntaxReadability
import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SYNTAX_SLIDER_MIDPOINT
import dev.ayuislands.syntax.SyntaxCellId
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import dev.ayuislands.syntax.SyntaxSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/** Stable native controls with explicit draft and language capability inputs. */
internal class StudioSyntaxControls(
    private var languageExpanded: Boolean,
    private val onAction: (StudioSyntaxAction) -> Unit,
    private val onEdit: (StudioSyntaxEdit) -> Unit,
) : JPanel(BorderLayout()) {
    private var settings: SyntaxSettings? = null
    private var selectedLanguage = ""
    private var isRendering = false
    private val editable = AtomicBooleanProperty(false)
    private val locked = AtomicBooleanProperty(false)
    private val customSelected = AtomicBooleanProperty(false)
    private val presets =
        StudioChoiceButtons(SyntaxPreset.entries, SyntaxPreset::displayName, ::selectPreset).apply {
            layout = WrapLayout(FlowLayout.LEADING, 0, 0)
        }
    private val readability = ReadabilityControl.entries.associateWith { JBCheckBox(it.title) }
    private val languageCombo =
        ComboBox(SyntaxLanguageRegistry.supportedLanguages().map { it.displayName }.toTypedArray())
    private val resetLanguage = JButton()
    private val support = CapabilityStatus { onAction(StudioSyntaxAction.RecoverSupport) }
    private val grid =
        SyntaxControlGrid(
            identityValue = SYNTAX_SLIDER_MIDPOINT,
            createStyleControl = ::styleControl,
            sliderChanged = { category, value ->
                editCell(category) { StudioSyntaxEdit.Intensity(it, category, value) }
            },
            sliderReleased = {},
            reset = { category -> editCell(category) { StudioSyntaxEdit.Reset(it, setOf(category)) } },
            updateReadout = { label, value -> SyntaxIntensityReadout.apply(label, value, SYNTAX_SLIDER_MIDPOINT) },
        )
    private val availability =
        SyntaxCategoryAvailability(
            grid.categoryLabels,
            grid.sliders,
            grid.sliderLabels,
            grid.resetButtons,
            grid.styleControls,
            grid::show,
        )

    init {
        bindGestures()
        add(panel { buildContent() }, BorderLayout.CENTER)
    }

    fun render(
        requested: SyntaxSettings,
        view: StudioSyntaxView,
    ) {
        val languageChanged = selectedLanguage != view.language
        isRendering = true
        try {
            selectedLanguage = view.language
            settings = requested
            editable.set(view.isEditable)
            locked.set(!view.isLicensed)
            val preset = SyntaxPreset.fromName(requested.selectedPreset)
            presets.render(preset, available = ::canSelectPreset, tooltip = ::presetTooltip)
            customSelected.set(preset == SyntaxPreset.CUSTOM)
            renderReadability(requested.readability)
            languageCombo.selectedItem = selectedLanguage
            val capability = view.capability.takeIf { it.languageId == selectedLanguage }
            support.render(capability)
            availability.render(selectedLanguage, capability, view.actuatedCategories, canEditCustom())
            renderCells(requested, languageChanged)
        } finally {
            isRendering = false
        }
    }

    fun closeStylePopups() = grid.styleControls.values.forEach(SyntaxStyleControl::dispose)

    private fun selectPreset(preset: SyntaxPreset) {
        if (!isRendering && canSelectPreset(preset)) onEdit(StudioSyntaxEdit.Preset(preset))
    }

    private fun canSelectPreset(preset: SyntaxPreset): Boolean =
        editable.get() && (preset != SyntaxPreset.CUSTOM || !locked.get())

    private fun presetTooltip(preset: SyntaxPreset): String? =
        if (preset == SyntaxPreset.CUSTOM && locked.get()) PRO_TOOLTIP else null

    private fun bindGestures() {
        for ((control, checkBox) in readability) {
            checkBox.addActionListener {
                if (!isRendering && checkBox.isEnabled) {
                    onEdit(StudioSyntaxEdit.Readability(control.option, checkBox.isSelected))
                }
            }
        }
        languageCombo.addActionListener {
            if (!isRendering && customSelected.get() && languageExpanded) {
                val language = languageCombo.selectedItem as? String ?: return@addActionListener
                onAction(StudioSyntaxAction.SelectLanguage(language))
            }
        }
        resetLanguage.addActionListener {
            if (canEditLanguage() && resetLanguage.isVisible) {
                onEdit(StudioSyntaxEdit.Reset(selectedLanguage, availability.availableFor(selectedLanguage)))
            }
        }
    }

    private fun Panel.buildContent() {
        row("Preset:") { cell(presets).resizableColumn().align(Align.FILL) }
        row("Readability:") {
            val spacing = StudioUiTokens.spacing()
            cell(
                JPanel(WrapLayout(FlowLayout.LEADING, spacing.fieldGap, 0)).apply {
                    isOpaque = false
                    readability.values.forEach(::add)
                },
            ).resizableColumn().align(Align.FILL)
        }
        row { link("Unlock Pro") { if (locked.get()) onAction(StudioSyntaxAction.Unlock) } }.visibleIf(locked)
        row { link("Per-key tuning in Color Scheme editor") { onAction(StudioSyntaxAction.OpenColorScheme) } }
        val languageGroup =
            collapsibleGroup("Language Customization") {
                row("Language:") {
                    cell(languageCombo)
                    cell(resetLanguage)
                }
                row { cell(support.component).align(Align.FILL) }
                grid.buildSingleColumn(this)
            }
        languageGroup.expanded = languageExpanded
        languageGroup.addExpandedListener {
            languageExpanded = it
            if (!it) closeStylePopups()
            onAction(StudioSyntaxAction.DiscloseLanguage(it))
        }
        languageGroup.visibleIf(customSelected)
    }

    private fun renderReadability(options: SyntaxReadabilityOptions) {
        for ((control, checkBox) in readability) {
            checkBox.isSelected = control.selected(options)
            checkBox.isEnabled = editable.get() && !locked.get()
            checkBox.toolTipText = if (locked.get()) PRO_TOOLTIP else null
        }
    }

    private fun renderCells(
        requested: SyntaxSettings,
        languageChanged: Boolean,
    ) {
        val available = availability.availableFor(selectedLanguage)
        var hasCustomizations = false
        for ((category, slider) in grid.sliders) {
            val key = cellKey(category)
            val value = requested.customOverrides[key]?.toIntOrNull() ?: SYNTAX_SLIDER_MIDPOINT
            slider.value = value.coerceIn(slider.minimum, slider.maximum)
            slider.accessibleContext.accessibleName =
                SyntaxIntensityReadout.accessibleName(category, slider.value, SYNTAX_SLIDER_MIDPOINT)
            SyntaxIntensityReadout.apply(grid.sliderLabels.getValue(category), slider.value, SYNTAX_SLIDER_MIDPOINT)
            val style = grid.styleControls.getValue(category)
            if (languageChanged) style.rebind() else style.refreshPresentation()
            val hasStoredChoice =
                key in requested.customOverrides || key in requested.customStyles || key in requested.customEmphasis
            val canReset = category in available && hasStoredChoice
            grid.resetButtons.getValue(category).isVisible = canReset
            hasCustomizations = hasCustomizations || canReset
        }
        resetLanguage.text = "Reset $selectedLanguage customizations"
        resetLanguage.isVisible = hasCustomizations
        resetLanguage.isEnabled = hasCustomizations && canEditCustom()
    }

    private fun styleControl(category: PrimitiveCategory): SyntaxStyleControl =
        SyntaxStyleControl(
            category,
            language = { selectedLanguage },
            emphasis = { readEmphasis(category) },
            onEmphasisChanged = { value ->
                if (value != null || readEmphasis(category) != null) {
                    editCell(category) { StudioSyntaxEdit.Emphasis(it, category, value) }
                }
            },
            styleOverride = { readStyle(category) },
            onStyleOverrideChanged = { value ->
                if (value != null || readStyle(category) != null) {
                    editCell(category) { StudioSyntaxEdit.Style(it, category, value) }
                }
            },
        )

    private fun readEmphasis(category: PrimitiveCategory): FontEmphasis? =
        FontEmphasis.fromName(settings?.let { it.customEmphasis[cellKey(category)] })

    private fun readStyle(category: PrimitiveCategory): FontStyleOverride? =
        FontStyleOverride.fromName(settings?.let { it.customStyles[cellKey(category)] })

    private fun cellKey(category: PrimitiveCategory): String =
        SyntaxCellId(selectedLanguage, category.specification.storageId).encode()

    private fun canEditCustom(): Boolean = editable.get() && !locked.get() && customSelected.get()

    private fun canEditLanguage(): Boolean = !isRendering && languageExpanded && canEditCustom()

    private inline fun editCell(
        category: PrimitiveCategory,
        edit: (String) -> StudioSyntaxEdit,
    ) {
        if (!canEditLanguage()) return
        if (category in availability.availableFor(selectedLanguage)) onEdit(edit(selectedLanguage))
    }
}

private enum class ReadabilityControl(
    val option: StudioSyntaxReadability,
    val title: String,
    val selected: (SyntaxReadabilityOptions) -> Boolean,
) {
    DIM_COMMENTS(StudioSyntaxReadability.DIM_COMMENTS, "Dim comments", SyntaxReadabilityOptions::dimComments),
    SOFTEN_DOCUMENTATION(
        StudioSyntaxReadability.SOFTEN_DOCUMENTATION,
        "Soften documentation",
        SyntaxReadabilityOptions::softenDocumentation,
    ),
    QUIET_OPERATORS(
        StudioSyntaxReadability.QUIET_OPERATORS,
        "Quiet operators",
        SyntaxReadabilityOptions::quietOperators,
    ),
    EMPHASIZE_DECLARATIONS(
        StudioSyntaxReadability.EMPHASIZE_DECLARATIONS,
        "Emphasize declarations",
        SyntaxReadabilityOptions::emphasizeDeclarations,
    ),
}

private const val PRO_TOOLTIP = "Requires Ayu Islands Pro"
