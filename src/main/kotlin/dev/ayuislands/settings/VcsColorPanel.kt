package dev.ayuislands.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.vcs.VcsColorApplier
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorContext
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsColorSnapshot
import org.jetbrains.annotations.TestOnly
import javax.swing.JLabel
import javax.swing.JSlider

/**
 * Settings panel for VCS color customization.
 *
 * Each section (Diff, Merge, Blame) carries its own preset segmented button.
 * Users mix intensities across sections (Diff on Neon, Blame on Whisper,
 * etc.). Inside each section, Custom mode reveals per-category sliders for
 * that section's surfaces.
 *
 * Mirrors the [AyuIslandsChromePanel] discipline:
 *  - Pending/stored bookkeeping delegated to a [SettingsSection] over the
 *    immutable [VcsColorSettings] snapshot so [isModified] can diff without
 *    touching the live [AyuIslandsState] until [apply] is called.
 *  - Premium gate: unlicensed users see the full settings surface and preview,
 *    but controls are locked. Runtime apply still re-checks the license so a
 *    mid-session entitlement change cannot persist premium state.
 *  - On [apply] the panel wraps the applier call in
 *    [VcsColorContext.withSnapshot] so a throw leaves persisted state
 *    untouched and the user can retry.
 */
class VcsColorPanel : AyuIslandsSettingsPanel {
    // ── Pending / stored state ────────────────────────────────────────────────

    private data class VcsColorSettings(
        val enabled: Boolean = false,
        val diffPreset: VcsColorPreset = VcsColorPreset.AMBIENT,
        val mergePreset: VcsColorPreset = VcsColorPreset.AMBIENT,
        val blamePreset: VcsColorPreset = VcsColorPreset.AMBIENT,
        val diffIntensity: Int = VcsColorPreset.AMBIENT_SLIDER,
        val projectViewIntensity: Int = VcsColorPreset.AMBIENT_SLIDER,
        val gutterIntensity: Int = VcsColorPreset.AMBIENT_SLIDER,
        val conflictMarkerIntensity: Int = VcsColorPreset.AMBIENT_SLIDER,
        val blameIntensity: Int = VcsColorPreset.AMBIENT_SLIDER,
    ) {
        fun presetFor(vcsSection: VcsSection): VcsColorPreset =
            when (vcsSection) {
                VcsSection.DIFF -> diffPreset
                VcsSection.MERGE -> mergePreset
                VcsSection.BLAME -> blamePreset
            }

        fun withPreset(
            vcsSection: VcsSection,
            preset: VcsColorPreset,
        ): VcsColorSettings =
            when (vcsSection) {
                VcsSection.DIFF -> copy(diffPreset = preset)
                VcsSection.MERGE -> copy(mergePreset = preset)
                VcsSection.BLAME -> copy(blamePreset = preset)
            }

        fun intensityFor(category: VcsColorCategory): Int =
            when (category) {
                VcsColorCategory.DIFF_VIEWER -> diffIntensity
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> projectViewIntensity
                VcsColorCategory.EDITOR_GUTTER -> gutterIntensity
                VcsColorCategory.CONFLICT_MARKERS -> conflictMarkerIntensity
                VcsColorCategory.BLAME_GUTTER -> blameIntensity
                else -> VcsColorPreset.AMBIENT_SLIDER
            }

        fun withIntensity(
            category: VcsColorCategory,
            value: Int,
        ): VcsColorSettings =
            when (category) {
                VcsColorCategory.DIFF_VIEWER -> copy(diffIntensity = value)
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> copy(projectViewIntensity = value)
                VcsColorCategory.EDITOR_GUTTER -> copy(gutterIntensity = value)
                VcsColorCategory.CONFLICT_MARKERS -> copy(conflictMarkerIntensity = value)
                VcsColorCategory.BLAME_GUTTER -> copy(blameIntensity = value)
                else -> this
            }
    }

    private val section =
        SettingsSection(initial = VcsColorSettings()) {
            val state = AyuIslandsSettings.getInstance().state
            VcsColorSettings(
                enabled = state.vcsColorEnabled,
                diffPreset = state.effectiveVcsPresetFor(VcsColorCategory.DIFF_VIEWER),
                mergePreset = state.effectiveVcsPresetFor(VcsColorCategory.CONFLICT_MARKERS),
                blamePreset = state.effectiveVcsPresetFor(VcsColorCategory.BLAME_GUTTER),
                diffIntensity = state.vcsDiffIntensity,
                projectViewIntensity = state.vcsProjectViewIntensity,
                gutterIntensity = state.vcsGutterIntensity,
                conflictMarkerIntensity = state.vcsConflictMarkerIntensity,
                blameIntensity = state.vcsBlameIntensity,
            )
        }

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
    private var vcsPreview: VcsColorPreviewComponent? = null

