package dev.ayuislands.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.vcs.VcsColorApplier
import dev.ayuislands.vcs.VcsColorBlender
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorContext
import dev.ayuislands.vcs.VcsColorPalette
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsColorSnapshot
import dev.ayuislands.vcs.VcsIntensity
import dev.ayuislands.vcs.VcsWriteMode
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JSlider

/**
 * Phase 40.2 — settings panel for VCS color customization.
 *
 * Phase 40.2b redesign — each section (Diff, Merge, Blame) carries its own
 * preset segmented button. Users mix intensities across sections (Diff on
 * Neon, Blame on Whisper, etc.). Inside each section, Custom mode reveals
 * per-category sliders for that section's surfaces.
 *
 * Mirrors the [AyuIslandsChromePanel] discipline:
 *  - Pending / stored pairs per field so [isModified] can diff without
 *    touching the live [AyuIslandsState] until [apply] is called.
 *  - Premium gate: unlicensed users see a placeholder comment + Pro upgrade
 *    link instead of the full content — no controls bound, no way to mutate
 *    VCS state without a license.
 *  - On [apply] the panel wraps the applier call in
 *    [VcsColorContext.withSnapshot] so a throw leaves persisted state
 *    untouched and the user can retry.
 */
class VcsColorPanel : AyuIslandsSettingsPanel {
    // ── Pending / stored state ────────────────────────────────────────────────

    private var pendingEnabled: Boolean = false
    private var storedEnabled: Boolean = false
    private var pendingDiffPreset: VcsColorPreset = VcsColorPreset.AMBIENT
    private var storedDiffPreset: VcsColorPreset = VcsColorPreset.AMBIENT
    private var pendingMergePreset: VcsColorPreset = VcsColorPreset.AMBIENT
    private var storedMergePreset: VcsColorPreset = VcsColorPreset.AMBIENT
    private var pendingBlamePreset: VcsColorPreset = VcsColorPreset.AMBIENT
    private var storedBlamePreset: VcsColorPreset = VcsColorPreset.AMBIENT
    private var pendingDiffIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var storedDiffIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var pendingProjectViewIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var storedProjectViewIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var pendingGutterIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var storedGutterIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var pendingConflictMarkerIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var storedConflictMarkerIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var pendingBlameIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var storedBlameIntensity: Int = VcsColorPreset.AMBIENT_SLIDER

    // ── Swing references (kept for reset-time refresh + test seams) ───────────

    private var variant: AyuVariant? = null
    private var licensed: Boolean = false
    private var enabledCheckbox: JBCheckBox? = null
    private var diffPresetSegmented: SegmentedButton<VcsColorPreset>? = null
    private var mergePresetSegmented: SegmentedButton<VcsColorPreset>? = null
    private var blamePresetSegmented: SegmentedButton<VcsColorPreset>? = null
    private var diffSlider: JSlider? = null
    private var projectViewSlider: JSlider? = null
    private var gutterSlider: JSlider? = null
    private var conflictMarkerSlider: JSlider? = null
    private var blameSlider: JSlider? = null
    private var diffValueLabel: JLabel? = null
    private var projectViewValueLabel: JLabel? = null
    private var gutterValueLabel: JLabel? = null
    private var conflictMarkerValueLabel: JLabel? = null
    private var blameValueLabel: JLabel? = null

    // DIFF section gets three swatches (modified blue / inserted green /
    // deleted red) all driven by the Diff viewer slider — surfaces the full
    // colour palette that one slider tints. MERGE and BLAME keep a single
    // swatch since each only owns one visible colour family.
    private var diffSwatchModified: JLabel? = null
    private var diffSwatchInserted: JLabel? = null
    private var diffSwatchDeleted: JLabel? = null
    private var mergeSectionSwatch: JLabel? = null
    private var blameSectionSwatch: JLabel? = null

