@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import javax.swing.JCheckBox
import javax.swing.JLabel

/** Font preset section in the Accent tab. */
class FontPresetPanel : AyuIslandsSettingsPanel {
    private var pendingEnabled: Boolean = false
    private var storedEnabled: Boolean = false
    private var pendingPreset: String = FontPreset.CLEAN.name
    private var storedPreset: String = FontPreset.CLEAN.name
    private var pendingConsole: Boolean = false
    private var storedConsole: Boolean = false

    private var enabledCheckbox: JCheckBox? = null
    private var presetSegmented: SegmentedButton<FontPreset>? = null
    private var consoleCheckbox: JCheckBox? = null
    private var warningLabel: JLabel? = null
    private var suppressListeners = false

    private val presetEnabled = AtomicBooleanProperty(false)
    private val fontMissing = AtomicBooleanProperty(false)

    private lateinit var availability: Map<FontPreset, Boolean>

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        val state = AyuIslandsSettings.getInstance().state

        storedEnabled = state.fontPresetEnabled
        pendingEnabled = storedEnabled

        storedPreset = state.fontPresetName ?: FontPreset.CLEAN.name
        pendingPreset = storedPreset

        storedConsole = state.fontApplyToConsole
        pendingConsole = storedConsole

        presetEnabled.set(pendingEnabled)

        availability = FontDetector.detectAll()
        updateFontMissing()

        panel.group("Font Preset") {
            row {
                val cb =
                    checkBox("Apply font preset")
                        .comment("Set editor font, size, line spacing, and ligatures from a curated preset")
                cb.component.isSelected = pendingEnabled
                cb.component.addActionListener {
                    pendingEnabled = cb.component.isSelected
                    presetEnabled.set(pendingEnabled)
                    updateFontMissing()
                }
                enabledCheckbox = cb.component
            }

            row {
                val segmented =
                    segmentedButton(FontPreset.entries) { preset ->
                        text = preset.displayName
                        enabled = availability[preset] == true
                    }
                segmented.maxButtonsCount(FontPreset.entries.size)
                segmented.selectedItem = FontPreset.fromName(pendingPreset)
                @Suppress("UnstableApiUsage")
                segmented.whenItemSelected { preset ->
                    if (!suppressListeners) {
                        pendingPreset = preset.name
                        updateFontMissing()
                    }
                }
                presetSegmented = segmented
            }.visibleIf(presetEnabled)

            row {
                val label = JLabel()
                updateWarningText(label, FontPreset.fromName(pendingPreset))
                warningLabel = label
                cell(label)
                browserLink(
                    "Download from nerdfonts.com",
                    FontPreset.fromName(pendingPreset).downloadUrl,
                )
            }.visibleIf(fontMissing)

            collapsibleGroup("Customize") {
                row {
                    val cb =
                        checkBox("Apply to built-in console")
                            .comment("Also set font for the IDE's built-in terminal")
                    cb.component.isSelected = pendingConsole
                    cb.component.addActionListener {
                        pendingConsole = cb.component.isSelected
                    }
                    consoleCheckbox = cb.component
                }
            }.visibleIf(presetEnabled)
        }
    }

    private fun updateFontMissing() {
        val preset = FontPreset.fromName(pendingPreset)
        val missing = availability[preset] != true && pendingEnabled
        fontMissing.set(missing)
        warningLabel?.let { updateWarningText(it, preset) }
    }

    private fun updateWarningText(
        label: JLabel,
        preset: FontPreset,
    ) {
        label.text = "Requires ${preset.fontFamily}"
    }

    override fun isModified(): Boolean =
        pendingEnabled != storedEnabled ||
            pendingPreset != storedPreset ||
            pendingConsole != storedConsole

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state

        state.fontPresetEnabled = pendingEnabled
        state.fontPresetName = pendingPreset
        state.fontApplyToConsole = pendingConsole

        storedEnabled = pendingEnabled
        storedPreset = pendingPreset
        storedConsole = pendingConsole

        if (pendingEnabled) {
            val preset = FontPreset.fromName(pendingPreset)
            if (FontDetector.isInstalled(preset)) {
                FontPresetApplicator.apply(preset, pendingConsole)
            }
        } else {
            FontPresetApplicator.revert()
        }
    }

    override fun reset() {
        pendingEnabled = storedEnabled
        pendingPreset = storedPreset
        pendingConsole = storedConsole

        suppressListeners = true
        enabledCheckbox?.isSelected = storedEnabled
        presetSegmented?.selectedItem = FontPreset.fromName(storedPreset)
        consoleCheckbox?.isSelected = storedConsole
        presetEnabled.set(storedEnabled)
        updateFontMissing()
        suppressListeners = false
    }
}
