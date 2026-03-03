package dev.ayuislands.settings

import com.intellij.icons.AllIcons
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.licensing.LicenseChecker
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JCheckBox

/** Per-element accent toggle checkboxes with conflict detection and license-aware dimming. */
class AyuIslandsElementsPanel : AyuIslandsSettingsPanel {
    private val pendingToggles: MutableMap<AccentElementId, Boolean> = mutableMapOf()
    private var storedToggles: Map<AccentElementId, Boolean> = emptyMap()
    private var pendingForceOverrides: MutableSet<String> = mutableSetOf()
    private var storedForceOverrides: Set<String> = emptySet()
    private var pendingCgpIntegration: Boolean = false
    private var storedCgpIntegration: Boolean = false
    private val checkboxes: MutableMap<AccentElementId, JCheckBox> = mutableMapOf()
    private var cgpCheckbox: JCheckBox? = null
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

        // Detect conflicts on panel open
        val conflicts = ConflictRegistry.detectConflicts()

        // Create a preview component (used inside the row below)
        val preview = AyuIslandsPreviewPanel()
        preview.previewAccentHex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        preview.previewToggles = pendingToggles.toMap()
        preview.previewGlowEnabled = false
        val previewComponent = preview.createComponent(variant)
        elementPreview = preview

        panel.group("Accent Elements") {
            if (!licensed) {
                row {
                    label("Pro feature").applyToComponent {
                        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    }
                    link("Get Ayu Islands Pro") {
                        LicenseChecker.requestLicense(
                            "Unlock per-element accent toggles, custom colors, and neon glow effects",
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
                                    buildToggleRow(this, id, conflicts, licensed)
                                }
                            }
                        },
                        // Interactive group
                        {
                            panel {
                                row { label("Interactive").bold() }
                                for (id in AccentElementId.entries.filter { it.group == AccentGroup.INTERACTIVE }) {
                                    buildToggleRow(this, id, conflicts, licensed)
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
    }

    private fun buildIntegrationsGroup(panel: Panel, licensed: Boolean) {
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
        conflicts: List<dev.ayuislands.accent.conflict.ConflictEntry>,
        licensed: Boolean,
    ) {
        panel.row {
            val cb = checkBox(id.displayName)
            cb.component.isSelected = pendingToggles[id] ?: true
            cb.component.isEnabled = licensed
            cb.component.addActionListener {
                pendingToggles[id] = cb.component.isSelected
                // If enabling a conflicting element, treat as force override
                val conflict = conflicts.firstOrNull { entry -> id in entry.affectedElements }
                if (cb.component.isSelected && conflict != null) {
                    pendingForceOverrides.add(id.name)
                } else if (!cb.component.isSelected) {
                    pendingForceOverrides.remove(id.name)
                }
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

            // Conflict indicator
            val conflict = conflicts.firstOrNull { entry -> id in entry.affectedElements }
            if (conflict != null) {
                icon(AllIcons.General.Warning).applyToComponent {
                    toolTipText =
                        "${conflict.pluginDisplayName} overrides this element." +
                            " Enabling may not have visible effect."
                }
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

        storedToggles = pendingToggles.toMap()
        storedForceOverrides = pendingForceOverrides.toSet()
        storedCgpIntegration = pendingCgpIntegration

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
        refreshCheckboxes()
        syncPreviewToggles()
        cgpCheckbox?.isSelected = storedCgpIntegration
    }
}
