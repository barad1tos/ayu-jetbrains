package dev.ayuislands.settings

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.selected
import dev.ayuislands.AppearanceSyncService
import dev.ayuislands.accent.AyuVariant
import javax.swing.JComboBox

/** System appearance section — visible on macOS only. */
class AyuIslandsAppearancePanel : AyuIslandsSettingsPanel {
    private var pendingFollowAppearance: Boolean = false
    private var storedFollowAppearance: Boolean = false
    private var followAppearanceCheckbox: JBCheckBox? = null

    private var pendingNightTheme: String = "Mirage"
    private var storedNightTheme: String = "Mirage"
    private var nightThemeCombo: JComboBox<String>? = null

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        val settings = AyuIslandsSettings.getInstance()
        storedFollowAppearance = settings.state.followSystemAppearance
        pendingFollowAppearance = storedFollowAppearance

        val currentDarkTheme = settings.state.lastDarkAppearanceTheme ?: "Ayu Mirage (Islands UI)"
        val currentNight = if (currentDarkTheme.contains("Dark")) "Dark" else "Mirage"
        storedNightTheme = currentNight
        pendingNightTheme = currentNight

        if (!SystemInfo.isMac) return

        panel.group("System") {
            row { comment("Automatically switch between Light and Dark variants.") }
            lateinit var checkboxCell: Cell<JBCheckBox>
            row {
                checkboxCell =
                    checkBox("Follow system appearance (Light / Dark)")
                val checkbox = checkboxCell.component
                checkbox.isSelected = pendingFollowAppearance
                checkbox.addActionListener {
                    pendingFollowAppearance = checkbox.isSelected
                }
                followAppearanceCheckbox = checkbox
            }
            row {
                label("Night theme:")
                val nightOptions = listOf("Mirage", "Dark")
                val combo = comboBox(nightOptions).component
                combo.selectedItem = currentNight
                combo.addActionListener {
                    pendingNightTheme = combo.selectedItem as? String ?: "Mirage"
                }
                nightThemeCombo = combo
            }.visibleIf(checkboxCell.selected)
        }
    }

    override fun isModified(): Boolean =
        pendingFollowAppearance != storedFollowAppearance ||
            pendingNightTheme != storedNightTheme

    override fun apply() {
        if (!isModified()) return
        val settings = AyuIslandsSettings.getInstance()

        if (pendingFollowAppearance != storedFollowAppearance) {
            settings.state.followSystemAppearance = pendingFollowAppearance
            storedFollowAppearance = pendingFollowAppearance
            if (pendingFollowAppearance) {
                AppearanceSyncService.getInstance().syncIfNeeded()
            }
        }

        if (pendingNightTheme != storedNightTheme) {
            val currentDarkTheme = settings.state.lastDarkAppearanceTheme ?: ""
            val isIslands = currentDarkTheme.contains("Islands UI")
            val newDarkTheme =
                when (pendingNightTheme) {
                    "Dark" -> if (isIslands) "Ayu Dark (Islands UI)" else "Ayu Dark"
                    else -> if (isIslands) "Ayu Mirage (Islands UI)" else "Ayu Mirage"
                }
            settings.state.lastDarkAppearanceTheme = newDarkTheme
            storedNightTheme = pendingNightTheme
        }
    }

    override fun reset() {
        pendingFollowAppearance = storedFollowAppearance
        followAppearanceCheckbox?.isSelected = storedFollowAppearance
        pendingNightTheme = storedNightTheme
        nightThemeCombo?.selectedItem = storedNightTheme
    }
}