    /** Drives `visibleIf` on each section's Custom-mode slider rows. */
    private val diffCustomSelected = AtomicBooleanProperty(pendingDiffPreset == VcsColorPreset.CUSTOM)
    private val mergeCustomSelected = AtomicBooleanProperty(pendingMergePreset == VcsColorPreset.CUSTOM)
    private val blameCustomSelected = AtomicBooleanProperty(pendingBlamePreset == VcsColorPreset.CUSTOM)

    /** Drives `visibleIf(masterEnabled)` so all sections collapse when master is off. */
    private val masterEnabled = AtomicBooleanProperty(pendingEnabled)

    /**
     * Tracks whether the panel is mid-listener-suppress (preset switch or
     * reset). Prevents a slider's change listener from feeding back into the
     * section preset when programmatic snap moves the slider.
     */
    private var suppressSliderListeners: Boolean = false

    // ── Panel lifecycle ───────────────────────────────────────────────────────

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        this.variant = variant
        licensed = LicenseChecker.isLicensedOrGrace()
        val state = AyuIslandsSettings.getInstance().state
        loadStored(state)
        if (!licensed) {
            panel.buildUnlicensedContent()
            return
        }
        panel.buildLicensedContent(state)
    }

    private fun Panel.buildUnlicensedContent() {
        row {
            comment(
                "VCS color customization is a Pro feature. " +
                    "Free version uses the default colors shipped with v2.6.2.",
            )
        }
        row {
            link("Get Ayu Islands Pro — unlock VCS color customization") {
                LicenseChecker.requestLicense("Unlock VCS color intensity customization")
            }
        }
    }

    private fun Panel.buildLicensedContent(state: AyuIslandsState) {
        buildMasterToggleRow()
        buildSection(VcsSection.DIFF, state)
        buildSection(VcsSection.MERGE, state)
        buildSection(VcsSection.BLAME, state)
    }

    private fun Panel.buildMasterToggleRow() {
        row {
            val cb = checkBox("Enable VCS color customization")
            cb.component.isSelected = pendingEnabled
            cb.component.addActionListener {
                pendingEnabled = cb.component.isSelected
                masterEnabled.set(pendingEnabled)
            }
            enabledCheckbox = cb.component
        }
    }

    private fun Panel.buildSection(
        section: VcsSection,
        state: AyuIslandsState,
    ) {
        val ctx = sectionContext(section)
        val collapsible =
            collapsibleGroup(section.title) {
                buildSectionPresetRow(section, ctx)
                for ((category, label) in section.sliders) {
                    buildSliderRow(category, label, ctx.customVisible)
                }
            }
        collapsible.expanded =
            when (section) {
                VcsSection.DIFF -> state.vcsDiffSectionExpanded
                VcsSection.MERGE -> state.vcsMergeSectionExpanded
                VcsSection.BLAME -> state.vcsBlameSectionExpanded
            }
        collapsible.addExpandedListener { expanded ->
            val live = AyuIslandsSettings.getInstance().state
            when (section) {
                VcsSection.DIFF -> live.vcsDiffSectionExpanded = expanded
                VcsSection.MERGE -> live.vcsMergeSectionExpanded = expanded
                VcsSection.BLAME -> live.vcsBlameSectionExpanded = expanded
            }
        }
        // Hide the entire section when the master toggle is off — sections
        // are meaningless without VCS color customization enabled, and
        // dangling collapsible add visual noise.
        collapsible.visibleIf(masterEnabled)
    }

    private data class SectionContext(
        val initialPreset: VcsColorPreset,
        val customVisible: AtomicBooleanProperty,
        val storeSegmented: (SegmentedButton<VcsColorPreset>) -> Unit,
    )

    private fun sectionContext(section: VcsSection): SectionContext =
        when (section) {
            VcsSection.DIFF ->
                SectionContext(pendingDiffPreset, diffCustomSelected) { diffPresetSegmented = it }
            VcsSection.MERGE ->
                SectionContext(pendingMergePreset, mergeCustomSelected) { mergePresetSegmented = it }
            VcsSection.BLAME ->
                SectionContext(pendingBlamePreset, blameCustomSelected) { blamePresetSegmented = it }
        }

    @Suppress("UnstableApiUsage")
    private fun Panel.buildSectionPresetRow(
        section: VcsSection,
        ctx: SectionContext,
    ) {
        row("Preset:") {
            val segmented = segmentedButton(VcsColorPreset.entries) { preset -> text = preset.displayName }
            segmented.maxButtonsCount(VcsColorPreset.entries.size)
            segmented.selectedItem = ctx.initialPreset
            segmented.whenItemSelected { preset ->
                onSectionPresetChosen(section, preset)
                ctx.customVisible.set(preset == VcsColorPreset.CUSTOM)
            }
            ctx.storeSegmented(segmented)
            // Section colour swatches sit 4px right of the Custom button.
            // DIFF carries three swatches (modified / inserted / deleted) because
            // its slider tints three colour families in parallel; MERGE and BLAME
            // each surface a single dominant colour.
            val iconicValue =
                when (section.iconicCategory) {
                    VcsColorCategory.DIFF_VIEWER -> pendingDiffIntensity
                    VcsColorCategory.CONFLICT_MARKERS -> pendingConflictMarkerIntensity
                    VcsColorCategory.BLAME_GUTTER -> pendingBlameIntensity
                    else -> VcsColorPreset.AMBIENT_SLIDER
                }
            for (keyName in section.swatchKeyNames) {
                val swatch = JLabel(" ")
                swatch.preferredSize = Dimension(SWATCH_SIZE, SWATCH_SIZE)
                swatch.minimumSize = Dimension(SWATCH_SIZE, SWATCH_SIZE)
                swatch.isOpaque = true
                swatch.background = computeSwatchColor(section.iconicCategory, keyName, iconicValue)
                swatch.border = BorderFactory.createLineBorder(JBColor.border(), 1)
                cell(swatch).gap(com.intellij.ui.dsl.builder.RightGap.SMALL)
                // Inlined stash — keeps the per-class function count under
                // detekt's TooManyFunctions ceiling.
                when (section) {
                    VcsSection.DIFF ->
                        when (keyName) {
                            "DIFF_MODIFIED" -> diffSwatchModified = swatch
                            "DIFF_INSERTED" -> diffSwatchInserted = swatch
                            "DIFF_DELETED" -> diffSwatchDeleted = swatch
                        }
                    VcsSection.MERGE -> mergeSectionSwatch = swatch
                    VcsSection.BLAME -> blameSectionSwatch = swatch
                }
            }
        }
    }

    private fun Panel.buildSliderRow(
        category: VcsColorCategory,
        label: String,
        sectionCustomVisible: AtomicBooleanProperty,
    ) {
        val initialValue =
            when (category) {
                VcsColorCategory.DIFF_VIEWER -> pendingDiffIntensity
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> pendingProjectViewIntensity
                VcsColorCategory.EDITOR_GUTTER -> pendingGutterIntensity
                VcsColorCategory.CONFLICT_MARKERS -> pendingConflictMarkerIntensity
                VcsColorCategory.BLAME_GUTTER -> pendingBlameIntensity
                else -> VcsColorPreset.AMBIENT_SLIDER
            }
        val sliderRow =
            row(label) {
                val slider =
                    JSlider(
                        MIN_INTENSITY,
                        MAX_INTENSITY,
                        initialValue.coerceIn(MIN_INTENSITY, MAX_INTENSITY),
                    )
                slider.paintTicks = true
                slider.majorTickSpacing = INTENSITY_MAJOR_TICK
                slider.minorTickSpacing = INTENSITY_MINOR_TICK
                val valueLabel = JLabel("$initialValue")
                slider.addChangeListener { onSliderChanged(category, slider.value) }
                cell(slider).resizableColumn().align(Align.FILL)
                cell(valueLabel)
                // Stash Swing refs for reset() refresh + test seams.
                when (category) {
                    VcsColorCategory.DIFF_VIEWER -> {
                        diffSlider = slider
                        diffValueLabel = valueLabel
                    }
                    VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> {
                        projectViewSlider = slider
                        projectViewValueLabel = valueLabel
                    }
                    VcsColorCategory.EDITOR_GUTTER -> {
                        gutterSlider = slider
                        gutterValueLabel = valueLabel
                    }
                    VcsColorCategory.CONFLICT_MARKERS -> {
                        conflictMarkerSlider = slider
                        conflictMarkerValueLabel = valueLabel
                    }
                    VcsColorCategory.BLAME_GUTTER -> {
                        blameSlider = slider
                        blameValueLabel = valueLabel
                    }
                    else -> Unit
                }
            }
        sliderRow.visibleIf(sectionCustomVisible)
    }

    /**
     * Returns the blended color for the ColorKey-typed palette entry named
     * [keyName] inside [category], at slider position [value]. Falls back to
     * [JBColor.GRAY] when the variant isn't resolvable yet (settings dialog
     * opened before any Ayu theme activates) or when no matching entry exists.
     */
    private fun computeSwatchColor(
        category: VcsColorCategory,
        keyName: String,
        value: Int,
    ): Color {
        val activeVariant = this.variant ?: AyuVariant.DARK
        val entry =
            VcsColorPalette.entriesFor(category).firstOrNull {
                it.keyName == keyName && it.mode == VcsWriteMode.COLOR_KEY
            } ?: return JBColor.GRAY
        val (base, target) = VcsColorPalette.endpoints(entry, activeVariant)
        return VcsColorBlender.blend(base, target, VcsIntensity.of(value))
    }

    /**
     * Handles a slider value change for [category]. Writes the new value into
     * the matching `pending*` field, refreshes the value label, and (if the
     * change is a user gesture rather than programmatic) promotes the
     * governing section's preset to [VcsColorPreset.CUSTOM] so the manual
     * slider position actually takes effect on apply.
     */
    private fun onSliderChanged(
        category: VcsColorCategory,
        value: Int,
    ) {
        when (category) {
            VcsColorCategory.DIFF_VIEWER -> {
                pendingDiffIntensity = value
                diffValueLabel?.text = "$value"
                diffSwatchModified?.background = computeSwatchColor(category, "DIFF_MODIFIED", value)
                diffSwatchInserted?.background = computeSwatchColor(category, "DIFF_INSERTED", value)
                diffSwatchDeleted?.background = computeSwatchColor(category, "DIFF_DELETED", value)
            }
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> {
                pendingProjectViewIntensity = value
                projectViewValueLabel?.text = "$value"
            }
            VcsColorCategory.EDITOR_GUTTER -> {
                pendingGutterIntensity = value
                gutterValueLabel?.text = "$value"
            }
            VcsColorCategory.CONFLICT_MARKERS -> {
                pendingConflictMarkerIntensity = value
                conflictMarkerValueLabel?.text = "$value"
                mergeSectionSwatch?.background = computeSwatchColor(category, "DIFF_CONFLICT", value)
            }
            VcsColorCategory.BLAME_GUTTER -> {
                pendingBlameIntensity = value
                blameValueLabel?.text = "$value"
                blameSectionSwatch?.background =
                    computeSwatchColor(category, "ANNOTATIONS_LAST_COMMIT_COLOR", value)
            }
            else -> return
        }
        if (suppressSliderListeners) return
        promoteSectionToCustom(category)
    }

    private fun promoteSectionToCustom(category: VcsColorCategory) {
        when (category) {
            VcsColorCategory.DIFF_VIEWER,
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
            VcsColorCategory.EDITOR_GUTTER,
            -> {
                if (pendingDiffPreset == VcsColorPreset.CUSTOM) return
                pendingDiffPreset = VcsColorPreset.CUSTOM
                diffCustomSelected.set(true)
                diffPresetSegmented?.selectedItem = VcsColorPreset.CUSTOM
            }
            VcsColorCategory.CONFLICT_MARKERS -> {
                if (pendingMergePreset == VcsColorPreset.CUSTOM) return
                pendingMergePreset = VcsColorPreset.CUSTOM
                mergeCustomSelected.set(true)
                mergePresetSegmented?.selectedItem = VcsColorPreset.CUSTOM
            }
            VcsColorCategory.BLAME_GUTTER -> {
                if (pendingBlamePreset == VcsColorPreset.CUSTOM) return
                pendingBlamePreset = VcsColorPreset.CUSTOM
                blameCustomSelected.set(true)
                blamePresetSegmented?.selectedItem = VcsColorPreset.CUSTOM
            }
            else -> Unit
        }
    }

    override fun isModified(): Boolean =
        pendingEnabled != storedEnabled ||
            pendingDiffPreset != storedDiffPreset ||
            pendingMergePreset != storedMergePreset ||
            pendingBlamePreset != storedBlamePreset ||
            pendingDiffIntensity != storedDiffIntensity ||
            pendingProjectViewIntensity != storedProjectViewIntensity ||
            pendingGutterIntensity != storedGutterIntensity ||
            pendingConflictMarkerIntensity != storedConflictMarkerIntensity ||
            pendingBlameIntensity != storedBlameIntensity

    override fun apply() {
        if (!isModified()) return
        if (!LicenseChecker.isLicensedOrGrace()) {
            LOG.info(
                "VcsColorPanel.apply: license no longer active; " +
                    "skipping VCS state persistence and re-apply",
            )
            return
        }

        // Resolve per-section preset choices into per-category intensities,
        // then materialise the snapshot. The applier reads only per-category
        // intensities — preset/Custom branching is fully resolved here.
        // Non-Custom presets defer to VcsColorPreset.intensityFor; Custom
        // mode pulls the slider values directly.
        val diffSnap =
            if (pendingDiffPreset == VcsColorPreset.CUSTOM) {
                null
            } else {
                pendingDiffPreset.intensityFor(VcsColorCategory.DIFF_VIEWER)
            }
        val resolved =
            mapOf(
                VcsColorCategory.DIFF_VIEWER to (diffSnap ?: pendingDiffIntensity),
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS to (diffSnap ?: pendingProjectViewIntensity),
                VcsColorCategory.EDITOR_GUTTER to (diffSnap ?: pendingGutterIntensity),
                VcsColorCategory.CONFLICT_MARKERS to
                    if (pendingMergePreset == VcsColorPreset.CUSTOM) {
                        pendingConflictMarkerIntensity
                    } else {
                        pendingMergePreset.intensityFor(VcsColorCategory.CONFLICT_MARKERS)
                    },
                VcsColorCategory.BLAME_GUTTER to
                    if (pendingBlamePreset == VcsColorPreset.CUSTOM) {
                        pendingBlameIntensity
                    } else {
                        pendingBlamePreset.intensityFor(VcsColorCategory.BLAME_GUTTER)
                    },
            )
        val snapshot = VcsColorSnapshot(enabled = pendingEnabled, perCategoryIntensities = resolved)

        try {
            VcsColorContext.withSnapshot(snapshot) {
                VcsColorApplier.applyAll()
            }
        } catch (exception: RuntimeException) {
            LOG.warn(
                "VcsColorPanel.apply: VCS color re-apply threw; " +
                    "leaving pending VCS state uncommitted so the user can retry",
                exception,
            )
            // Surface a balloon so the user knows their click was rejected.
            // Notification dispatch is itself try-wrapped: a shutdown-race or
            // notification-subsystem hiccup must not mask the apply failure.
            try {
                NotificationGroupManager
                    .getInstance()
                    .getNotificationGroup("Ayu Islands")
                    .createNotification(
                        "VCS colors could not be applied",
                        "Settings were not saved — see idea.log for details. " +
                            "Adjust any value in the VCS panel and click Apply again to retry.",
                        NotificationType.WARNING,
                    ).notify(null)
            } catch (notificationException: RuntimeException) {
                LOG.warn("VcsColorPanel.apply: failed to surface VCS-apply error balloon", notificationException)
            }
            return
        }
        val state = AyuIslandsSettings.getInstance().state
        state.vcsColorEnabled = pendingEnabled
        state.vcsDiffPreset = pendingDiffPreset.name
        state.vcsMergePreset = pendingMergePreset.name
        state.vcsBlamePreset = pendingBlamePreset.name
        state.vcsDiffIntensity = pendingDiffIntensity
        state.vcsProjectViewIntensity = pendingProjectViewIntensity
        state.vcsGutterIntensity = pendingGutterIntensity
        state.vcsConflictMarkerIntensity = pendingConflictMarkerIntensity
        state.vcsBlameIntensity = pendingBlameIntensity
        loadStored(state)
    }

    override fun reset() {
        val state = AyuIslandsSettings.getInstance().state
        loadStored(state)
        suppressSliderListeners = true
        try {
            enabledCheckbox?.isSelected = pendingEnabled
            diffPresetSegmented?.selectedItem = pendingDiffPreset
            mergePresetSegmented?.selectedItem = pendingMergePreset
            blamePresetSegmented?.selectedItem = pendingBlamePreset
            masterEnabled.set(pendingEnabled)
            diffCustomSelected.set(pendingDiffPreset == VcsColorPreset.CUSTOM)
            mergeCustomSelected.set(pendingMergePreset == VcsColorPreset.CUSTOM)
            blameCustomSelected.set(pendingBlamePreset == VcsColorPreset.CUSTOM)
            diffSlider?.value = pendingDiffIntensity
            diffValueLabel?.text = "$pendingDiffIntensity"
            diffSwatchModified?.background =
                computeSwatchColor(VcsColorCategory.DIFF_VIEWER, "DIFF_MODIFIED", pendingDiffIntensity)
            diffSwatchInserted?.background =
                computeSwatchColor(VcsColorCategory.DIFF_VIEWER, "DIFF_INSERTED", pendingDiffIntensity)
            diffSwatchDeleted?.background =
                computeSwatchColor(VcsColorCategory.DIFF_VIEWER, "DIFF_DELETED", pendingDiffIntensity)
            projectViewSlider?.value = pendingProjectViewIntensity
            projectViewValueLabel?.text = "$pendingProjectViewIntensity"
            gutterSlider?.value = pendingGutterIntensity
            gutterValueLabel?.text = "$pendingGutterIntensity"
            conflictMarkerSlider?.value = pendingConflictMarkerIntensity
            conflictMarkerValueLabel?.text = "$pendingConflictMarkerIntensity"
            mergeSectionSwatch?.background =
                computeSwatchColor(
                    VcsColorCategory.CONFLICT_MARKERS,
                    "DIFF_CONFLICT",
                    pendingConflictMarkerIntensity,
                )
            blameSlider?.value = pendingBlameIntensity
            blameValueLabel?.text = "$pendingBlameIntensity"
            blameSectionSwatch?.background =
                computeSwatchColor(
                    VcsColorCategory.BLAME_GUTTER,
                    "ANNOTATIONS_LAST_COMMIT_COLOR",
                    pendingBlameIntensity,
                )
        } finally {
            suppressSliderListeners = false
        }
    }

    private fun loadStored(state: AyuIslandsState) {
        storedEnabled = state.vcsColorEnabled
        pendingEnabled = storedEnabled
        storedDiffPreset = state.effectiveVcsDiffPreset()
        pendingDiffPreset = storedDiffPreset
        storedMergePreset = state.effectiveVcsMergePreset()
        pendingMergePreset = storedMergePreset
        storedBlamePreset = state.effectiveVcsBlamePreset()
        pendingBlamePreset = storedBlamePreset
        storedDiffIntensity = state.vcsDiffIntensity
        pendingDiffIntensity = state.vcsDiffIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        storedProjectViewIntensity = state.vcsProjectViewIntensity
        pendingProjectViewIntensity = state.vcsProjectViewIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        storedGutterIntensity = state.vcsGutterIntensity
        pendingGutterIntensity = state.vcsGutterIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        storedConflictMarkerIntensity = state.vcsConflictMarkerIntensity
        pendingConflictMarkerIntensity = state.vcsConflictMarkerIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        storedBlameIntensity = state.vcsBlameIntensity
        pendingBlameIntensity = state.vcsBlameIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        masterEnabled.set(pendingEnabled)
        diffCustomSelected.set(pendingDiffPreset == VcsColorPreset.CUSTOM)
        mergeCustomSelected.set(pendingMergePreset == VcsColorPreset.CUSTOM)
        blameCustomSelected.set(pendingBlamePreset == VcsColorPreset.CUSTOM)
    }

    /**
     * Switching a section's preset writes the new preset into that section's
     * pending field, then (for non-Custom presets) snaps every slider owned by
     * the section to the preset's canonical position. Custom mode leaves
     * sliders alone so the user can fine-tune from the last preset.
     */
    private fun onSectionPresetChosen(
        section: VcsSection,
        preset: VcsColorPreset,
    ) {
        when (section) {
            VcsSection.DIFF -> pendingDiffPreset = preset
            VcsSection.MERGE -> pendingMergePreset = preset
            VcsSection.BLAME -> pendingBlamePreset = preset
        }
        if (preset == VcsColorPreset.CUSTOM) return
        suppressSliderListeners = true
        try {
            for ((category, _) in section.sliders) {
                val snap = preset.intensityFor(category)
                writeSliderValue(category, snap)
            }
        } finally {
            suppressSliderListeners = false
        }
    }

    private fun writeSliderValue(
        category: VcsColorCategory,
        value: Int,
    ) {
        when (category) {
            VcsColorCategory.DIFF_VIEWER -> {
                pendingDiffIntensity = value
                diffSlider?.value = value
                diffValueLabel?.text = "$value"
                diffSwatchModified?.background = computeSwatchColor(category, "DIFF_MODIFIED", value)
                diffSwatchInserted?.background = computeSwatchColor(category, "DIFF_INSERTED", value)
                diffSwatchDeleted?.background = computeSwatchColor(category, "DIFF_DELETED", value)
            }
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> {
                pendingProjectViewIntensity = value
                projectViewSlider?.value = value
                projectViewValueLabel?.text = "$value"
            }
            VcsColorCategory.EDITOR_GUTTER -> {
                pendingGutterIntensity = value
                gutterSlider?.value = value
                gutterValueLabel?.text = "$value"
            }
            VcsColorCategory.CONFLICT_MARKERS -> {
                pendingConflictMarkerIntensity = value
                conflictMarkerSlider?.value = value
                conflictMarkerValueLabel?.text = "$value"
                mergeSectionSwatch?.background = computeSwatchColor(category, "DIFF_CONFLICT", value)
            }
            VcsColorCategory.BLAME_GUTTER -> {
                pendingBlameIntensity = value
                blameSlider?.value = value
                blameValueLabel?.text = "$value"
                blameSectionSwatch?.background =
                    computeSwatchColor(category, "ANNOTATIONS_LAST_COMMIT_COLOR", value)
            }
            else -> Unit
        }
    }

    // ── @TestOnly seams ───────────────────────────────────────────────────────

    @TestOnly internal fun getPendingEnabledForTest(): Boolean = pendingEnabled

    @TestOnly internal fun setPendingEnabledForTest(value: Boolean) {
        pendingEnabled = value
    }

    @TestOnly internal fun getPendingPresetForTest(section: VcsSection): VcsColorPreset =
        when (section) {
            VcsSection.DIFF -> pendingDiffPreset
            VcsSection.MERGE -> pendingMergePreset
            VcsSection.BLAME -> pendingBlamePreset
        }

    @TestOnly internal fun setPendingPresetForTest(
        section: VcsSection,
        preset: VcsColorPreset,
    ) {
        when (section) {
            VcsSection.DIFF -> pendingDiffPreset = preset
            VcsSection.MERGE -> pendingMergePreset = preset
            VcsSection.BLAME -> pendingBlamePreset = preset
        }
    }

    @TestOnly internal fun getPendingIntensityForTest(category: VcsColorCategory): Int =
        when (category) {
            VcsColorCategory.DIFF_VIEWER -> pendingDiffIntensity
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> pendingProjectViewIntensity
            VcsColorCategory.EDITOR_GUTTER -> pendingGutterIntensity
            VcsColorCategory.CONFLICT_MARKERS -> pendingConflictMarkerIntensity
            VcsColorCategory.BLAME_GUTTER -> pendingBlameIntensity
            else -> VcsColorPreset.AMBIENT_SLIDER
        }

    @TestOnly internal fun setPendingIntensityForTest(
        category: VcsColorCategory,
        value: Int,
    ) {
        when (category) {
            VcsColorCategory.DIFF_VIEWER -> pendingDiffIntensity = value
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> pendingProjectViewIntensity = value
            VcsColorCategory.EDITOR_GUTTER -> pendingGutterIntensity = value
            VcsColorCategory.CONFLICT_MARKERS -> pendingConflictMarkerIntensity = value
            VcsColorCategory.BLAME_GUTTER -> pendingBlameIntensity = value
            else -> Unit
        }
    }

    @TestOnly internal fun triggerSectionPresetChosenForTest(
        section: VcsSection,
        preset: VcsColorPreset,
    ) {
        onSectionPresetChosen(section, preset)
    }

    companion object {
        private val LOG = logger<VcsColorPanel>()
        internal const val MIN_INTENSITY = 0
        internal const val MAX_INTENSITY = 100
        private const val INTENSITY_MAJOR_TICK = 25
        private const val INTENSITY_MINOR_TICK = 5
        private const val SWATCH_SIZE = 25
    }
}

