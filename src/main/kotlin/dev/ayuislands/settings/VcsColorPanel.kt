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
 * Phase 40.2 — settings panel for VCS color customization.
 *
 * Mirrors the [AyuIslandsChromePanel] structure:
 *  - Pending / stored pairs per field so [isModified] can diff without
 *    touching the live [AyuIslandsState] until [apply] is called.
 *  - Premium gate per memory `feedback_monetization`: unlicensed users see
 *    the panel title plus a single "requires Pro license" comment with a
 *    Pro upgrade link; no controls render in the unlicensed path so there's
 *    no way to mutate VCS state without a license.
 *  - On [apply] the panel wraps the applier call in
 *    [VcsColorContext.withSnapshot] so the EP runs against the pending
 *    snapshot; if the apply throws, persisted state stays untouched and the
 *    user can retry. The pattern matches [AyuIslandsChromePanel.apply].
 */
class VcsColorPanel : AyuIslandsSettingsPanel {
    // ── Pending / stored state ────────────────────────────────────────────────

    private var pendingEnabled: Boolean = false
    private var storedEnabled: Boolean = false
    private var pendingPreset: VcsColorPreset = VcsColorPreset.AMBIENT
    private var storedPreset: VcsColorPreset = VcsColorPreset.AMBIENT
    private var pendingDiffIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var storedDiffIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var pendingProjectViewIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var storedProjectViewIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var pendingGutterIntensity: Int = VcsColorPreset.AMBIENT_SLIDER
    private var storedGutterIntensity: Int = VcsColorPreset.AMBIENT_SLIDER

    // ── Swing references (kept for reset-time refresh + test seams) ───────────

    private var variant: AyuVariant? = null
    private var licensed: Boolean = false
    private var enabledCheckbox: JBCheckBox? = null
    private var presetSegmented: SegmentedButton<VcsColorPreset>? = null
    private var diffSlider: JSlider? = null
    private var projectViewSlider: JSlider? = null
    private var gutterSlider: JSlider? = null
    private var diffValueLabel: JLabel? = null
    private var projectViewValueLabel: JLabel? = null
    private var gutterValueLabel: JLabel? = null

    /** Drives `visibleIf(customSelected)` on the per-category slider rows. */
    private val customSelected = AtomicBooleanProperty(pendingPreset == VcsColorPreset.CUSTOM)

    /** Drives `visibleIf(masterEnabled)` so the preset + sliders hide when master is off. */
    private val masterEnabled = AtomicBooleanProperty(pendingEnabled)

    /**
     * Tracks whether the panel is mid-listener-suppress (preset switch or
     * reset). Prevents the slider's `addChangeListener` from feeding back
     * into `pendingPreset = CUSTOM` when the preset cycle moves the sliders
     * programmatically.
     */
    private var suppressSliderListeners: Boolean = false

    // ── Panel lifecycle ───────────────────────────────────────────────────────

    @Suppress("UnstableApiUsage")
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

    @Suppress("UnstableApiUsage")
    private fun Panel.buildLicensedContent(state: AyuIslandsState) {
        buildMasterToggleRow()
        buildPresetRow()
        buildDiffCollapsibleGroup(state)
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

    @Suppress("UnstableApiUsage")
    private fun Panel.buildPresetRow() {
        val presetRow =
            row("Preset:") {
                val segmented = segmentedButton(VcsColorPreset.entries) { preset -> text = preset.displayName }
                segmented.maxButtonsCount(VcsColorPreset.entries.size)
                segmented.selectedItem = pendingPreset
                segmented.whenItemSelected { preset -> onPresetChosen(preset) }
                presetSegmented = segmented
            }
        presetRow.visibleIf(masterEnabled)
    }

    private fun Panel.buildDiffCollapsibleGroup(state: AyuIslandsState) {
        val collapsible =
            collapsibleGroup(DIFF_GROUP_TITLE) {
                buildPerCategorySliderRow(VcsColorCategory.DIFF_VIEWER, "Diff viewer:")
                buildPerCategorySliderRow(VcsColorCategory.PROJECT_VIEW_FILE_STATUS, "Project View:")
                buildPerCategorySliderRow(VcsColorCategory.EDITOR_GUTTER, "Editor gutter:")
            }
        collapsible.expanded = state.vcsDiffSectionExpanded
        collapsible.addExpandedListener { expanded ->
            AyuIslandsSettings.getInstance().state.vcsDiffSectionExpanded = expanded
        }
    }

    private fun Panel.buildPerCategorySliderRow(
        category: VcsColorCategory,
        label: String,
    ) {
        val initialValue = pendingFor(category)
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
                attachSliderHandles(category, slider, valueLabel)
            }
        sliderRow.visibleIf(customSelected)
    }

