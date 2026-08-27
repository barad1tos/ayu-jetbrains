package dev.ayuislands.settings

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.Project
import com.intellij.ui.InplaceButton
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.SegmentedButton.ItemPresentation
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SYNTAX_INTENSITY_SCHEMA_VERSION
import dev.ayuislands.syntax.SyntaxCellCodec
import dev.ayuislands.syntax.SyntaxCellId
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import org.jetbrains.annotations.TestOnly
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSlider

/**
 * Settings tab — 5-pill preset row matching the Glow / VCS / Font franchise.
 *
 * Every control previews immediately in the active Ayu editor scheme. Apply
 * or OK materializes named schemes before persisting the pending settings;
 * Cancel and Reset restore the session checkpoint without rewriting saved
 * choices. The four named presets are free, while Custom is reserved for Pro.
 *
 * [LicenseChecker] is consulted only while loading premium state, disabling
 * premium controls, and rejecting Custom for free users.
 *
 * Custom shows only primitives confirmed for the selected language. Rows keep
 * fixed category, slider, readout, style, and reset cells. Sparse persisted
 * maps remain additive: unknown or unavailable cells are preserved unchanged,
 * and the readout [JLabel] is presentation-only.
 */
@Suppress("TooManyFunctions", "UnstableApiUsage") // Settings panel with focused UI lifecycle helpers.
class AyuIslandsSyntaxPanel internal constructor(
    resolveLanguage: (Project?, FileType?, String) -> String = SyntaxLanguageResolver()::resolve,
    subscribeProjectLanguage: (Project, () -> Unit) -> (() -> Unit) = ::observeProjectLanguage,
) : SettingsParticipant {
    private val contextLanguage = ContextLanguageController(resolveLanguage, subscribeProjectLanguage)
    private var pendingPreset: SyntaxPreset = SyntaxPreset.AMBIENT
    private var storedPreset: SyntaxPreset = SyntaxPreset.AMBIENT
    private var suppressListeners: Boolean = false
    private var presetSegmented: SegmentedButton<SyntaxPreset>? = null

    // Custom drill-down state. The fold-out captures per-(language, category)
    // overrides; only cells the user actually moves persist (sparse store).
    // Untouched cells inherit the subordinate (last-named) preset at resolve
    // time, so the override map stays small.
    private val customSelected = AtomicBooleanProperty(pendingPreset == SyntaxPreset.CUSTOM)
    private var suppressSliderListeners: Boolean = false
    private var pendingSubordinate: SyntaxPreset = SyntaxPreset.AMBIENT
    private var storedSubordinate: SyntaxPreset = SyntaxPreset.AMBIENT
    private var premiumSyntaxControlsEnabled: Boolean = true
    private var pendingDimComments: Boolean = false
    private var storedDimComments: Boolean = false
    private var pendingSoftenDocumentation: Boolean = false
    private var storedSoftenDocumentation: Boolean = false
    private var pendingQuietOperators: Boolean = false
    private var storedQuietOperators: Boolean = false
    private var pendingEmphasizeDeclarations: Boolean = false
    private var storedEmphasizeDeclarations: Boolean = false
    private val pendingOverrides: MutableMap<String, String> = mutableMapOf()
    private val storedOverrides: MutableMap<String, String> = mutableMapOf()

    // Legacy font-style store: flat "language|category" -> FontStyleOverride
    // enum name. It still round-trips stored configurations, but no longer has
    // row-level controls in the compact Custom grid.
    private val pendingStyles: MutableMap<String, String> = mutableMapOf()
    private val storedStyles: MutableMap<String, String> = mutableMapOf()
    private val pendingEmphasis: MutableMap<String, String> = mutableMapOf()
    private val storedEmphasis: MutableMap<String, String> = mutableMapOf()
    private val controlGrid =
        SyntaxControlGrid(
            identityValue = SLIDER_MID,
            createStyleControl = ::createStyleControl,
            sliderChanged = { category, value -> onSliderChanged(currentLanguage, category, value) },
            sliderReleased = { onSliderReleased() },
            reset = ::resetCell,
            updateReadout = { label, value -> SyntaxIntensityReadout.apply(label, value, SLIDER_MID) },
        )
    private val categoryLabels: MutableMap<PrimitiveCategory, JLabel> = controlGrid.categoryLabels
    private val sliders: MutableMap<PrimitiveCategory, JSlider> = controlGrid.sliders
    private val sliderLabels: MutableMap<PrimitiveCategory, JLabel> = controlGrid.sliderLabels
    private val resetButtons: MutableMap<PrimitiveCategory, InplaceButton> = controlGrid.resetButtons
    private val styleControls: MutableMap<PrimitiveCategory, SyntaxStyleControl> = controlGrid.styleControls
    private val categoryAvailability =
        SyntaxCategoryAvailability(
            categoryLabels = categoryLabels,
            sliders = sliders,
            sliderLabels = sliderLabels,
            resetButtons = resetButtons,
            styleControls = styleControls,
            visibilityChanged = controlGrid::show,
        )
    private var dimCommentsCheckbox: JCheckBox? = null
    private var softenDocumentationCheckbox: JCheckBox? = null
    private var quietOperatorsCheckbox: JCheckBox? = null
    private var emphasizeDeclarationsCheckbox: JCheckBox? = null
    private var masterResetButton: JButton? = null
    private var languageCombo: JComboBox<String>? = null
    private var currentLanguage: String = ""
    private var variant: AyuVariant? = null
    private var syntaxPreview: SyntaxPreviewComponent? = null
    private var capabilityController: SyntaxCapabilityController? = null
    private var editingSession: SyntaxPanelSession? = null
    private val runtimeStatus = RuntimeStatus()
    private val capabilityStatus = CapabilityStatus { capabilityController?.performRecoveryAction() }

    override fun dispose() {
        contextLanguage.dispose()
        languageCombo = null
        editingSession?.dispose()
        editingSession = null
        capabilityController?.dispose()
        styleControls.values.forEach(SyntaxStyleControl::dispose)
        styleControls.clear()
    }

    fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
        contextProject: Project? = null,
        contextFileType: FileType? = null,
    ) {
        this.variant = variant
        capabilityController?.dispose()
        capabilityController = SyntaxCapabilityController(contextProject, ::renderCapability)
        categoryAvailability.refreshCapabilities(variant)
        loadStateIntoPending()
        openEditingSession()
        customSelected.set(pendingPreset == SyntaxPreset.CUSTOM)
        currentLanguage =
            contextLanguage.start(
                project = contextProject,
                fileType = contextFileType,
                fallback = DEFAULT_PREVIEW_LANGUAGE,
                applyDetected = ::applyDetectedLanguage,
            )
        with(panel) {
            buildPresetBlock()

            // The 4 named pills apply immediately. The Custom pill opens the
            // per-language drill-down available on the Pro tier. Free users
            // who pick Custom are reverted to their previous selection and
            // prompted to upgrade.
            row {
                comment(
                    "Custom provides per-language fine tuning. Pick one of the 4 presets to apply instantly.",
                )
            }

            buildReadabilityBlock()

            row {
                cell(runtimeStatus.component).align(AlignX.FILL)
            }

            buildPreviewRow(variant)
            refreshPreview()

            row {
                browserLink(
                    "Per-key tuning in Color Scheme editor",
                    "https://www.jetbrains.com/help/idea/configuring-colors-and-fonts.html",
                )
            }

            buildCustomFoldOut()
        }
        if (pendingPreset == SyntaxPreset.CUSTOM) {
            selectCapabilityLanguage()
        }
    }

    private fun Panel.buildPresetBlock() {
        row("Preset:") {
            val segmented =
                segmentedButton(SyntaxPreset.entries) { preset ->
                    configurePresetPresentation(preset)
                }
            segmented.maxButtonsCount(SyntaxPreset.entries.size)
            segmented.selectedItem = pendingPreset
            segmented.whenItemSelected { preset ->
                if (suppressListeners) return@whenItemSelected
                onPresetChosen(preset)
            }
            presetSegmented = segmented
        }
    }

    private fun ItemPresentation.configurePresetPresentation(preset: SyntaxPreset) {
        text = preset.displayName
        val isPremiumPreset = preset == SyntaxPreset.CUSTOM
        enabled = !isPremiumPreset || premiumSyntaxControlsEnabled
        toolTipText = if (isPremiumPreset && !premiumSyntaxControlsEnabled) PREMIUM_CONTROL_TOOLTIP else null
    }

    /** Build the premium Custom drill-down as two grouped, aligned columns. */
    private fun Panel.buildCustomFoldOut() {
        val languages = SyntaxLanguageRegistry.supportedLanguages().map { it.displayName }
        currentLanguage = currentLanguage.takeIf { it in languages } ?: DEFAULT_PREVIEW_LANGUAGE
        row("Language:") {
            val combo = comboBox(languages).component
            languageCombo = combo
            combo.selectedItem = currentLanguage
            combo.addActionListener {
                val language = combo.selectedItem as? String ?: return@addActionListener
                contextLanguage.select(language)
                currentLanguage = language
                rebindSlidersFor(language)
                refreshMasterResetButton()
                refreshPreview()
                if (customSelected.get()) selectCapabilityLanguage()
            }
            val resetButton =
                button("") { onResetCurrentLanguage() }.component.apply {
                    isVisible = false
                }
            masterResetButton = resetButton
        }.visibleIf(customSelected)

        row {
            cell(capabilityStatus.component).align(AlignX.FILL)
        }.visibleIf(customSelected)

        controlGrid.build(this).visibleIf(customSelected)

        rebindSlidersFor(currentLanguage)
        refreshMasterResetButton()
    }

    private fun Panel.buildReadabilityBlock() {
        row("Readability:") {
            dimCommentsCheckbox =
                checkBox("Dim comments").component.apply {
                    configureReadabilityCheckbox(pendingDimComments) {
                        pendingDimComments = it
                    }
                }
            softenDocumentationCheckbox =
                checkBox("Soften documentation").component.apply {
                    configureReadabilityCheckbox(pendingSoftenDocumentation) {
                        pendingSoftenDocumentation = it
                    }
                }
            quietOperatorsCheckbox =
                checkBox("Quiet operators").component.apply {
                    configureReadabilityCheckbox(pendingQuietOperators) {
                        pendingQuietOperators = it
                    }
                }
            emphasizeDeclarationsCheckbox =
                checkBox("Emphasize declarations").component.apply {
                    configureReadabilityCheckbox(pendingEmphasizeDeclarations) {
                        pendingEmphasizeDeclarations = it
                    }
                }
        }
        row {
            comment("Applies on top of the selected preset. Use Custom for per-language tuning.")
        }
    }

    private fun JCheckBox.configureReadabilityCheckbox(
        selected: Boolean,
        updatePending: (Boolean) -> Unit,
    ) {
        applyReadabilityCheckboxState(selected)
        addActionListener {
            if (!isEnabled) return@addActionListener
            updatePending(isSelected)
            previewDiscrete()
        }
    }

    private fun JCheckBox.applyReadabilityCheckboxState(selected: Boolean) {
        isSelected = selected && premiumSyntaxControlsEnabled
        isEnabled = premiumSyntaxControlsEnabled
        toolTipText = if (premiumSyntaxControlsEnabled) null else PREMIUM_CONTROL_TOOLTIP
    }

    @TestOnly
    internal fun buildReadabilityBlockForTest(panel: Panel) {
        loadStateIntoPending()
        openEditingSession()
        panel.buildReadabilityBlock()
    }

    @TestOnly
    internal fun buildPresetBlockForTest(panel: Panel) {
        loadStateIntoPending()
        customSelected.set(pendingPreset == SyntaxPreset.CUSTOM)
        panel.buildPresetBlock()
        refreshPresetPillAffordance()
    }

    @TestOnly
    internal fun customPresetPresentationForTest(): ItemPresentation? = customPresetPresentation()

    @TestOnly
    internal fun activateCapabilityForTest(probe: SyntaxCapabilityProbe) {
        capabilityController?.replaceProbeForTest(probe)
        selectCapabilityLanguage()
    }

    private fun createStyleControl(category: PrimitiveCategory): SyntaxStyleControl =
        SyntaxStyleControl(
            category = category,
            language = { currentLanguage },
            emphasis = {
                FontEmphasis.fromName(
                    pendingEmphasis[syntaxCellKey(currentLanguage, category)],
                )
            },
            onEmphasisChanged = { emphasis -> onEmphasisChanged(category, emphasis) },
            styleOverride = {
                FontStyleOverride.fromName(
                    pendingStyles[syntaxCellKey(currentLanguage, category)],
                )
            },
            onStyleOverrideChanged = { style -> onStyleOverrideChanged(category, style) },
        )

    private fun selectCapabilityLanguage() {
        capabilityController?.selectLanguage(currentLanguage)
    }

    private fun renderCapability(state: SyntaxCapabilityState?) {
        categoryAvailability.showCapability(state)
        syntaxPreview?.showCapability(state)
        refreshMasterResetButton()
        capabilityStatus.render(state)
    }

    private fun refreshResetVisibility(category: PrimitiveCategory) {
        val key = syntaxCellKey(currentLanguage, category)
        val sliderMoved = (sliders[category]?.value ?: SLIDER_MID) != SLIDER_MID
        val styled = pendingStyles[key] != null
        val emphasized = pendingEmphasis[key] != null
        resetButtons[category]?.isVisible = sliderMoved || styled || emphasized
    }

    private fun resetCell(category: PrimitiveCategory) {
        resetCategorySlider(category)
        val key = syntaxCellKey(currentLanguage, category)
        pendingOverrides.remove(key)
        pendingStyles.remove(key)
        pendingEmphasis.remove(key)
        styleControls[category]?.refreshPresentation()
        refreshResetVisibility(category)
        refreshMasterResetButton()
        previewDiscrete()
    }

    private fun onSliderChanged(
        language: String,
        category: PrimitiveCategory,
        value: Int,
    ) {
        sliderLabels[category]?.let { SyntaxIntensityReadout.apply(it, value, SLIDER_MID) }
        sliders[category]?.accessibleContext?.accessibleName =
            SyntaxIntensityReadout.accessibleName(category, value, SLIDER_MID)
        if (suppressSliderListeners) return
        syntaxPreview?.showPrimitive(category)
        val key = syntaxCellKey(language, category)
        if (value == SLIDER_MID) {
            pendingOverrides.remove(key)
        } else {
            pendingOverrides[key] = value.toString()
        }
        refreshResetVisibility(category)
        refreshMasterResetButton()
        editingSession?.editSlider(pendingConfig())
    }

    private fun onSliderReleased() {
        if (!suppressSliderListeners) editingSession?.sliderReleased()
    }

    private fun onEmphasisChanged(
        category: PrimitiveCategory,
        emphasis: FontEmphasis?,
    ) = updateFontStyleCell(category, pendingEmphasis, emphasis?.name)

    private fun onStyleOverrideChanged(
        category: PrimitiveCategory,
        style: FontStyleOverride?,
    ) = updateFontStyleCell(category, pendingStyles, style?.name)

    private fun updateFontStyleCell(
        category: PrimitiveCategory,
        store: MutableMap<String, String>,
        value: String?,
    ) {
        syntaxPreview?.showPrimitive(category)
        val key = syntaxCellKey(currentLanguage, category)
        if (value == null) store.remove(key) else store[key] = value
        styleControls[category]?.refreshPresentation()
        refreshResetVisibility(category)
        refreshMasterResetButton()
        previewDiscrete()
    }

    private fun resetCategorySlider(category: PrimitiveCategory) {
        suppressSliderListeners = true
        try {
            sliders[category]?.value = SLIDER_MID
            sliderLabels[category]?.let { SyntaxIntensityReadout.apply(it, SLIDER_MID, SLIDER_MID) }
            sliders[category]?.accessibleContext?.accessibleName =
                SyntaxIntensityReadout.accessibleName(category, SLIDER_MID, SLIDER_MID)
            refreshResetVisibility(category)
        } finally {
            suppressSliderListeners = false
        }
    }

    /**
     * Per-language master reset: wipe only the override AND style cells keyed
     * to the active language, snap visible rows back to identity / inherit, and
     * apply so the editor falls back to the subordinate preset for that
     * language. Other languages' cells are left intact.
     */
    private fun onResetCurrentLanguage() {
        val visibleCells =
            categoryAvailability.availableFor(currentLanguage).mapTo(linkedSetOf()) { category ->
                SyntaxCellId(currentLanguage, category.specification.storageId)
            }
        SyntaxCellCodec.removeKnownCells(pendingOverrides, visibleCells)
        SyntaxCellCodec.removeKnownCells(pendingStyles, visibleCells)
        SyntaxCellCodec.removeKnownCells(pendingEmphasis, visibleCells)
        rebindSlidersFor(currentLanguage)
        refreshMasterResetButton()
        previewDiscrete()
    }

    private fun onPresetChosen(preset: SyntaxPreset) {
        // The Custom pill is disabled through the SegmentedButton item
        // presentation, but direct calls and future programmatic selection
        // paths still need the service-level gate.
        if (preset == SyntaxPreset.CUSTOM && !LicenseChecker.isLicensedOrGrace()) {
            suppressListeners = true
            try {
                presetSegmented?.selectedItem = pendingPreset
            } finally {
                suppressListeners = false
            }
            LicenseChecker.requestLicense("Unlock per-language syntax customization")
            return
        }
        pendingPreset = preset
        // A named preset becomes the subordinate Custom layers on; selecting
        // Custom keeps the last-named preset as the inheritance base for
        // untouched cells.
        if (preset != SyntaxPreset.CUSTOM) {
            pendingSubordinate = preset
        }
        customSelected.set(preset == SyntaxPreset.CUSTOM)
        if (preset == SyntaxPreset.CUSTOM) selectCapabilityLanguage()
        previewDiscrete()
    }

    override fun isModified(): Boolean =
        pendingPreset != storedPreset ||
            pendingOverrides != storedOverrides ||
            pendingStyles != storedStyles ||
            pendingEmphasis != storedEmphasis ||
            pendingSubordinate != storedSubordinate ||
            pendingDimComments != storedDimComments ||
            pendingSoftenDocumentation != storedSoftenDocumentation ||
            pendingQuietOperators != storedQuietOperators ||
            pendingEmphasizeDeclarations != storedEmphasizeDeclarations

    override fun apply() {
        if (!isModified()) return
        when (val result = editingSession?.apply(pendingConfig())) {
            null,
            SyntaxCommitResult.Applied,
            -> Unit
            is SyntaxCommitResult.Failed -> throw result.failure
        }
    }

    private fun persist(config: SyntaxPresetConfig) {
        val state = SyntaxIntensityState.getInstance().state
        state.selectedPreset = config.selectedPreset
        state.subordinatePreset = config.subordinatePreset
        state.customOverrides.clear()
        state.customOverrides.putAll(pendingOverrides)
        state.customStyles.clear()
        state.customStyles.putAll(pendingStyles)
        state.customEmphasis.clear()
        state.customEmphasis.putAll(pendingEmphasis)
        val appliedReadabilityOptions = readabilityOptions()
        state.dimComments = appliedReadabilityOptions.dimComments
        state.softenDocumentation = appliedReadabilityOptions.softenDocumentation
        state.quietOperators = appliedReadabilityOptions.quietOperators
        state.emphasizeDeclarations = appliedReadabilityOptions.emphasizeDeclarations
        state.schemaVersion = SYNTAX_INTENSITY_SCHEMA_VERSION
        storedPreset = pendingPreset
        storedSubordinate = pendingSubordinate
        storedOverrides.clear()
        storedOverrides.putAll(pendingOverrides)
        storedStyles.clear()
        storedStyles.putAll(pendingStyles)
        storedEmphasis.clear()
        storedEmphasis.putAll(pendingEmphasis)
        rememberReadabilityOptions(appliedReadabilityOptions)
    }

    override fun reset() {
        when (val result = editingSession?.reset()) {
            null,
            SyntaxRestoreResult.Restored,
            -> Unit
            is SyntaxRestoreResult.Failed -> throw result.failure
        }
        loadStateIntoPending()
        suppressListeners = true
        try {
            presetSegmented?.selectedItem = pendingPreset
        } finally {
            suppressListeners = false
        }
        customSelected.set(pendingPreset == SyntaxPreset.CUSTOM)
        refreshReadabilityCheckboxes()
        refreshPresetPillAffordance()
        rebindSlidersFor(currentLanguage)
        refreshMasterResetButton()
        refreshAfterRuntimeChange()
    }

    override fun cancel() {
        when (val result = editingSession?.cancel()) {
            null,
            SyntaxRestoreResult.Restored,
            -> Unit
            is SyntaxRestoreResult.Failed -> throw result.failure
        }
    }

    private fun loadStateIntoPending() {
        val state = SyntaxIntensityState.getInstance().state
        val loadedPreset = SyntaxPreset.fromName(state.selectedPreset)
        premiumSyntaxControlsEnabled = LicenseChecker.isLicensedOrGrace()
        val canUseCustom = loadedPreset != SyntaxPreset.CUSTOM || premiumSyntaxControlsEnabled
        storedPreset = if (canUseCustom) loadedPreset else SyntaxPreset.AMBIENT
        pendingPreset = storedPreset
        storedSubordinate = SyntaxPreset.fromName(state.subordinatePreset)
        pendingSubordinate = storedSubordinate
        rememberReadabilityOptions(
            SyntaxReadabilityOptions(
                dimComments = state.dimComments,
                softenDocumentation = state.softenDocumentation,
                quietOperators = state.quietOperators,
                emphasizeDeclarations = state.emphasizeDeclarations,
            ),
        )
        storedOverrides.clear()
        storedOverrides.putAll(state.customOverrides)
        pendingOverrides.clear()
        pendingOverrides.putAll(storedOverrides)
        storedStyles.clear()
        storedStyles.putAll(state.customStyles)
        pendingStyles.clear()
        pendingStyles.putAll(storedStyles)
        storedEmphasis.clear()
        storedEmphasis.putAll(state.customEmphasis)
        pendingEmphasis.clear()
        pendingEmphasis.putAll(storedEmphasis)
    }

    private fun refreshReadabilityCheckboxes() {
        dimCommentsCheckbox?.applyReadabilityCheckboxState(pendingDimComments)
        softenDocumentationCheckbox?.applyReadabilityCheckboxState(pendingSoftenDocumentation)
        quietOperatorsCheckbox?.applyReadabilityCheckboxState(pendingQuietOperators)
        emphasizeDeclarationsCheckbox?.applyReadabilityCheckboxState(pendingEmphasizeDeclarations)
    }

    private fun refreshPresetPillAffordance() {
        val segmented = presetSegmented ?: return
        for (preset in SyntaxPreset.entries) {
            segmented.update(preset)
        }
    }

    private fun rememberReadabilityOptions(options: SyntaxReadabilityOptions) {
        storedDimComments = options.dimComments
        pendingDimComments = options.dimComments
        storedSoftenDocumentation = options.softenDocumentation
        pendingSoftenDocumentation = options.softenDocumentation
        storedQuietOperators = options.quietOperators
        pendingQuietOperators = options.quietOperators
        storedEmphasizeDeclarations = options.emphasizeDeclarations
        pendingEmphasizeDeclarations = options.emphasizeDeclarations
    }

    private fun rebindSlidersFor(language: String) {
        suppressSliderListeners = true
        try {
            for (category in PrimitiveCategory.entries) {
                val value = pendingOverrides[syntaxCellKey(language, category)]?.toIntOrNull() ?: SLIDER_MID
                sliders[category]?.value = value
                sliderLabels[category]?.let { SyntaxIntensityReadout.apply(it, value, SLIDER_MID) }
                sliders[category]?.accessibleContext?.accessibleName =
                    SyntaxIntensityReadout.accessibleName(category, value, SLIDER_MID)
                styleControls[category]?.rebind()
                refreshResetVisibility(category)
            }
        } finally {
            suppressSliderListeners = false
        }
        categoryAvailability.refreshRows(language)
    }

    /**
     * Show the master reset button only when the active language has at least
     * one override OR style cell, and track the language in its label. A no-op
     * until the fold-out has materialized the button.
     */
    private fun refreshMasterResetButton() {
        val button = masterResetButton ?: return
        val visibleKeys =
            categoryAvailability.availableFor(currentLanguage).mapTo(hashSetOf()) { category ->
                syntaxCellKey(currentLanguage, category)
            }
        val hasLanguageCustomizations =
            pendingOverrides.keys.any { it in visibleKeys } ||
                pendingStyles.keys.any { it in visibleKeys } ||
                pendingEmphasis.keys.any { it in visibleKeys }
        button.text = "Reset $currentLanguage customizations"
        button.isVisible = hasLanguageCustomizations
        button.isEnabled = hasLanguageCustomizations
    }

    private fun pendingConfig(): SyntaxPresetConfig {
        val nestedOverrides = SyntaxCellCodec.decode(pendingOverrides) { it.toIntOrNull() }
        val nestedStyles = SyntaxCellCodec.decode(pendingStyles) { FontStyleOverride.fromName(it)?.fontType }
        val nestedEmphasis = SyntaxCellCodec.decode(pendingEmphasis) { FontEmphasis.fromName(it)?.fontType }
        return SyntaxPresetConfig(
            selectedPreset = pendingPreset.name,
            customOverrides = nestedOverrides,
            subordinatePreset = pendingSubordinate.name,
            customStyles = nestedStyles,
            readabilityOptions = readabilityOptions(),
            customEmphasis = nestedEmphasis,
        )
    }

    private fun openEditingSession() {
        editingSession?.dispose()
        editingSession =
            SyntaxPanelSession(
                initialCheckpoint = pendingConfig(),
                persist = ::persist,
                onRuntimeApplied = {
                    runtimeStatus.applied()
                    refreshAfterRuntimeChange()
                },
                onRuntimeFailed = runtimeStatus::failed,
                onRelinquished = runtimeStatus::relinquished,
                onForeignScheme = runtimeStatus::foreignScheme,
            )
    }

    private fun previewDiscrete() {
        editingSession?.editDiscrete(pendingConfig())
    }

    private fun refreshAfterRuntimeChange() {
        variant?.let { categoryAvailability.refreshCapabilities(it) }
        categoryAvailability.refreshRows(currentLanguage)
        refreshPreview()
    }

    private fun Panel.buildPreviewRow(variant: AyuVariant) {
        row {
            val preview = SyntaxPreviewComponent(variant, currentLanguage)
            syntaxPreview = preview
            cell(preview)
                .resizableColumn()
                .align(Align.FILL)
        }
    }

    private fun refreshPreview() {
        val v = variant ?: return
        syntaxPreview?.updatePreview(
            variant = v,
            language = currentLanguage,
        )
    }

    private fun applyDetectedLanguage(detectedLanguage: String) {
        currentLanguage = detectedLanguage
        languageCombo?.selectedItem = detectedLanguage
    }

    private fun readabilityOptions(): SyntaxReadabilityOptions =
        SyntaxReadabilityOptions(
            dimComments = pendingDimComments,
            softenDocumentation = pendingSoftenDocumentation,
            quietOperators = pendingQuietOperators,
            emphasizeDeclarations = pendingEmphasizeDeclarations,
        )

    private fun customPresetPresentation(): ItemPresentation? {
        val segmented = presetSegmented ?: return null
        val getPresentations =
            segmented.javaClass.methods.firstOrNull { method ->
                method.name == SEGMENTED_PRESENTATIONS_METHOD && method.parameterCount == 0
            } ?: return null
        val presentationMap = getPresentations.invoke(segmented) as? Map<*, *> ?: return null
        return presentationMap[SyntaxPreset.CUSTOM] as? ItemPresentation
    }

    private companion object {
        private const val SLIDER_MID = 50
        private const val PREMIUM_CONTROL_TOOLTIP = "Pro Feature"
        private const val DEFAULT_PREVIEW_LANGUAGE = "Kotlin"
        private const val SEGMENTED_PRESENTATIONS_METHOD =
            "getPresentations" + "$" + "intellij_platform_ide_impl"
    }
}

private fun syntaxCellKey(
    language: String,
    category: PrimitiveCategory,
): String = "$language|${category.specification.storageId}"