/**
 * Selector for the three VCS panel sections. Encapsulates each section's
 * user-visible title, the list of per-category sliders it owns in Custom
 * mode, and the iconic category whose blended color drives the section's
 * preset-row swatch.
 */
internal enum class VcsSection(
    val title: String,
    val sliders: List<Pair<VcsColorCategory, String>>,
    val iconicCategory: VcsColorCategory,
    val swatchKeyNames: List<String>,
) {
    DIFF(
        title = "Diff and File Status",
        sliders =
            listOf(
                VcsColorCategory.DIFF_VIEWER to "Diff viewer:",
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS to "Project View:",
                VcsColorCategory.EDITOR_GUTTER to "Editor gutter:",
            ),
        iconicCategory = VcsColorCategory.DIFF_VIEWER,
        // Three swatches showing the full Diff slider's effect: modified
        // (blue), inserted (green), deleted (red). All driven by the same
        // pendingDiffIntensity.
        swatchKeyNames = listOf("DIFF_MODIFIED", "DIFF_INSERTED", "DIFF_DELETED"),
    ),
    MERGE(
        title = "Merge and Conflict",
        sliders = listOf(VcsColorCategory.CONFLICT_MARKERS to "Conflict markers:"),
        iconicCategory = VcsColorCategory.CONFLICT_MARKERS,
        swatchKeyNames = listOf("DIFF_CONFLICT"),
    ),
    BLAME(
        title = "Blame and History",
        sliders = listOf(VcsColorCategory.BLAME_GUTTER to "Blame gutter:"),
        iconicCategory = VcsColorCategory.BLAME_GUTTER,
        swatchKeyNames = listOf("ANNOTATIONS_LAST_COMMIT_COLOR"),
    ),
}