    /** Drives `visibleIf` on each section's Custom-mode slider rows. */
    private val diffCustomSelected = AtomicBooleanProperty(section.pending.diffPreset == VcsColorPreset.CUSTOM)
    private val mergeCustomSelected = AtomicBooleanProperty(section.pending.mergePreset == VcsColorPreset.CUSTOM)
    private val blameCustomSelected = AtomicBooleanProperty(section.pending.blamePreset == VcsColorPreset.CUSTOM)

    /** Drives `visibleIf(masterEnabled)` so all sections collapse when master is off. */
    private val masterEnabled = AtomicBooleanProperty(section.pending.enabled)

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
        val gate =
            PremiumFeatureGate(
                featureName = "VCS color customization",
                lockedDescription =
                    "VCS color customization is a Pro feature. " +
                        "Preview the available diff, file-status, gutter, conflict, and blame controls here.",
                requestMessage = "Unlock VCS color intensity customization",
                isUnlocked = licensed,
            )
        val state = AyuIslandsSettings.getInstance().state
        loadStored()
        masterEnabled.set(section.pending.enabled || !gate.isUnlocked)
        panel.buildContent(state, gate)
    }

    private fun Panel.buildContent(
        state: AyuIslandsState,
        gate: PremiumFeatureGate,
    ) {
        premiumFeatureNotice(gate)
        buildMasterToggleRow(gate)
        row {
            val preview = VcsColorPreviewComponent(variant ?: AyuVariant.DARK, previewIntensities())
            vcsPreview = preview
            cell(preview)
                .resizableColumn()
                .align(Align.FILL)
        }.visibleIf(masterEnabled)
        buildSection(VcsSection.DIFF, state, gate)
        buildSection(VcsSection.MERGE, state, gate)
        buildSection(VcsSection.BLAME, state, gate)
    }

    private fun Panel.buildMasterToggleRow(gate: PremiumFeatureGate) {
        row {
            val cb = checkBox("Enable VCS color customization")
            cb.component.isSelected = section.pending.enabled
            cb.component.applyPremiumLock(gate)
            cb.component.addActionListener {
                if (!gate.isUnlocked) return@addActionListener
                section.update { it.copy(enabled = cb.component.isSelected) }
                masterEnabled.set(section.pending.enabled)
            }
            enabledCheckbox = cb.component
        }
    }

    private fun Panel.buildSection(
        vcsSection: VcsSection,
        state: AyuIslandsState,
        gate: PremiumFeatureGate,
    ) {
        val ctx = sectionContext(vcsSection)
        val collapsible =
            collapsibleGroup(vcsSection.title) {
                buildSectionPresetRow(vcsSection, ctx, gate)
                for ((category, label) in vcsSection.sliders) {
                    buildSliderRow(category, label, ctx.customVisible, gate)
                }
            }
        collapsible.expanded =
            when (vcsSection) {
                VcsSection.DIFF -> state.vcsDiffSectionExpanded
                VcsSection.MERGE -> state.vcsMergeSectionExpanded
                VcsSection.BLAME -> state.vcsBlameSectionExpanded
            }
        collapsible.addExpandedListener { expanded ->
            val live = AyuIslandsSettings.getInstance().state
            when (vcsSection) {
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

    private fun sectionContext(vcsSection: VcsSection): SectionContext =
        when (vcsSection) {
            VcsSection.DIFF -> {
                SectionContext(section.pending.diffPreset, diffCustomSelected) { diffPresetSegmented = it }
            }

            VcsSection.MERGE -> {
                SectionContext(section.pending.mergePreset, mergeCustomSelected) { mergePresetSegmented = it }
            }

            VcsSection.BLAME -> {
                SectionContext(section.pending.blamePreset, blameCustomSelected) { blamePresetSegmented = it }
            }
        }

    @Suppress("UnstableApiUsage")
    private fun Panel.buildSectionPresetRow(
        vcsSection: VcsSection,
        ctx: SectionContext,
        gate: PremiumFeatureGate,
    ) {
        row("Preset:") {
            val segmented = segmentedButton(VcsColorPreset.entries) { preset -> text = preset.displayName }
            segmented.maxButtonsCount(VcsColorPreset.entries.size)
            segmented.selectedItem = ctx.initialPreset
            segmented.enabled(gate.isUnlocked)
            segmented.whenItemSelected { preset ->
                if (!gate.isUnlocked) return@whenItemSelected
                onSectionPresetChosen(vcsSection, preset)
                ctx.customVisible.set(preset == VcsColorPreset.CUSTOM)
            }
            ctx.storeSegmented(segmented)
        }
    }

    private fun Panel.buildSliderRow(
        category: VcsColorCategory,
        label: String,
        sectionCustomVisible: AtomicBooleanProperty,
        gate: PremiumFeatureGate,
    ) {
        val initialValue = section.pending.intensityFor(category)
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
                slider.applyPremiumLock(gate)
                val valueLabel = JLabel("$initialValue")
                slider.addChangeListener {
                    if (gate.isUnlocked) {
                        onSliderChanged(category, slider.value)
                    }
                }
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

                    else -> {}
                }
            }
        sliderRow.visibleIfUnlockedOrPreview(sectionCustomVisible, gate)
    }

    private fun previewIntensities(): VcsPreviewIntensities =
        VcsPreviewIntensities(
            diffViewer = section.pending.diffIntensity,
            projectView = section.pending.projectViewIntensity,
            editorGutter = section.pending.gutterIntensity,
            conflictMarkers = section.pending.conflictMarkerIntensity,
            blameGutter = section.pending.blameIntensity,
        )

    /**
     * Handles a slider value change for [category]. Writes the new value into
     * the pending snapshot, refreshes the value label, and (if the change is
     * a user gesture rather than programmatic) promotes the governing
     * section's preset to [VcsColorPreset.CUSTOM] so the manual slider
     * position actually takes effect on apply.
     */
    private fun onSliderChanged(
        category: VcsColorCategory,
        value: Int,
    ) {
        when (category) {
            VcsColorCategory.DIFF_VIEWER,
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
            VcsColorCategory.EDITOR_GUTTER,
            VcsColorCategory.CONFLICT_MARKERS,
            VcsColorCategory.BLAME_GUTTER,
            -> {
                section.update { it.withIntensity(category, value) }
                valueLabelFor(category)?.text = "$value"
            }

            else -> {
                return
            }
        }
        vcsPreview?.updatePreview(variant ?: AyuVariant.DARK, previewIntensities())
        if (suppressSliderListeners) return
        promoteSectionToCustom(category)
    }

    private fun valueLabelFor(category: VcsColorCategory): JLabel? =
        when (category) {
            VcsColorCategory.DIFF_VIEWER -> diffValueLabel
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> projectViewValueLabel
            VcsColorCategory.EDITOR_GUTTER -> gutterValueLabel
            VcsColorCategory.CONFLICT_MARKERS -> conflictMarkerValueLabel
            VcsColorCategory.BLAME_GUTTER -> blameValueLabel
            else -> null
        }

    private fun promoteSectionToCustom(category: VcsColorCategory) {
        when (category) {
            VcsColorCategory.DIFF_VIEWER,
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
            VcsColorCategory.EDITOR_GUTTER,
            -> {
                if (section.pending.diffPreset == VcsColorPreset.CUSTOM) return
                section.update { it.copy(diffPreset = VcsColorPreset.CUSTOM) }
                diffCustomSelected.set(true)
                diffPresetSegmented?.selectedItem = VcsColorPreset.CUSTOM
            }

            VcsColorCategory.CONFLICT_MARKERS -> {
                if (section.pending.mergePreset == VcsColorPreset.CUSTOM) return
                section.update { it.copy(mergePreset = VcsColorPreset.CUSTOM) }
                mergeCustomSelected.set(true)
                mergePresetSegmented?.selectedItem = VcsColorPreset.CUSTOM
            }

            VcsColorCategory.BLAME_GUTTER -> {
                if (section.pending.blamePreset == VcsColorPreset.CUSTOM) return
                section.update { it.copy(blamePreset = VcsColorPreset.CUSTOM) }
                blameCustomSelected.set(true)
                blamePresetSegmented?.selectedItem = VcsColorPreset.CUSTOM
            }

            else -> {}
        }
    }

    override fun isModified(): Boolean = section.isModified()

    override fun apply() {
        if (!isModified()) return
        if (!LicenseChecker.isLicensedOrGrace()) {
            LOG.info(
                "VcsColorPanel.apply: license no longer active; " +
                    "skipping VCS state persistence and re-apply",
            )
            return
        }

        try {
            section.commit { pending, _ ->
                // Resolve per-section preset choices into per-category intensities,
                // then materialise the snapshot. The applier reads only per-category
                // intensities — preset/Custom branching is fully resolved here.
                // Non-Custom presets defer to VcsColorPreset.intensityFor per
                // category (so future preset variants that differentiate intensity
                // across categories work correctly); Custom mode pulls the slider
                // value for that specific category.
                fun resolveCategory(
                    preset: VcsColorPreset,
                    category: VcsColorCategory,
                    customValue: Int,
                ): Int = if (preset == VcsColorPreset.CUSTOM) customValue else preset.intensityFor(category)

                val resolved =
                    mapOf(
                        VcsColorCategory.DIFF_VIEWER to
                            resolveCategory(pending.diffPreset, VcsColorCategory.DIFF_VIEWER, pending.diffIntensity),
                        VcsColorCategory.PROJECT_VIEW_FILE_STATUS to
                            resolveCategory(
                                pending.diffPreset,
                                VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
                                pending.projectViewIntensity,
                            ),
                        VcsColorCategory.EDITOR_GUTTER to
                            resolveCategory(
                                pending.diffPreset,
                                VcsColorCategory.EDITOR_GUTTER,
                                pending.gutterIntensity,
                            ),
                        VcsColorCategory.CONFLICT_MARKERS to
                            resolveCategory(
                                pending.mergePreset,
                                VcsColorCategory.CONFLICT_MARKERS,
                                pending.conflictMarkerIntensity,
                            ),
                        VcsColorCategory.BLAME_GUTTER to
                            resolveCategory(pending.blamePreset, VcsColorCategory.BLAME_GUTTER, pending.blameIntensity),
                    )
                val snapshot = VcsColorSnapshot(enabled = pending.enabled, perCategoryIntensities = resolved)

                // Run the applier FIRST so a throw leaves persisted state untouched
                // and the user can retry after fixing the cause.
                VcsColorContext.withSnapshot(snapshot) {
                    VcsColorApplier.applyAll()
                }
                val state = AyuIslandsSettings.getInstance().state
                state.vcsColorEnabled = pending.enabled
                state.vcsDiffPreset = pending.diffPreset.name
                state.vcsMergePreset = pending.mergePreset.name
                state.vcsBlamePreset = pending.blamePreset.name
                state.vcsDiffIntensity = pending.diffIntensity
                state.vcsProjectViewIntensity = pending.projectViewIntensity
                state.vcsGutterIntensity = pending.gutterIntensity
                state.vcsConflictMarkerIntensity = pending.conflictMarkerIntensity
                state.vcsBlameIntensity = pending.blameIntensity
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
        loadStored()
    }

    override fun reset() {
        loadStored()
        suppressSliderListeners = true
        try {
            val pending = section.pending
            enabledCheckbox?.isSelected = pending.enabled
            diffPresetSegmented?.selectedItem = pending.diffPreset
            mergePresetSegmented?.selectedItem = pending.mergePreset
            blamePresetSegmented?.selectedItem = pending.blamePreset
            masterEnabled.set(pending.enabled || !licensed)
            diffCustomSelected.set(pending.diffPreset == VcsColorPreset.CUSTOM)
            mergeCustomSelected.set(pending.mergePreset == VcsColorPreset.CUSTOM)
            blameCustomSelected.set(pending.blamePreset == VcsColorPreset.CUSTOM)
            diffSlider?.value = pending.diffIntensity
            diffValueLabel?.text = "${pending.diffIntensity}"
            projectViewSlider?.value = pending.projectViewIntensity
            projectViewValueLabel?.text = "${pending.projectViewIntensity}"
            gutterSlider?.value = pending.gutterIntensity
            gutterValueLabel?.text = "${pending.gutterIntensity}"
            conflictMarkerSlider?.value = pending.conflictMarkerIntensity
            conflictMarkerValueLabel?.text = "${pending.conflictMarkerIntensity}"
            blameSlider?.value = pending.blameIntensity
            blameValueLabel?.text = "${pending.blameIntensity}"
            vcsPreview?.updatePreview(variant ?: AyuVariant.DARK, previewIntensities())
        } finally {
            suppressSliderListeners = false
        }
    }

    private fun loadStored() {
        section.load()
        // Stored intensities mirror the raw persisted values; pending is clamped
        // into the slider-safe range so JSlider construction stays total even for
        // corrupted out-of-range XML. An out-of-range legacy value leaves
        // stored != pending on purpose — the panel opens with a visible Apply.
        section.update {
            it.copy(
                diffIntensity = it.diffIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY),
                projectViewIntensity = it.projectViewIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY),
                gutterIntensity = it.gutterIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY),
                conflictMarkerIntensity = it.conflictMarkerIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY),
                blameIntensity = it.blameIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY),
            )
        }
        masterEnabled.set(section.pending.enabled || !licensed)
        diffCustomSelected.set(section.pending.diffPreset == VcsColorPreset.CUSTOM)
        mergeCustomSelected.set(section.pending.mergePreset == VcsColorPreset.CUSTOM)
        blameCustomSelected.set(section.pending.blamePreset == VcsColorPreset.CUSTOM)
    }

    /**
     * Switching a section's preset writes the new preset into that section's
     * pending snapshot, then (for non-Custom presets) snaps every slider owned
     * by the section to the preset's canonical position. Custom mode leaves
     * sliders alone so the user can fine-tune from the last preset.
     */
    private fun onSectionPresetChosen(
        vcsSection: VcsSection,
        preset: VcsColorPreset,
    ) {
        section.update { it.withPreset(vcsSection, preset) }
        if (preset == VcsColorPreset.CUSTOM) return
        suppressSliderListeners = true
        try {
            for ((category, _) in vcsSection.sliders) {
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
                section.update { it.copy(diffIntensity = value) }
                diffSlider?.value = value
                diffValueLabel?.text = "$value"
            }

            VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> {
                section.update { it.copy(projectViewIntensity = value) }
                projectViewSlider?.value = value
                projectViewValueLabel?.text = "$value"
            }

            VcsColorCategory.EDITOR_GUTTER -> {
                section.update { it.copy(gutterIntensity = value) }
                gutterSlider?.value = value
                gutterValueLabel?.text = "$value"
            }

            VcsColorCategory.CONFLICT_MARKERS -> {
                section.update { it.copy(conflictMarkerIntensity = value) }
                conflictMarkerSlider?.value = value
                conflictMarkerValueLabel?.text = "$value"
            }

            VcsColorCategory.BLAME_GUTTER -> {
                section.update { it.copy(blameIntensity = value) }
                blameSlider?.value = value
                blameValueLabel?.text = "$value"
            }

            else -> {}
        }
        vcsPreview?.updatePreview(variant ?: AyuVariant.DARK, previewIntensities())
    }

    // ── @TestOnly seams ───────────────────────────────────────────────────────

    @TestOnly internal fun getPendingEnabledForTest(): Boolean = section.pending.enabled

    @TestOnly internal fun setPendingEnabledForTest(value: Boolean) {
        section.update { it.copy(enabled = value) }
    }

    @TestOnly internal fun getPendingPresetForTest(vcsSection: VcsSection): VcsColorPreset =
        section.pending.presetFor(
            vcsSection,
        )

    @TestOnly internal fun setPendingPresetForTest(
        vcsSection: VcsSection,
        preset: VcsColorPreset,
    ) {
        section.update { it.withPreset(vcsSection, preset) }
    }

    @TestOnly internal fun getPendingIntensityForTest(category: VcsColorCategory): Int =
        section.pending.intensityFor(
            category,
        )

    @TestOnly internal fun setPendingIntensityForTest(
        category: VcsColorCategory,
        value: Int,
    ) {
        section.update { it.withIntensity(category, value) }
    }

    @TestOnly internal fun triggerSectionPresetChosenForTest(
        vcsSection: VcsSection,
        preset: VcsColorPreset,
    ) {
        onSectionPresetChosen(vcsSection, preset)
    }

    companion object {
        private val LOG = logger<VcsColorPanel>()
        internal const val MIN_INTENSITY = 0
        internal const val MAX_INTENSITY = 100
        private const val INTENSITY_MAJOR_TICK = 25
        private const val INTENSITY_MINOR_TICK = 5
    }
}

/**
 * Selector for the three VCS panel sections. Encapsulates each section's
 * user-visible title and the list of per-category sliders it owns in Custom
 * mode.
 */
internal enum class VcsSection(
    val title: String,
    val sliders: List<Pair<VcsColorCategory, String>>,
) {
    DIFF(
        title = "Diff and File Status",
        sliders =
            listOf(
                VcsColorCategory.DIFF_VIEWER to "Diff viewer:",
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS to "Project View:",
                VcsColorCategory.EDITOR_GUTTER to "Editor gutter:",
            ),
    ),
    MERGE(
        title = "Merge and Conflict",
        sliders = listOf(VcsColorCategory.CONFLICT_MARKERS to "Conflict markers:"),
    ),
    BLAME(
        title = "Blame and History",
        sliders = listOf(VcsColorCategory.BLAME_GUTTER to "Blame gutter:"),
    ),
}
