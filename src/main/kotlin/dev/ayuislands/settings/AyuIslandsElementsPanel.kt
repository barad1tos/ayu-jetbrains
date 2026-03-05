@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.conflict.ConflictType
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.licensing.LicenseChecker
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox

/** Per-element accent toggle checkboxes with conflict detection and license-aware dimming. */
class AyuIslandsElementsPanel : AyuIslandsSettingsPanel {
    private val pendingToggles: MutableMap<AccentElementId, Boolean> = mutableMapOf()
    private var storedToggles: Map<AccentElementId, Boolean> = emptyMap()
    private var pendingForceOverrides: MutableSet<String> = mutableSetOf()
    private var storedForceOverrides: Set<String> = emptySet()
    private var pendingCgpIntegration: Boolean = false
    private var storedCgpIntegration: Boolean = false
    private var pendingTabMode: String = "MINIMAL"
    private var storedTabMode: String = "MINIMAL"
    private val checkboxes: MutableMap<AccentElementId, JCheckBox> = mutableMapOf()
    private var cgpCheckbox: JCheckBox? = null
    private var tabModeCombo: ComboBox<String>? = null
    private var variant: AyuVariant? = null
    private var elementPreview: AyuIslandsPreviewPanel? = null

    var onToggleChanged: (() -> Unit)? = null

    fun updatePreviewAccent(hex: String) {
        elementPreview?.previewAccentHex = hex
        elementPreview?.updatePreview()
    }

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        this.variant = variant
        val state = AyuIslandsSettings.getInstance().state
        val licensed = LicenseChecker.isLicensedOrGrace()

        // Initialize toggle state from persisted settings
        for (id in AccentElementId.entries) {
            val enabled = state.isToggleEnabled(id)
            pendingToggles[id] = enabled
        }
        storedToggles = pendingToggles.toMap()

        storedForceOverrides = state.forceOverrides.toSet()
        pendingForceOverrides = storedForceOverrides.toMutableSet()

        storedCgpIntegration = state.cgpIntegrationEnabled
        pendingCgpIntegration = storedCgpIntegration

        storedTabMode = state.glowTabMode ?: "MINIMAL"
        pendingTabMode = storedTabMode

        // Detect conflicts on panel open and clean stale overrides for blocked elements
        ConflictRegistry.detectConflicts()
        val blockedIds =
            AccentElementId.entries
                .filter { ConflictRegistry.getConflictFor(it)?.type == ConflictType.BLOCK }
                .map { it.name }
                .toSet()
        pendingForceOverrides.removeAll(blockedIds)

        // Create a preview component (used inside the row below)
        val preview = AyuIslandsPreviewPanel()
        preview.previewAccentHex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        preview.previewToggles = pendingToggles.toMap()
        preview.previewGlowEnabled = false
        val previewComponent = preview.createComponent()
        elementPreview = preview

        panel.group("Accent Elements") {
            if (!licensed) {
                row {
                    label("Per-element toggles require a Pro license").applyToComponent {
                        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    }
                    link("Get Ayu Islands Pro") {
                        LicenseChecker.requestLicense(
                            "Unlock per-element accent toggles and neon glow effects",
                        )
                    }
                }
            }

            row {
                // Left: checkbox columns + enable/disable
                panel {
                    twoColumnsRow(
                        // Visual group
                        {
                            panel {
                                row { label("Visual").bold() }
                                for (id in AccentElementId.entries.filter { it.group == AccentGroup.VISUAL }) {
                                    buildToggleRow(this, id, licensed)
                                }
                            }
                        },
                        // Interactive group
                        {
                            panel {
                                row { label("Interactive").bold() }
                                for (id in AccentElementId.entries.filter { it.group == AccentGroup.INTERACTIVE }) {
                                    buildToggleRow(this, id, licensed)
                                }
                            }
                        },
                    )

                    // Enable all / Disable all links
                    row {
                        link("Enable all") {
                            AccentElementId.entries.forEach { pendingToggles[it] = true }
                            refreshCheckboxes()
                            syncPreviewToggles()
                            onToggleChanged?.invoke()
                        }.enabled(licensed)
                        link("Disable all") {
                            AccentElementId.entries.forEach { pendingToggles[it] = false }
                            refreshCheckboxes()
                            syncPreviewToggles()
                            onToggleChanged?.invoke()
                        }.enabled(licensed)
                    }
                }

                // Right: hover preview mockup
                cell(previewComponent).align(AlignY.CENTER)
            }
        }

