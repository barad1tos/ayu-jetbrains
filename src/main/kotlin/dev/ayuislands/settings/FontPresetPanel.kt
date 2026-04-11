package dev.ayuislands.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontInstallConsent
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontStatus
import dev.ayuislands.font.FontUninstaller
import dev.ayuislands.font.FontWeight
import java.awt.datatransfer.StringSelection
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** Font preset tab — curated Nerd Font presets with one-click apply. */
class FontPresetPanel : AyuIslandsSettingsPanel {
    private companion object {
        val IS_MAC: Boolean = System.getProperty("os.name").lowercase().contains("mac")
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 30
        const val MIN_LINE_SPACING = 0.8
        const val MAX_LINE_SPACING = 2.0
        const val LINE_SPACING_STEP = 0.1
    }

    // Core state
    private var pendingEnabled = false
    private var storedEnabled = false
    private var pendingPreset = FontPreset.AMBIENT.name
    private var storedPreset = FontPreset.AMBIENT.name
    private var pendingConsole = false
    private var storedConsole = false

    // Per-preset customizations (live working copy)
    private val customizations = mutableMapOf<String, FontSettings>()
    private var storedCustomizations = mapOf<String, String>()

    // Derived from current preset's customization
    private val currentSettings: FontSettings
        get() =
            customizations.getOrPut(pendingPreset) {
                FontSettings.fromPreset(FontPreset.fromName(pendingPreset))
            }

    // UI components
    private var enabledCheckbox: JCheckBox? = null
    private var presetSegmented: SegmentedButton<FontPreset>? = null
    private var fontFamilyCombo: ComboBox<String>? = null
    private var consoleCheckbox: JCheckBox? = null
    private var warningLabel: JLabel? = null
    private var installHintLabel: JLabel? = null
    private var previewComponent: FontPreviewComponent? = null
    private var sizeSpinner: JSpinner? = null
    private var spacingSpinner: JSpinner? = null
    private var ligaturesCheckbox: JCheckBox? = null
    private var weightCombo: ComboBox<FontWeight>? = null
    private var summaryLabel: JLabel? = null
    private var suppressListeners = false

    private val presetEnabled = AtomicBooleanProperty(false)
    private val fontMissing = AtomicBooleanProperty(false)
    private val fontInstalled = AtomicBooleanProperty(false)
    private val fontCorrupted = AtomicBooleanProperty(false)
    private val isCustomSelected = AtomicBooleanProperty(false)
    private val customFontVisible = AtomicBooleanProperty(false)

    private lateinit var availability: Map<FontPreset, Boolean>

