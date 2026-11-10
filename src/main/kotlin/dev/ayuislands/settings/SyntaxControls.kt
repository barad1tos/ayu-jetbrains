package dev.ayuislands.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxCellId
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import dev.ayuislands.syntax.SyntaxPreset
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel

/** Shared native controls; requested choices, capability observations and sample schemes are explicit inputs. */
internal class SyntaxControls(
    initialPreview: SyntaxPreviewInput,
    private val onEvent: (SyntaxPanelEvent) -> Unit,
) : JPanel(BorderLayout()),
    Disposable {
    private var rendered: SyntaxPanelView? = null
    private var isRendering = false
    private val editing = AtomicBooleanProperty(false)
    private val premiumEditing = AtomicBooleanProperty(false)
    private val customSelected = AtomicBooleanProperty(false)
    private val selectedPreset = AtomicProperty(SyntaxPreset.AMBIENT)
    private var presetSegmented: SegmentedButton<SyntaxPreset>? = null
    private val dimCommentsCheckbox = JCheckBox("Dim comments")
    private val softenDocumentationCheckbox = JCheckBox("Soften documentation")
    private val quietOperatorsCheckbox = JCheckBox("Quiet operators")
    private val emphasizeDeclarationsCheckbox = JCheckBox("Emphasize declarations")
    private val languageCombo =
        ComboBox(SyntaxLanguageRegistry.supportedLanguages().map { it.displayName }.toTypedArray())
    private val masterResetButton = JButton()
    private val runtimeStatus = JLabel().apply { putClientProperty("html.disable", true) }
    private val capabilityStatus = CapabilityStatus { emit(SyntaxPanelEvent.RecoverSupport) }
    private val syntaxPreview = SyntaxPreviewComponent(initialPreview.variant, scheme = initialPreview.scheme)
    private val controlGrid =
        SyntaxControlGrid(
            identityValue = IDENTITY,
            createStyleControl = ::createStyleControl,
            sliderChanged = ::onSliderChanged,
            sliderReleased = { emit(SyntaxPanelEvent.SliderReleased) },
            reset = { category -> rendered?.let { emit(SyntaxPanelEvent.ResetCell(it.language, category)) } },
            updateReadout = { label, value -> SyntaxIntensityReadout.apply(label, value, IDENTITY) },
        )
    private val categoryAvailability =
        SyntaxCategoryAvailability(
            controlGrid.categoryLabels,
            controlGrid.sliders,
            controlGrid.sliderLabels,
            controlGrid.resetButtons,
            controlGrid.styleControls,
            controlGrid::show,
        )

    init {
        Disposer.register(this, syntaxPreview)
        selectedPreset.afterChange { emit(SyntaxPanelEvent.Preset(it)) }
        dimCommentsCheckbox.addActionListener { emit(SyntaxPanelEvent.DimComments(dimCommentsCheckbox.isSelected)) }
        softenDocumentationCheckbox.addActionListener {
            emit(SyntaxPanelEvent.SoftenDocumentation(softenDocumentationCheckbox.isSelected))
        }
        quietOperatorsCheckbox.addActionListener {
            emit(
                SyntaxPanelEvent.QuietOperators(quietOperatorsCheckbox.isSelected),
            )
        }
        emphasizeDeclarationsCheckbox.addActionListener {
            emit(SyntaxPanelEvent.EmphasizeDeclarations(emphasizeDeclarationsCheckbox.isSelected))
        }
        languageCombo.addActionListener {
            (languageCombo.selectedItem as? String)?.let { emit(SyntaxPanelEvent.Language(it)) }
        }
        masterResetButton.addActionListener {
            rendered?.let { emit(SyntaxPanelEvent.ResetLanguage(it.language, availableFor(it.language))) }
        }
        add(panel { buildContent() }, BorderLayout.CENTER)
    }

    fun render(
        view: SyntaxPanelView,
        preview: SyntaxPreviewInput,
    ) {
        val changedLanguage = rendered?.language != view.language
        isRendering = true
        try {
            rendered = view
            editing.set(view.isEditable)
            premiumEditing.set(view.isEditable && view.isLicensed)
            val preset = SyntaxPreset.fromName(view.settings.selectedPreset)
            customSelected.set(preset == SyntaxPreset.CUSTOM)
            selectedPreset.set(preset)
            SyntaxPreset.entries.forEach { presetSegmented?.update(it) }
            renderReadability(view)
            languageCombo.selectedItem = view.language
            renderCategories(view, changedLanguage)
            runtimeStatus.text = view.status?.message.orEmpty()
            runtimeStatus.toolTipText = view.status?.detail
            runtimeStatus.isVisible = view.status != null
            capabilityStatus.render(view.capability)
            syntaxPreview.updatePreview(preview.variant, view.language, preview.scheme)
            syntaxPreview.showCapability(view.capability)
        } finally {
            isRendering = false
        }
    }

    fun availableFor(language: String): Set<PrimitiveCategory> = categoryAvailability.availableFor(language)

    fun showPrimitive(category: PrimitiveCategory) = syntaxPreview.showPrimitive(category)

    override fun dispose() {
        controlGrid.styleControls.values.forEach(SyntaxStyleControl::dispose)
    }

    private fun Panel.buildContent() {
        row("Preset:") {
            presetSegmented =
                segmentedButton(SyntaxPreset.entries) { preset ->
                    text = preset.displayName
                    enabled = preset != SyntaxPreset.CUSTOM || rendered?.isLicensed == true
                    toolTipText = if (enabled) null else PREMIUM_TOOLTIP
                }.maxButtonsCount(SyntaxPreset.entries.size).bind(selectedPreset)
        }.enabledIf(editing)
        row { comment("Custom provides per-language fine tuning. Pick one of the 4 presets to preview it.") }
        row("Readability:") {
            cell(
                JPanel(WrapLayout(FlowLayout.LEADING, JBUI.scale(READABILITY_GAP), 0)).apply {
                    isOpaque = false
                    add(dimCommentsCheckbox)
                    add(softenDocumentationCheckbox)
                    add(quietOperatorsCheckbox)
                    add(emphasizeDeclarationsCheckbox)
                },
            ).resizableColumn().align(Align.FILL)
        }
        row { comment("Applies on top of the selected preset. Use Custom for per-language tuning.") }
        row { cell(runtimeStatus).align(Align.FILL) }
        row { cell(syntaxPreview).resizableColumn().align(Align.FILL) }
        row { link("Per-key tuning in Color Scheme editor") { emit(SyntaxPanelEvent.OpenHelp) } }
        collapsibleGroup("Language Customization") {
            row("Language:") {
                cell(languageCombo)
                cell(masterResetButton)
            }
            row { cell(capabilityStatus.component).align(Align.FILL) }
            controlGrid.build(this)
        }.visibleIf(customSelected)
    }

    private fun renderReadability(view: SyntaxPanelView) {
        val options = view.settings.readability
        dimCommentsCheckbox.isSelected = options.dimComments && view.isLicensed
        softenDocumentationCheckbox.isSelected = options.softenDocumentation && view.isLicensed
        quietOperatorsCheckbox.isSelected = options.quietOperators && view.isLicensed
        emphasizeDeclarationsCheckbox.isSelected = options.emphasizeDeclarations && view.isLicensed
        val tooltip = if (view.isLicensed) null else PREMIUM_TOOLTIP
        listOf(dimCommentsCheckbox, softenDocumentationCheckbox, quietOperatorsCheckbox, emphasizeDeclarationsCheckbox)
            .forEach {
                it.toolTipText = tooltip
                it.isEnabled = view.isEditable && view.isLicensed
            }
    }

    private fun renderCategories(
        view: SyntaxPanelView,
        changedLanguage: Boolean,
    ) {
        val settings = view.settings
        for (category in PrimitiveCategory.entries) {
            val key = cellKey(view.language, category)
            val value = settings.customOverrides[key]?.toIntOrNull() ?: IDENTITY
            controlGrid.sliders[category]?.apply {
                this.value = value
                accessibleContext.accessibleName = SyntaxIntensityReadout.accessibleName(category, value, IDENTITY)
            }
            controlGrid.sliderLabels[category]?.let { SyntaxIntensityReadout.apply(it, value, IDENTITY) }
            controlGrid.styleControls[category]?.let {
                if (changedLanguage) it.rebind() else it.refreshPresentation()
            }
            controlGrid.resetButtons[category]?.isVisible =
                value != IDENTITY ||
                key in settings.customStyles ||
                key in settings.customEmphasis
        }
        categoryAvailability.refreshCapabilities(view.actuatedByLanguage)
        categoryAvailability.showCapability(view.capability)
        categoryAvailability.refreshRows(view.language, premiumEditing.get())
        val visibleKeys = availableFor(view.language).mapTo(hashSetOf()) { cellKey(view.language, it) }
        val hasCustomizations =
            listOf(settings.customOverrides, settings.customStyles, settings.customEmphasis).any { values ->
                values.keys.any { it in visibleKeys }
            }
        masterResetButton.text = "Reset ${view.language} customizations"
        masterResetButton.isVisible = hasCustomizations
        masterResetButton.isEnabled = hasCustomizations && premiumEditing.get()
    }

    private fun createStyleControl(category: PrimitiveCategory): SyntaxStyleControl =
        SyntaxStyleControl(
            category,
            language = { rendered?.language.orEmpty() },
            emphasis = {
                rendered?.let { FontEmphasis.fromName(it.settings.customEmphasis[cellKey(it.language, category)]) }
            },
            onEmphasisChanged = { emphasis ->
                showPrimitive(category)
                rendered?.let { emit(SyntaxPanelEvent.Emphasis(it.language, category, emphasis)) }
            },
            styleOverride = {
                rendered?.let { FontStyleOverride.fromName(it.settings.customStyles[cellKey(it.language, category)]) }
            },
            onStyleOverrideChanged = { style ->
                showPrimitive(category)
                rendered?.let { emit(SyntaxPanelEvent.Style(it.language, category, style)) }
            },
        )

    private fun onSliderChanged(
        category: PrimitiveCategory,
        value: Int,
    ) {
        if (isRendering) return
        val view = rendered ?: return
        showPrimitive(category)
        emit(SyntaxPanelEvent.Intensity(view.language, category, value))
    }

    private fun emit(event: SyntaxPanelEvent) {
        if (isRendering) return
        val view = rendered ?: return
        val permitted =
            when (event) {
                is SyntaxPanelEvent.Language,
                is SyntaxPanelEvent.ShowPrimitive,
                SyntaxPanelEvent.RecoverSupport,
                SyntaxPanelEvent.OpenHelp,
                SyntaxPanelEvent.Unlock,
                -> true

                is SyntaxPanelEvent.Preset -> view.isEditable && (event.value != SyntaxPreset.CUSTOM || view.isLicensed)

                else -> view.isEditable && view.isLicensed
            }
        if (permitted) onEvent(event)
    }

    private companion object {
        const val IDENTITY = 50
        const val PREMIUM_TOOLTIP = "Pro Feature"
        const val READABILITY_GAP = 8
    }
}

private fun cellKey(
    language: String,
    category: PrimitiveCategory,
): String = SyntaxCellId(language, category.specification.storageId).encode()
