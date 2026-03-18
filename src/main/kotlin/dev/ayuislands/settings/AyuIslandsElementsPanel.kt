@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.conflict.ConflictType
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.GlowTabMode
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
    private var pendingTabMode: String = "MINIMAL"
    private var storedTabMode: String = "MINIMAL"
    private var pendingTabUnderlineHeight: Int = AyuIslandsState.DEFAULT_TAB_UNDERLINE_HEIGHT
    private var storedTabUnderlineHeight: Int = AyuIslandsState.DEFAULT_TAB_UNDERLINE_HEIGHT
    private var pendingTabUnderlineGlowSync: Boolean = false
    private var storedTabUnderlineGlowSync: Boolean = false
    private var pendingBracketScope: Boolean = true
    private var storedBracketScope: Boolean = true
    private val checkboxes: MutableMap<AccentElementId, JCheckBox> = mutableMapOf()
    private var tabModeSegmented: SegmentedButton<GlowTabMode>? = null
    private var tabModeCommentRow: Row? = null
    private var tabModeRow: Row? = null
    private var thicknessSegmented: SegmentedButton<Int>? = null
    private var thicknessRow: Row? = null
    private var syncRow: Row? = null
    private var syncCheckbox: JCheckBox? = null
    private var bracketScopeCheckbox: JCheckBox? = null
    private var licensed: Boolean = false
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
        licensed = LicenseChecker.isLicensedOrGrace()

        // Initialize toggle state from persisted settings
        for (id in AccentElementId.entries) {
            val enabled = state.isToggleEnabled(id)
            pendingToggles[id] = enabled
        }
        storedToggles = pendingToggles.toMap()

        storedForceOverrides = state.forceOverrides.toSet()
        pendingForceOverrides = storedForceOverrides.toMutableSet()

        storedTabMode = state.glowTabMode ?: "MINIMAL"
        pendingTabMode = storedTabMode

        storedTabUnderlineHeight = state.tabUnderlineHeight
        pendingTabUnderlineHeight = storedTabUnderlineHeight
        storedTabUnderlineGlowSync = state.tabUnderlineGlowSync
        pendingTabUnderlineGlowSync = storedTabUnderlineGlowSync
        storedBracketScope = state.bracketScopeEnabled
        pendingBracketScope = storedBracketScope

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
                row { comment("Per-element toggles require a Pro license.") }
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
                                for (
                                id in
                                AccentElementId.entries.filter { it.group == AccentGroup.INTERACTIVE }
                                ) {
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

        panel.group("Bracket Scope") {
            row {
                val scopeCb =
                    checkBox("Highlight bracket scope on gutter")
                        .comment("Show accent-colored scope stripe when caret is on a bracket")
                scopeCb.component.isSelected = pendingBracketScope
                scopeCb.component.isEnabled = licensed
                scopeCb.component.addActionListener {
                    pendingBracketScope = scopeCb.component.isSelected
                }
                bracketScopeCheckbox = scopeCb.component
            }
        }

        buildActiveTabRow(panel)
    }

    private fun buildActiveTabRow(panel: Panel) {
        val state = AyuIslandsSettings.getInstance().state
        val glowEnabled = state.glowEnabled

        val islandsUi = AyuVariant.isIslandsUi()

        panel.group("Active Tab") {
            // Tab accent style (Islands UI only)
            tabModeCommentRow =
                row {
                    comment(
                        "Minimal = underline only, Full = underline + tinted background, Off = neutral",
                    )
                }.visible(islandsUi)
            tabModeRow =
                row {
                    label("Tab accent style")
                    val segmented =
                        segmentedButton(GlowTabMode.entries) { mode -> text = mode.displayName }
                    segmented.selectedItem = GlowTabMode.fromName(pendingTabMode)
                    segmented.enabled(licensed)
                    @Suppress("UnstableApiUsage")
                    segmented.whenItemSelected { mode ->
                        pendingTabMode = mode.name
                        updateThicknessRowVisibility()
                    }
                    tabModeSegmented = segmented
                }.visible(islandsUi)

            // Underline thickness presets (non-Islands only)
            val tabModeIsOff = GlowTabMode.fromName(pendingTabMode) == GlowTabMode.OFF
            thicknessRow =
                row {
                    label("Underline thickness")
                    val thickSegmented =
                        segmentedButton(UNDERLINE_THICKNESS_PRESETS) { value -> text = "${value}px" }
                    thickSegmented.selectedItem = pendingTabUnderlineHeight
                    thickSegmented.enabled(licensed && !pendingTabUnderlineGlowSync)
                    @Suppress("UnstableApiUsage")
                    thickSegmented.whenItemSelected { value -> pendingTabUnderlineHeight = value }
                    thicknessSegmented = thickSegmented
                }.visible(!tabModeIsOff && !islandsUi)

            // Sync with the glow width checkbox (hidden on Islands UI)
            syncRow =
                row {
                    val cb = checkBox("Sync with glow width")
                    cb.component.isSelected = pendingTabUnderlineGlowSync
                    cb.component.isEnabled = licensed && glowEnabled
                    if (!glowEnabled) {
                        cb.component.toolTipText = "Enable glow to sync"
                    }
                    cb.component.addActionListener {
                        pendingTabUnderlineGlowSync = cb.component.isSelected
                        updateThicknessEnabledState()
                        if (pendingTabUnderlineGlowSync && glowEnabled) {
                            val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
                            val syncedWidth = state.getWidthForStyle(style)
                            thicknessSegmented?.selectedItem = syncedWidth
                        }
                    }
                    syncCheckbox = cb.component
                }.visible(!tabModeIsOff && !islandsUi)
        }
    }

    private fun updateThicknessRowVisibility() {
        val isOff = GlowTabMode.fromName(pendingTabMode) == GlowTabMode.OFF
        val hidden = isOff || AyuVariant.isIslandsUi()
        thicknessRow?.visible(!hidden)
        syncRow?.visible(!hidden)
        if (!hidden) {
            updateThicknessEnabledState()
        }
    }

    private fun updateThicknessEnabledState() {
        thicknessSegmented?.enabled(licensed && !pendingTabUnderlineGlowSync)
        syncCheckbox?.isEnabled = licensed && AyuIslandsSettings.getInstance().state.glowEnabled
    }

    private fun buildToggleRow(
        panel: Panel,
        id: AccentElementId,
        licensed: Boolean,
    ) {
        val conflict = ConflictRegistry.getConflictFor(id)
        val isBlocking = conflict != null && conflict.type == ConflictType.BLOCK

        panel.row {
            val cb = checkBox(id.displayName)
            cb.component.isSelected = pendingToggles[id] ?: true
            cb.component.isEnabled = licensed && !isBlocking
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

        if (isBlocking) {
            panel.row { comment("Managed by ${conflict.pluginDisplayName}") }
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
        if (pendingTabMode != storedTabMode) return true
        if (pendingTabUnderlineHeight != storedTabUnderlineHeight) return true
        if (pendingTabUnderlineGlowSync != storedTabUnderlineGlowSync) return true
        if (pendingBracketScope != storedBracketScope) return true
        return false
    }

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state

        for ((id, enabled) in pendingToggles) {
            state.setToggle(id, enabled)
        }
        state.forceOverrides = pendingForceOverrides.toMutableSet()
        state.glowTabMode = pendingTabMode
        state.tabUnderlineHeight = pendingTabUnderlineHeight
        state.tabUnderlineGlowSync = pendingTabUnderlineGlowSync
        state.bracketScopeEnabled = pendingBracketScope

        storedToggles = pendingToggles.toMap()
        storedForceOverrides = pendingForceOverrides.toSet()
        storedTabMode = pendingTabMode
        storedTabUnderlineHeight = pendingTabUnderlineHeight
        storedTabUnderlineGlowSync = pendingTabUnderlineGlowSync
        storedBracketScope = pendingBracketScope

        // Re-apply accent with new toggle states
        val currentVariant = variant ?: return
        val accentHex = AyuIslandsSettings.getInstance().getAccentForVariant(currentVariant)
        AccentApplicator.apply(accentHex)
    }

    override fun reset() {
        pendingToggles.clear()
        pendingToggles.putAll(storedToggles)
        pendingForceOverrides = storedForceOverrides.toMutableSet()
        pendingTabMode = storedTabMode
        pendingTabUnderlineHeight = storedTabUnderlineHeight
        pendingTabUnderlineGlowSync = storedTabUnderlineGlowSync
        pendingBracketScope = storedBracketScope
        refreshCheckboxes()
        bracketScopeCheckbox?.isSelected = storedBracketScope
        syncPreviewToggles()
        tabModeSegmented?.selectedItem = GlowTabMode.fromName(pendingTabMode)
        thicknessSegmented?.selectedItem = pendingTabUnderlineHeight
        syncCheckbox?.isSelected = pendingTabUnderlineGlowSync
        updateThicknessRowVisibility()
    }

    companion object {
        private val UNDERLINE_THICKNESS_PRESETS = listOf(2, 4, 6, 8)
    }
}
