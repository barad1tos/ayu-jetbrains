package dev.ayuislands.settings

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.selected
import dev.ayuislands.AppearanceSyncService
import dev.ayuislands.accent.AyuVariant
import javax.swing.JComboBox

/**
 * Typealias for injecting a row (e.g. the `Follow system accent color` checkbox owned
 * by [AyuIslandsAccentPanel]) into the shared `System` collapsible group rendered by
 * this panel. The configurable sets the bridge before `buildPanel` runs.
 */
typealias SystemAccentRowInstaller = (Panel) -> Unit

/**
 * Hosts theme-related settings:
 *   - Editor color scheme auto-bind on theme change (cross-platform).
 *   - macOS "System" collapsible group — appearance (Light/Dark) and accent-color
 *     system integration. The accent-color checkbox is owned by
 *     [AyuIslandsAccentPanel]; [systemAccentRowInstaller] is wired by the
 *     configurable so the checkbox renders inside this group while its state
 *     and side-effects stay with the accent panel.
 *
 * The scheme-bind row is rendered FIRST so it surfaces above the macOS-only
 * group when both apply. On non-macOS platforms only the scheme-bind row is
 * rendered.
 *
 * Pending/stored bookkeeping is delegated to a [SettingsSection] over
 * [AppearanceSettings] — except `syncEditorScheme`, whose pending/stored
 * Boolean fields are a reflection contract with
 * `AyuIslandsAppearancePanelSyncEditorSchemeTest` (the test writes the fields
 * via `getDeclaredField`), so they deliberately stay hand-rolled.
 */
class AyuIslandsAppearancePanel : AyuIslandsSettingsPanel {
    private data class AppearanceSettings(
        val followSystemAppearance: Boolean = false,
        val nightTheme: String = "Mirage",
    )

    private val section =
        SettingsSection(initial = AppearanceSettings()) {
            val state = AyuIslandsSettings.getInstance().state
            val currentDarkTheme = state.lastDarkAppearanceTheme ?: "Ayu Mirage (Islands UI)"
            AppearanceSettings(
                followSystemAppearance = state.followSystemAppearance,
                nightTheme = if (currentDarkTheme.contains("Dark")) "Dark" else "Mirage",
            )
        }

    private var followAppearanceCheckbox: JBCheckBox? = null
    private var nightThemeCombo: JComboBox<String>? = null

    private var pendingSyncEditorScheme: Boolean = true
    private var storedSyncEditorScheme: Boolean = true
    private var syncEditorSchemeCheckbox: JBCheckBox? = null

    var systemAccentRowInstaller: SystemAccentRowInstaller? = null

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        val settings = AyuIslandsSettings.getInstance()
        section.load()

        storedSyncEditorScheme = settings.state.syncEditorScheme
        pendingSyncEditorScheme = storedSyncEditorScheme

        // Cross-platform: editor color scheme auto-bind. Renders above the macOS
        // "System" group so it's discoverable on every platform.
        panel.group("Theme Synchronization") {
            row {
                val cb =
                    checkBox("Sync editor color scheme with active Ayu theme")
                        .comment(
                            "When on, switching between Ayu variants automatically applies " +
                                "the matching editor color scheme. Off respects your custom scheme.",
                        )
                cb.component.isSelected = pendingSyncEditorScheme
                cb.component.addActionListener {
                    pendingSyncEditorScheme = cb.component.isSelected
                }
                syncEditorSchemeCheckbox = cb.component
            }
        }

        if (!SystemInfo.isMac) return

        val collapsible =
            panel.collapsibleGroup("System") {
                row { comment("Inherit appearance and accent color from macOS.") }
                lateinit var checkboxCell: Cell<JBCheckBox>
                row {
                    checkboxCell =
                        checkBox("Follow system appearance (Light / Dark)")
                    val checkbox = checkboxCell.component
                    checkbox.isSelected = section.pending.followSystemAppearance
                    checkbox.addActionListener {
                        section.update { it.copy(followSystemAppearance = checkbox.isSelected) }
                    }
                    followAppearanceCheckbox = checkbox
                }
                row {
                    label("Night theme:")
                    val nightOptions = listOf("Mirage", "Dark")
                    val combo = comboBox(nightOptions).component
                    combo.selectedItem = section.pending.nightTheme
                    combo.addActionListener {
                        section.update { it.copy(nightTheme = combo.selectedItem as? String ?: "Mirage") }
                    }
                    nightThemeCombo = combo
                }.visibleIf(checkboxCell.selected)

                // Accent-color system checkbox lives in AyuIslandsAccentPanel (owns the
                // pending follow-system state and swatch-panel disable logic). We inject
                // its row here so the UI groups both macOS integrations together.
                systemAccentRowInstaller?.invoke(this)
            }
        collapsible.expanded = settings.state.systemGroupExpanded
        collapsible.addExpandedListener { expanded ->
            settings.state.systemGroupExpanded = expanded
        }
    }

    override fun isModified(): Boolean =
        section.isModified() ||
            pendingSyncEditorScheme != storedSyncEditorScheme

    override fun apply() {
        if (!isModified()) return
        val settings = AyuIslandsSettings.getInstance()

        section.commit { pending, stored ->
            if (pending.followSystemAppearance != stored.followSystemAppearance) {
                settings.state.followSystemAppearance = pending.followSystemAppearance
                if (pending.followSystemAppearance) {
                    AppearanceSyncService.getInstance().syncIfNeeded()
                }
            }

            if (pending.nightTheme != stored.nightTheme) {
                val currentDarkTheme = settings.state.lastDarkAppearanceTheme ?: ""
                val isIslands = currentDarkTheme.contains("Islands UI")
                val newDarkTheme =
                    when (pending.nightTheme) {
                        "Dark" -> if (isIslands) "Ayu Dark (Islands UI)" else "Ayu Dark"
                        else -> if (isIslands) "Ayu Mirage (Islands UI)" else "Ayu Mirage"
                    }
                settings.state.lastDarkAppearanceTheme = newDarkTheme
            }
        }

        if (pendingSyncEditorScheme != storedSyncEditorScheme) {
            settings.state.syncEditorScheme = pendingSyncEditorScheme
            storedSyncEditorScheme = pendingSyncEditorScheme
        }
    }

    override fun reset() {
        val restored = section.resetToStored()
        followAppearanceCheckbox?.isSelected = restored.followSystemAppearance
        nightThemeCombo?.selectedItem = restored.nightTheme
        pendingSyncEditorScheme = storedSyncEditorScheme
        syncEditorSchemeCheckbox?.isSelected = storedSyncEditorScheme
    }
}
