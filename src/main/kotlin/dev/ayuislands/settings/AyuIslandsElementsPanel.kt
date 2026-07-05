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
    private data class ElementSettings(
        val toggles: Map<AccentElementId, Boolean> = emptyMap(),
        val forceOverrides: Set<String> = emptySet(),
        val tabMode: String = "MINIMAL",
        val tabUnderlineHeight: Int = AyuIslandsState.DEFAULT_TAB_UNDERLINE_HEIGHT,
        val tabUnderlineGlowSync: Boolean = false,
        val bracketScope: Boolean = true,
    )

    // CHROME-group ids are owned by the Chrome tinting panel and must NOT surface
    // in this panel's checkbox list — the snapshot filters them out; blockedIds
    // and enable/disable-all filter likewise.
    private val section =
        SettingsSection(initial = ElementSettings()) {
            val state = AyuIslandsSettings.getInstance().state
            ElementSettings(
                toggles =
                    AccentElementId.entries
                        .filter { it.group != AccentGroup.CHROME }
                        .associateWith { state.isToggleEnabled(it) },
                forceOverrides = state.forceOverrides.toSet(),
                tabMode = state.glowTabMode ?: "MINIMAL",
                tabUnderlineHeight = state.tabUnderlineHeight,
                tabUnderlineGlowSync = state.tabUnderlineGlowSync,
                bracketScope = state.bracketScopeEnabled,
            )
        }

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
        val gate =
            PremiumFeatureGate(
                featureName = "Accent elements",
                lockedDescription =
                    "Accent elements are a Pro feature. " +
                        "Preview per-element toggles, tab underline, and bracket scope controls here.",
                requestMessage = "Unlock accent element customization",
            )
        licensed = gate.isUnlocked

        section.load()

        // Detect conflicts on panel open and clean stale overrides for blocked elements
        ConflictRegistry.detectConflicts()
        val blockedIds =
            AccentElementId.entries
                .filter { it.group != AccentGroup.CHROME }
                .filter { ConflictRegistry.getConflictFor(it)?.type == ConflictType.BLOCK }
                .map { it.name }
                .toSet()
        section.update { it.copy(forceOverrides = it.forceOverrides - blockedIds) }

        // Create a preview component (used inside the row below)
        val preview = AyuIslandsPreviewPanel()
        preview.previewAccentHex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        preview.previewToggles = section.pending.toggles
        preview.previewGlowEnabled = false
        val previewComponent = preview.createComponent()
        elementPreview = preview

        val settings = AyuIslandsSettings.getInstance()
        val collapsible =
            panel.collapsibleGroup("Accent Elements") {
                premiumFeatureNotice(gate)
                // Subsection 1: Per-element toggles
                row { label("Per-element toggles").bold() }
                row {
                    // Left: checkbox columns + enable/disable
                    panel {
                        twoColumnsRow(
                            {
                                panel {
                                    row { label("Visual").bold() }
                                    for (id in AccentElementId.entries.filter { it.group == AccentGroup.VISUAL }) {
                                        buildToggleRow(this, id, licensed)
                                    }
                                }
                            },
                            {
                                panel {
                                    row { label("Interactive").bold() }
                                    for (id in AccentElementId.entries.filter { it.group == AccentGroup.INTERACTIVE }) {
                                        buildToggleRow(this, id, licensed)
                                    }
                                }
                            },
                        )
                        row {
                            link("Enable all") {
                                setAllToggles(enabled = true)
                            }.enabled(licensed)
                            link("Disable all") {
                                setAllToggles(enabled = false)
                            }.enabled(licensed)
                        }
                    }
                    // Right: hover preview mockup
                    cell(previewComponent).align(AlignY.CENTER)
                }

                // Subsection 2: Tab underline
                separator()
                row { label("Tab underline").bold() }
                buildActiveTabContent(gate)

                // Subsection 3: Bracket scope
                separator()
                row { label("Bracket scope").bold() }
                row {
                    val scopeCb =
                        checkBox("Highlight bracket scope on gutter")
                            .comment("Show accent-colored scope stripe when caret is on a bracket")
                    scopeCb.component.isSelected = section.pending.bracketScope
                    scopeCb.component.isEnabled = licensed
                    scopeCb.component.addActionListener {
                        if (!licensed) return@addActionListener
                        section.update { it.copy(bracketScope = scopeCb.component.isSelected) }
                    }
                    bracketScopeCheckbox = scopeCb.component
                }
            }
        collapsible.expanded = settings.state.accentElementsGroupExpanded
        collapsible.addExpandedListener { expanded ->
            settings.state.accentElementsGroupExpanded = expanded
        }
    }

    private fun setAllToggles(enabled: Boolean) {
        section.update { settings ->
            settings.copy(
                toggles =
                    AccentElementId.entries
                        .filter { it.group != AccentGroup.CHROME }
                        .associateWith { enabled },
            )
        }
        refreshCheckboxes()
        syncPreviewToggles()
        onToggleChanged?.invoke()
    }

    /**
     * Renders Tab-underline rows directly onto the receiver [Panel] — no group wrapper.
     * Called from inside the parent `Accent Elements` collapsibleGroup where this content
     * sits under the `Tab underline` subsection header.
     */
    private fun Panel.buildActiveTabContent(gate: PremiumFeatureGate) {
        val state = AyuIslandsSettings.getInstance().state
        val glowEnabled = state.glowEnabled
        val islandsUi = AyuVariant.isIslandsUi()

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
                segmented.selectedItem = GlowTabMode.fromName(section.pending.tabMode)
                segmented.enabled(licensed)
                @Suppress("UnstableApiUsage")
                segmented.whenItemSelected { mode ->
                    if (!licensed) return@whenItemSelected
                    section.update { it.copy(tabMode = mode.name) }
                    updateThicknessRowVisibility()
                }
                tabModeSegmented = segmented
            }.visible(islandsUi)

        // Underline thickness presets (non-Islands only)
        val tabModeIsOff = GlowTabMode.fromName(section.pending.tabMode) == GlowTabMode.OFF
        thicknessRow =
            row {
                label("Underline thickness")
                val thickSegmented =
                    segmentedButton(UNDERLINE_THICKNESS_PRESETS) { value -> text = "${value}px" }
                thickSegmented.selectedItem = section.pending.tabUnderlineHeight
                thickSegmented.enabled(licensed && !section.pending.tabUnderlineGlowSync)
                @Suppress("UnstableApiUsage")
                thickSegmented.whenItemSelected { value ->
                    if (licensed) {
                        section.update { it.copy(tabUnderlineHeight = value) }
                    }
                }
                thicknessSegmented = thickSegmented
            }.visibleWhenUnlockedOrPreview(!tabModeIsOff && !islandsUi, gate)

        // Sync with the glow width checkbox (hidden on Islands UI)
        syncRow =
            row {
                val cb = checkBox("Sync with glow width")
                cb.component.isSelected = section.pending.tabUnderlineGlowSync
                cb.component.isEnabled = licensed && glowEnabled
                if (!glowEnabled) {
                    cb.component.toolTipText = "Enable glow to sync"
                }
                cb.component.addActionListener {
                    if (!licensed) return@addActionListener
                    section.update { it.copy(tabUnderlineGlowSync = cb.component.isSelected) }
                    updateThicknessEnabledState()
                    if (section.pending.tabUnderlineGlowSync && glowEnabled) {
                        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
                        val syncedWidth = state.getWidthForStyle(style)
                        thicknessSegmented?.selectedItem = syncedWidth
                    }
                }
                syncCheckbox = cb.component
            }.visibleWhenUnlockedOrPreview(!tabModeIsOff && !islandsUi, gate)
    }

    private fun updateThicknessRowVisibility() {
        val isOff = GlowTabMode.fromName(section.pending.tabMode) == GlowTabMode.OFF
        val visible = if (licensed) !isOff && !AyuVariant.isIslandsUi() else !AyuVariant.isIslandsUi()
        thicknessRow?.visible(visible)
        syncRow?.visible(visible)
        if (visible) {
            updateThicknessEnabledState()
        }
    }

    private fun updateThicknessEnabledState() {
        thicknessSegmented?.enabled(licensed && !section.pending.tabUnderlineGlowSync)
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
            cb.component.isSelected = section.pending.toggles[id] ?: true
            cb.component.isEnabled = licensed && !isBlocking
            cb.component.addActionListener {
                if (!licensed || isBlocking) return@addActionListener
                section.update { it.copy(toggles = it.toggles + (id to cb.component.isSelected)) }
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
            cb.isSelected = section.pending.toggles[id] ?: true
        }
    }

    private fun syncPreviewToggles() {
        elementPreview?.previewToggles = section.pending.toggles
        elementPreview?.updatePreview()
    }

    override fun isModified(): Boolean = section.isModified()

    override fun apply() {
        if (!isModified()) return
        if (!LicenseChecker.isLicensedOrGrace()) return

        section.commit { pending, _ ->
            val state = AyuIslandsSettings.getInstance().state
            for ((id, enabled) in pending.toggles) {
                state.setToggle(id, enabled)
            }
            state.forceOverrides = pending.forceOverrides.toMutableSet()
            state.glowTabMode = pending.tabMode
            state.tabUnderlineHeight = pending.tabUnderlineHeight
            state.tabUnderlineGlowSync = pending.tabUnderlineGlowSync
            state.bracketScopeEnabled = pending.bracketScope
        }

        // Re-apply accent with new toggle states via applyForFocusedProject so per-project/
        // per-language overrides aren't stomped by the accent applied by AyuIslandsAccentPanel
        // earlier in the Configurable.apply cycle.
        val currentVariant = variant ?: return
        AccentApplicator.applyForFocusedProject(currentVariant)
    }

    override fun reset() {
        val restored = section.resetToStored()
        refreshCheckboxes()
        bracketScopeCheckbox?.isSelected = restored.bracketScope
        syncPreviewToggles()
        tabModeSegmented?.selectedItem = GlowTabMode.fromName(restored.tabMode)
        thicknessSegmented?.selectedItem = restored.tabUnderlineHeight
        syncCheckbox?.isSelected = restored.tabUnderlineGlowSync
        updateThicknessRowVisibility()
    }

    companion object {
        private val UNDERLINE_THICKNESS_PRESETS = listOf(2, 4, 6, 8)
    }
}