        buildIntegrationsGroup(panel, licensed)
        buildActiveTabRow(panel, licensed)
    }

    private fun buildActiveTabRow(
        panel: Panel,
        licensed: Boolean,
    ) {
        panel.group("Active Tab") {
            row {
                label("Tab accent style")
                val model = DefaultComboBoxModel(GlowTabMode.entries.map { it.displayName }.toTypedArray())
                val combo = ComboBox(model)
                combo.selectedItem = GlowTabMode.fromName(pendingTabMode).displayName
                combo.isEnabled = licensed
                combo.addActionListener {
                    val selectedName = combo.selectedItem as? String ?: return@addActionListener
                    pendingTabMode = GlowTabMode.entries.first { it.displayName == selectedName }.name
                }
                tabModeCombo = combo
                cell(combo)
            }
            row {
                comment("Minimal = underline only, Full = underline + tinted background, Off = neutral")
            }
        }
    }

    private fun buildIntegrationsGroup(
        panel: Panel,
        licensed: Boolean,
    ) {
        if (!ConflictRegistry.isCodeGlanceProDetected()) return
        panel.group("Integrations") {
            row {
                comment("Sync features with third-party plugins")
            }
            row {
                val cb =
                    checkBox("Sync color with CodeGlance")
                        .comment("Apply accent color to CodeGlance Pro viewport")
                cb.component.isSelected = pendingCgpIntegration
                cb.component.isEnabled = licensed
                cb.component.addActionListener {
                    pendingCgpIntegration = cb.component.isSelected
                }
                cgpCheckbox = cb.component

                browserLink("Plugin page", "https://plugins.jetbrains.com/plugin/18824-codeglance-pro")
            }
        }
    }

    private fun buildToggleRow(
        panel: Panel,
        id: AccentElementId,
        licensed: Boolean,
    ) {
        val conflict = ConflictRegistry.getConflictFor(id)
        val blocked = conflict?.type == ConflictType.BLOCK

        panel.row {
            val cb = checkBox(id.displayName)
            cb.component.isSelected = pendingToggles[id] ?: true
            cb.component.isEnabled = licensed && !blocked
            cb.component.addActionListener {
                pendingToggles[id] = cb.component.isSelected
                syncPreviewToggles()
                onToggleChanged?.invoke()
            }
            checkboxes[id] = cb.component

            // Hover-to-highlight in preview
            cb.component.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        elementPreview?.highlightedElement = id
                        elementPreview?.updatePreview()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        elementPreview?.highlightedElement = null
                        elementPreview?.updatePreview()
                    }
                },
            )
        }

        if (blocked) {
            panel.row {
                comment("Managed by ${conflict!!.pluginDisplayName}")
            }
        }
    }

    private fun refreshCheckboxes() {
        for ((id, cb) in checkboxes) {
            cb.isSelected = pendingToggles[id] ?: true
        }
    }

    private fun syncPreviewToggles() {
        elementPreview?.previewToggles = pendingToggles.toMap()
        elementPreview?.updatePreview()
    }

    override fun isModified(): Boolean {
        if (pendingToggles != storedToggles) return true
        if (pendingForceOverrides != storedForceOverrides) return true
        if (pendingCgpIntegration != storedCgpIntegration) return true
        if (pendingTabMode != storedTabMode) return true
        return false
    }

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state

        for ((id, enabled) in pendingToggles) {
            state.setToggle(id, enabled)
        }
        state.forceOverrides = pendingForceOverrides.toMutableSet()
        state.cgpIntegrationEnabled = pendingCgpIntegration
        state.glowTabMode = pendingTabMode

        storedToggles = pendingToggles.toMap()
        storedForceOverrides = pendingForceOverrides.toSet()
        storedCgpIntegration = pendingCgpIntegration
        storedTabMode = pendingTabMode

        // Re-apply accent with new toggle states
        val currentVariant = variant ?: return
        val accentHex = AyuIslandsSettings.getInstance().getAccentForVariant(currentVariant)
        AccentApplicator.apply(accentHex)
    }

    override fun reset() {
        pendingToggles.clear()
        pendingToggles.putAll(storedToggles)
        pendingForceOverrides = storedForceOverrides.toMutableSet()
        pendingCgpIntegration = storedCgpIntegration
        pendingTabMode = storedTabMode
        refreshCheckboxes()
        syncPreviewToggles()
        cgpCheckbox?.isSelected = storedCgpIntegration
        tabModeCombo?.selectedItem = GlowTabMode.fromName(pendingTabMode).displayName
    }
}