    private fun pendingFor(category: VcsColorCategory): Int =
        when (category) {
            VcsColorCategory.DIFF_VIEWER -> pendingDiffIntensity
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> pendingProjectViewIntensity
            VcsColorCategory.EDITOR_GUTTER -> pendingGutterIntensity
            else -> VcsColorPreset.AMBIENT_SLIDER
        }

    private fun attachSliderHandles(
        category: VcsColorCategory,
        slider: JSlider,
        valueLabel: JLabel,
    ) {
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
            else -> Unit
        }
    }

    /**
     * Handles a slider value change for [category]. Writes the new value into
     * the matching `pending*` field, refreshes the value label, and (if the
     * change is a user gesture rather than programmatic) promotes the preset
     * to [VcsColorPreset.CUSTOM] so the manual slider position actually takes
     * effect on apply.
     */
    private fun onSliderChanged(
        category: VcsColorCategory,
        value: Int,
    ) {
        when (category) {
            VcsColorCategory.DIFF_VIEWER -> {
                pendingDiffIntensity = value
                diffValueLabel?.text = "$value"
            }
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> {
                pendingProjectViewIntensity = value
                projectViewValueLabel?.text = "$value"
            }
            VcsColorCategory.EDITOR_GUTTER -> {
                pendingGutterIntensity = value
                gutterValueLabel?.text = "$value"
            }
            else -> return
        }
        if (suppressSliderListeners || pendingPreset == VcsColorPreset.CUSTOM) return
        pendingPreset = VcsColorPreset.CUSTOM
        customSelected.set(true)
        presetSegmented?.selectedItem = VcsColorPreset.CUSTOM
    }

    override fun isModified(): Boolean =
        pendingEnabled != storedEnabled ||
            pendingPreset != storedPreset ||
            pendingDiffIntensity != storedDiffIntensity ||
            pendingProjectViewIntensity != storedProjectViewIntensity ||
            pendingGutterIntensity != storedGutterIntensity

    override fun apply() {
        if (!isModified()) return
        // License revalidation: a Pro user whose license expired mid-session must
        // not be allowed to keep mutating VCS state behind a panel that's about to
        // revert to the placeholder comment.
        if (!LicenseChecker.isLicensedOrGrace()) {
            LOG.info(
                "VcsColorPanel.apply: license no longer active; " +
                    "skipping VCS state persistence and re-apply",
            )
            return
        }

        val snapshot =
            VcsColorSnapshot(
                enabled = pendingEnabled,
                preset = pendingPreset,
                perCategoryIntensities =
                    mapOf(
                        VcsColorCategory.DIFF_VIEWER to pendingDiffIntensity,
                        VcsColorCategory.PROJECT_VIEW_FILE_STATUS to pendingProjectViewIntensity,
                        VcsColorCategory.EDITOR_GUTTER to pendingGutterIntensity,
                    ),
            )

        // H-4 (chrome pattern): run the applier first so a throw leaves the
        // persisted state untouched and the user can retry after fixing the cause.
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
            notifyApplyFailed()
            return
        }
        val state = AyuIslandsSettings.getInstance().state
        state.vcsColorEnabled = pendingEnabled
        state.vcsColorPreset = pendingPreset.name
        state.vcsDiffIntensity = pendingDiffIntensity
        state.vcsProjectViewIntensity = pendingProjectViewIntensity
        state.vcsGutterIntensity = pendingGutterIntensity
        loadStored(state)
    }

    override fun reset() {
        val state = AyuIslandsSettings.getInstance().state
        loadStored(state)
        suppressSliderListeners = true
        try {
            enabledCheckbox?.isSelected = pendingEnabled
            presetSegmented?.selectedItem = pendingPreset
            masterEnabled.set(pendingEnabled)
            customSelected.set(pendingPreset == VcsColorPreset.CUSTOM)
            diffSlider?.value = pendingDiffIntensity
            diffValueLabel?.text = "$pendingDiffIntensity"
            projectViewSlider?.value = pendingProjectViewIntensity
            projectViewValueLabel?.text = "$pendingProjectViewIntensity"
            gutterSlider?.value = pendingGutterIntensity
            gutterValueLabel?.text = "$pendingGutterIntensity"
        } finally {
            suppressSliderListeners = false
        }
    }

    private fun loadStored(state: AyuIslandsState) {
        storedEnabled = state.vcsColorEnabled
        pendingEnabled = storedEnabled
        storedPreset = state.effectiveVcsColorPreset()
        pendingPreset = storedPreset
        storedDiffIntensity = state.vcsDiffIntensity
        pendingDiffIntensity = state.vcsDiffIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        storedProjectViewIntensity = state.vcsProjectViewIntensity
        pendingProjectViewIntensity = state.vcsProjectViewIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        storedGutterIntensity = state.vcsGutterIntensity
        pendingGutterIntensity = state.vcsGutterIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        masterEnabled.set(pendingEnabled)
        customSelected.set(pendingPreset == VcsColorPreset.CUSTOM)
    }

    /**
     * Switching presets (non-Custom) snaps every per-category slider to that
     * preset's canonical position so the visible slider state always reflects
     * what's being applied. Custom mode leaves sliders at their previous values
     * so the user can fine-tune from the last preset they used.
     */
    private fun onPresetChosen(preset: VcsColorPreset) {
        pendingPreset = preset
        customSelected.set(preset == VcsColorPreset.CUSTOM)
        if (preset == VcsColorPreset.CUSTOM) return
        val snapPosition = preset.intensityFor(VcsColorCategory.DIFF_VIEWER)
        suppressSliderListeners = true
        try {
            pendingDiffIntensity = snapPosition
            diffSlider?.value = snapPosition
            diffValueLabel?.text = "$snapPosition"
            pendingProjectViewIntensity = snapPosition
            projectViewSlider?.value = snapPosition
            projectViewValueLabel?.text = "$snapPosition"
            pendingGutterIntensity = snapPosition
            gutterSlider?.value = snapPosition
            gutterValueLabel?.text = "$snapPosition"
        } finally {
            suppressSliderListeners = false
        }
    }

    private fun notifyApplyFailed() {
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
            LOG.warn(
                "VcsColorPanel.apply: failed to surface VCS-apply error balloon",
                notificationException,
            )
        }
    }

    // ── @TestOnly seams ───────────────────────────────────────────────────────

    @TestOnly internal fun licensedForTest(): Boolean = licensed

    @TestOnly internal fun getPendingEnabledForTest(): Boolean = pendingEnabled

    @TestOnly internal fun setPendingEnabledForTest(value: Boolean) {
        pendingEnabled = value
    }

    @TestOnly internal fun getPendingPresetForTest(): VcsColorPreset = pendingPreset

    @TestOnly internal fun setPendingPresetForTest(value: VcsColorPreset) {
        pendingPreset = value
    }

    @TestOnly internal fun getPendingIntensityForTest(category: VcsColorCategory): Int = pendingFor(category)

    @TestOnly internal fun setPendingIntensityForTest(
        category: VcsColorCategory,
        value: Int,
    ) {
        when (category) {
            VcsColorCategory.DIFF_VIEWER -> pendingDiffIntensity = value
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> pendingProjectViewIntensity = value
            VcsColorCategory.EDITOR_GUTTER -> pendingGutterIntensity = value
            else -> Unit
        }
    }

    @TestOnly internal fun triggerPresetChosenForTest(preset: VcsColorPreset) {
        onPresetChosen(preset)
    }

    companion object {
        private val LOG = logger<VcsColorPanel>()
        private const val DIFF_GROUP_TITLE = "Diff & File Status"
        internal const val MIN_INTENSITY = 0
        internal const val MAX_INTENSITY = 100
        private const val INTENSITY_MAJOR_TICK = 25
        private const val INTENSITY_MINOR_TICK = 5
    }
}