    private fun refreshCustomFontVisible() {
        customFontVisible.set(presetEnabled.get() && isCustomSelected.get())
    }

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        initState()
        buildFontTab(panel)
    }

    fun initState() {
        val state = AyuIslandsSettings.getInstance().state

        // Migrate legacy preset names before reading
        val migratedPreset = FontPreset.fromName(state.fontPresetName)
        if (migratedPreset.name != state.fontPresetName) {
            state.fontPresetName = migratedPreset.name
        }
        FontPreset.migrateCustomizations(state.fontPresetCustomizations)

        storedEnabled = state.fontPresetEnabled
        pendingEnabled = storedEnabled
        storedPreset = state.fontPresetName ?: FontPreset.AMBIENT.name
        pendingPreset = storedPreset
        storedConsole = state.fontApplyToConsole
        pendingConsole = storedConsole

        // Load per-preset customizations from state
        storedCustomizations = state.fontPresetCustomizations.toMap()
        customizations.clear()
        for (preset in FontPreset.entries) {
            val encoded = storedCustomizations[preset.name]
            customizations[preset.name] = FontSettings.decode(encoded, preset)
        }

        presetEnabled.set(pendingEnabled)
        isCustomSelected.set(pendingPreset == FontPreset.CUSTOM.name)
        refreshCustomFontVisible()
        FontDetector.invalidateCache()
        availability = FontDetector.detectAll()
        updateFontMissing()
    }

    fun buildFontTab(panel: Panel) {
        panel.apply {
            buildEnableRow()
            buildPresetSelectorRow()
            buildFontFamilyRow()
            buildSummaryRow()
            buildPreviewRow()
            buildWarningRow()
            buildInstallHintRow()
            buildCustomizeGroup()
        }
    }

    private fun Panel.buildEnableRow() {
        row { comment("Set editor font, size, line spacing, and ligatures from a curated preset") }
        row {
            val cb = checkBox("Apply font preset")
            cb.component.isSelected = pendingEnabled
            cb.component.addActionListener {
                pendingEnabled = cb.component.isSelected
                presetEnabled.set(pendingEnabled)
                refreshCustomFontVisible()
                updateFontMissing()
            }
            enabledCheckbox = cb.component

            link("Reset defaults") {
                pendingPreset = FontPreset.AMBIENT.name
                pendingConsole = false

                customizations.clear()
                for (preset in FontPreset.entries) {
                    customizations[preset.name] = FontSettings.fromPreset(preset)
                }

                suppressListeners = true
                presetSegmented?.selectedItem = FontPreset.AMBIENT
                consoleCheckbox?.isSelected = false
                isCustomSelected.set(false)
                refreshCustomFontVisible()
                loadControlsFromPreset()
                updateFontMissing()
                suppressListeners = false
            }
        }
    }

    @Suppress("UnstableApiUsage")
    private fun Panel.buildPresetSelectorRow() {
        row {
            label("Preset")
            val segmented = segmentedButton(FontPreset.entries) { preset -> text = preset.displayName }
            segmented.maxButtonsCount(FontPreset.entries.size)
            segmented.selectedItem = FontPreset.fromName(pendingPreset)
            segmented.whenItemSelected { preset ->
                if (!suppressListeners) {
                    pendingPreset = preset.name
                    isCustomSelected.set(preset == FontPreset.CUSTOM)
                    refreshCustomFontVisible()
                    loadControlsFromPreset()
                    updateFontMissing()
                }
            }
            presetSegmented = segmented
        }.visibleIf(presetEnabled)
    }

    private fun Panel.buildFontFamilyRow() {
        row {
            label("Font:")
            val monoFonts = FontDetector.listMonospaceFonts().toTypedArray()
            val combo = ComboBox(monoFonts)
            combo.selectedItem = currentSettings.fontFamily
            combo.addActionListener {
                if (!suppressListeners) {
                    val selected = combo.selectedItem as? String ?: return@addActionListener
                    updateCustomFontFamily(selected)
                }
            }
            fontFamilyCombo = combo
            cell(combo)
        }.visibleIf(customFontVisible)
    }

    private fun Panel.buildSummaryRow() {
        row {
            val label = JLabel()
            updateSummaryText(label)
            summaryLabel = label
            cell(label)
        }.visibleIf(presetEnabled)
    }

    private fun updateSummaryText(label: JLabel) {
        val s = currentSettings
        val lig = if (s.enableLigatures) "ligatures" else "no ligatures"
        label.text = "${s.fontFamily} · ${s.fontSize.toInt()}pt · " +
            "${s.weight.displayName} · ${s.lineSpacing}× · $lig"
    }

    private fun Panel.buildPreviewRow() {
        row {
            val preview = FontPreviewComponent()
            previewComponent = preview
            refreshPreview(reloadPreset = true)
            cell(preview)
        }.visibleIf(presetEnabled)
    }

    private fun Panel.buildWarningRow() {
        row {
            val label = JLabel()
            label.text = "\u26A0 Requires ${FontPreset.fromName(pendingPreset).fontFamily}"
            warningLabel = label
            cell(label)
        }.visibleIf(fontMissing)
    }

    /**
     * Consent-gated lifecycle action (D-04 fix + D-07 + D-12).
     *
     * Handles both install and uninstall paths because both (a) share the same
     * post-action refresh boilerplate and (b) both must run through FontInstallConsent
     * synchronously BEFORE touching the filesystem. Collapsing into one helper keeps
     * FontPresetPanel under the detekt TooManyFunctions threshold.
     *
     * Install path is used by "Install automatically" on the missing row and by
     * "Reinstall" on both the healthy and corrupted rows — D-12 requires every
     * reinstall to be user-triggered through this same consent helper.
     */
    private fun triggerLifecycleAction(uninstall: Boolean) {
        val preset = FontPreset.fromName(pendingPreset)
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        val entry = FontCatalog.forPreset(preset)
        val refresh = {
            ApplicationManager.getApplication().invokeLater {
                FontDetector.invalidateCache()
                availability = FontDetector.detectAll()
                updateFontMissing()
            }
            Unit
        }
        if (uninstall) {
            val absPath = FontInstaller.platformFontDir().absolutePath
            if (!FontInstallConsent.confirmUninstall(entry, project, absPath)) return
            FontUninstaller.uninstall(preset, project) { refresh() }
        } else {
            if (!FontInstallConsent.confirmInstall(entry, project, compact = true)) return
            FontInstaller.install(preset, project) { refresh() }
        }
    }

    private fun Panel.buildInstallHintRow() {
        // D-04 fix: install path is consent-gated via FontInstallConsent.
        // Previous behavior called FontInstaller.install() directly with no dialog,
        // violating "never touch userspace without consent".
        row {
            link("Install automatically") { triggerLifecycleAction(uninstall = false) }
            comment("Downloads, installs, and registers the font with no restart")
        }.visibleIf(fontMissing)

        // D-02: installed-healthy row with Reinstall + Delete actions (mutually exclusive
        // with fontMissing via the three-way status() snapshot in updateFontMissing).
        row {
            label("Installed ✓")
            link("Reinstall") { triggerLifecycleAction(uninstall = false) }
            link("Delete") { triggerLifecycleAction(uninstall = true) }
            comment("Reinstall re-downloads the font; Delete removes the installed files (restart required)")
        }.visibleIf(fontInstalled)

        // D-02: corrupted row — state says installed, but the file is gone from disk
        // and the JVM no longer sees the family. User-triggered repair only (D-12).
        row {
            label("\u26A0 Installed file missing")
            link("Reinstall") { triggerLifecycleAction(uninstall = false) }
            comment("Your plugin state records this font as installed, but the files are gone from disk")
        }.visibleIf(fontCorrupted)

        if (!IS_MAC) return

        row {
            val label = JLabel()
            val slug = FontCatalog.forPreset(FontPreset.fromName(pendingPreset)).brewCaskSlug
            label.text = "Or via Homebrew: brew install --cask $slug"
            installHintLabel = label
            cell(label)
        }.visibleIf(fontMissing)

        row {
            link("Copy") {
                val preset = FontPreset.fromName(pendingPreset)
                val command = "brew install --cask ${FontCatalog.forPreset(preset).brewCaskSlug}"
                CopyPasteManager.getInstance().setContents(StringSelection(command))
            }
            link("Run in Terminal") {
                val preset = FontPreset.fromName(pendingPreset)
                val command = "brew install --cask ${FontCatalog.forPreset(preset).brewCaskSlug}"
                CopyPasteManager.getInstance().setContents(StringSelection(command))
                val state = AyuIslandsSettings.getInstance().state
                if (state.fontInstallTerminal == "SYSTEM") {
                    ProcessBuilder("open", "-a", "Terminal").start()
                } else {
                    val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return@link
                    ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.activate(null)
                }
            }
            val combo = ComboBox(arrayOf("Built-in Terminal", "System Terminal"))
            val state = AyuIslandsSettings.getInstance().state
            combo.selectedIndex = if (state.fontInstallTerminal == "SYSTEM") 1 else 0
            combo.addActionListener {
                AyuIslandsSettings.getInstance().state.fontInstallTerminal =
                    if (combo.selectedIndex == 1) "SYSTEM" else "BUILTIN"
            }
            cell(combo)
        }.visibleIf(fontMissing)
    }

    private fun Panel.buildCustomizeGroup() {
        collapsibleGroup("Customize") {
            buildSizeSpacingRow()
            buildWeightOptionsRow()
        }.visibleIf(presetEnabled)
    }

    private fun Panel.buildSizeSpacingRow() {
        row {
            label("Size:")
            val sizeModel =
                SpinnerNumberModel(
                    currentSettings.fontSize.toInt(),
                    MIN_FONT_SIZE,
                    MAX_FONT_SIZE,
                    1,
                )
            val spinner = JSpinner(sizeModel)
            spinner.addChangeListener {
                if (!suppressListeners) {
                    updateCurrentSettings(fontSize = (spinner.value as Number).toFloat())
                }
            }
            sizeSpinner = spinner
            cell(spinner)

            label("Spacing:")
            val spacingModel =
                SpinnerNumberModel(
                    currentSettings.lineSpacing.toDouble(),
                    MIN_LINE_SPACING,
                    MAX_LINE_SPACING,
                    LINE_SPACING_STEP,
                )
            val spSpinner = JSpinner(spacingModel)
            spSpinner.addChangeListener {
                if (!suppressListeners) {
                    updateCurrentSettings(lineSpacing = (spSpinner.value as Number).toFloat())
                }
            }
            spacingSpinner = spSpinner
            cell(spSpinner)

            label("Weight:")
            val wCombo = ComboBox(FontWeight.entries.toTypedArray())
            wCombo.renderer = SimpleListCellRenderer.create("") { it.displayName }
            wCombo.selectedItem = currentSettings.weight
            wCombo.addActionListener {
                if (!suppressListeners) {
                    val selected = wCombo.selectedItem as FontWeight
                    updateCurrentSettings(weight = selected)
                }
            }
            weightCombo = wCombo
            cell(wCombo)
        }
    }

    private fun Panel.buildWeightOptionsRow() {
        row {
            val ligCb = checkBox("Ligatures")
            ligCb.component.isSelected = currentSettings.enableLigatures
            ligCb.component.addActionListener {
                if (!suppressListeners) {
                    updateCurrentSettings(enableLigatures = ligCb.component.isSelected)
                }
            }
            ligaturesCheckbox = ligCb.component

            val consoleCb = checkBox("Also apply to console")
            consoleCb.component.isSelected = pendingConsole
            consoleCb.component.addActionListener {
                pendingConsole = consoleCb.component.isSelected
            }
            consoleCheckbox = consoleCb.component
        }
    }

    /** Update the custom font family selection and refresh the preview. */
    private fun updateCustomFontFamily(family: String) {
        customizations[pendingPreset] = currentSettings.copy(fontFamily = family)
        summaryLabel?.let { updateSummaryText(it) }
        previewComponent?.updateFontFamily(family)
        refreshPreview()
    }

    /** Update a field in the current preset's customization and refresh the preview. */
    private fun updateCurrentSettings(
        fontSize: Float = currentSettings.fontSize,
        lineSpacing: Float = currentSettings.lineSpacing,
        enableLigatures: Boolean = currentSettings.enableLigatures,
        weight: FontWeight = currentSettings.weight,
    ) {
        customizations[pendingPreset] =
            currentSettings.copy(
                fontSize = fontSize,
                lineSpacing = lineSpacing,
                enableLigatures = enableLigatures,
                weight = weight,
            )
        summaryLabel?.let { updateSummaryText(it) }
        refreshPreview()
    }

    /** Load controls from the current preset's saved customization. */
    private fun loadControlsFromPreset() {
        suppressListeners = true
        val settings = currentSettings
        fontFamilyCombo?.selectedItem = settings.fontFamily
        sizeSpinner?.value = settings.fontSize.toInt()
        spacingSpinner?.value = settings.lineSpacing.toDouble()
        ligaturesCheckbox?.isSelected = settings.enableLigatures
        weightCombo?.selectedItem = settings.weight
        suppressListeners = false
        summaryLabel?.let { updateSummaryText(it) }
        refreshPreview(reloadPreset = true)
    }

    private fun refreshPreview(reloadPreset: Boolean = false) {
        val settings = currentSettings
        if (reloadPreset) {
            val preset = FontPreset.fromName(pendingPreset)
            previewComponent?.updatePreset(preset, availability[preset] == true)
            if (!preset.isCurated) {
                previewComponent?.updateFontFamily(settings.fontFamily)
            }
        }
        previewComponent?.updateSettings(
            settings.fontSize,
            settings.lineSpacing,
            settings.enableLigatures,
            settings.weight,
        )
    }

    private fun updateFontMissing() {
        val preset = FontPreset.fromName(pendingPreset)
        // D-01/D-03: compute all three booleans atomically from a SINGLE status snapshot
        // so the three .visibleIf() rows are always mutually exclusive — no race window.
        // Non-curated presets and disabled state collapse to NOT_INSTALLED.
        val status =
            if (preset.isCurated && pendingEnabled) FontDetector.status(preset) else FontStatus.NOT_INSTALLED
        val isMissing = preset.isCurated && status == FontStatus.NOT_INSTALLED && pendingEnabled
        val isHealthy = preset.isCurated && status == FontStatus.HEALTHY && pendingEnabled
        val isCorrupted = preset.isCurated && status == FontStatus.CORRUPTED && pendingEnabled

        fontMissing.set(isMissing)
        fontInstalled.set(isHealthy)
        fontCorrupted.set(isCorrupted)

        warningLabel?.let { it.text = "\u26A0 Requires ${preset.fontFamily}" }
        installHintLabel?.let {
            val slug = FontCatalog.forPreset(preset).brewCaskSlug
            it.text = "Or via Homebrew: brew install --cask $slug"
        }
        previewComponent?.updatePreset(preset, status == FontStatus.HEALTHY)
    }

    override fun isModified(): Boolean {
        if (pendingEnabled != storedEnabled) return true
        if (pendingPreset != storedPreset) return true
        if (pendingConsole != storedConsole) return true
        for ((name, settings) in customizations) {
            if (settings.encode() != storedCustomizations[name]) return true
        }
        return false
    }

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state

        state.fontPresetEnabled = pendingEnabled
        state.fontPresetName = pendingPreset
        state.fontApplyToConsole = pendingConsole

        // Save all per-preset customizations
        val newCustomizations = mutableMapOf<String, String>()
        for ((name, settings) in customizations) {
            newCustomizations[name] = settings.encode()
        }
        state.fontPresetCustomizations = newCustomizations
        storedCustomizations = newCustomizations.toMap()

        storedEnabled = pendingEnabled
        storedPreset = pendingPreset
        storedConsole = pendingConsole

        if (pendingEnabled) {
            FontDetector.invalidateCache()
            FontPresetApplicator.apply(currentSettings.copy(applyToConsole = pendingConsole))
        } else {
            FontPresetApplicator.revert()
        }
    }

    override fun reset() {
        pendingEnabled = storedEnabled
        pendingPreset = storedPreset
        pendingConsole = storedConsole

        // Restore customizations from the stored state
        customizations.clear()
        for (preset in FontPreset.entries) {
            val encoded = storedCustomizations[preset.name]
            customizations[preset.name] = FontSettings.decode(encoded, preset)
        }

        suppressListeners = true
        enabledCheckbox?.isSelected = storedEnabled
        presetSegmented?.selectedItem = FontPreset.fromName(storedPreset)
        consoleCheckbox?.isSelected = storedConsole
        presetEnabled.set(storedEnabled)
        isCustomSelected.set(storedPreset == FontPreset.CUSTOM.name)
        refreshCustomFontVisible()
        loadControlsFromPreset()
        updateFontMissing()
        suppressListeners = false
    }
}
