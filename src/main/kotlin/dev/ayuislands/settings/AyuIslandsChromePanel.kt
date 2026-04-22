package dev.ayuislands.settings

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ChromeDecorationsProbe
import dev.ayuislands.licensing.LicenseChecker
import org.jetbrains.annotations.TestOnly
import javax.swing.JLabel
import javax.swing.JSlider

/**
 * Settings panel rendering the "Chrome Tinting" collapsible group inside the Accent tab
 * (phase 40). Follows the same shape as [AyuIslandsElementsPanel]:
 *
 *  - Pending/stored pairs for each state field so [isModified] can diff without touching
 *    the live [AyuIslandsState] until [apply] is called.
 *  - Premium gate per decision D-10: unlicensed users see the collapsible header with a
 *    single "requires Pro license" comment instead of the full content. The underlying
 *    controls are not wired at all in the unlicensed path so there is no way to mutate
 *    chrome state without a license.
 *  - Probe gate per decision D-09 / requirement CHROME-02: the Main Toolbar row is
 *    present but disabled (never hidden) with a user-visible comment when
 *    [ChromeDecorationsProbe.isCustomHeaderActive] reports native OS chrome. The row
 *    stays in the panel so users understand why toolbar tinting is unavailable rather
 *    than silently disappearing. The disabled-state comment is per-OS honest: on
 *    IDE 2026.1+ JetBrains removed the "Merge main menu with window title" toggle and
 *    the corresponding custom-header registry entries, making custom title bar painting
 *    impossible on macOS — the comment explains this without pointing users at a setting
 *    that no longer exists.
 *  - On [apply], after the 8 chrome fields persist into [AyuIslandsState], the panel calls
 *    [AccentApplicator.applyForFocusedProject] so the 5 chrome AccentElement impls repaint
 *    immediately without requiring a second settings open.
 */
class AyuIslandsChromePanel : AyuIslandsSettingsPanel {
    // ── Pending / stored state ────────────────────────────────────────────────

    private var pendingChromeStatusBar: Boolean = false
    private var storedChromeStatusBar: Boolean = false
    private var pendingChromeMainToolbar: Boolean = false
    private var storedChromeMainToolbar: Boolean = false
    private var pendingChromeToolWindowStripe: Boolean = false
    private var storedChromeToolWindowStripe: Boolean = false
    private var pendingChromeNavBar: Boolean = false
    private var storedChromeNavBar: Boolean = false
    private var pendingChromePanelBorder: Boolean = false
    private var storedChromePanelBorder: Boolean = false
    private var pendingChromeTintIntensity: Int = AyuIslandsState.DEFAULT_CHROME_TINT_INTENSITY
    private var storedChromeTintIntensity: Int = AyuIslandsState.DEFAULT_CHROME_TINT_INTENSITY
    private var pendingChromeTintKeepForegroundReadable: Boolean = true
    private var storedChromeTintKeepForegroundReadable: Boolean = true

    // ── Swing references (kept for reset-time refresh + test seams) ───────────

    private var variant: AyuVariant? = null
    private var licensed: Boolean = false
    private var statusBarCheckbox: JBCheckBox? = null
    private var mainToolbarCheckbox: JBCheckBox? = null
    private var toolWindowStripeCheckbox: JBCheckBox? = null
    private var navBarCheckbox: JBCheckBox? = null
    private var panelBorderCheckbox: JBCheckBox? = null
    private var intensitySlider: JSlider? = null
    private var intensityValueLabel: JLabel? = null
    private var keepForegroundReadableCheckbox: JBCheckBox? = null
    private var mainToolbarComment: String? = null
    private var expandedListener: ((Boolean) -> Unit)? = null
    private var collapsibleExpanded: Boolean = false

    // ── Panel lifecycle ───────────────────────────────────────────────────────

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        this.variant = variant
        licensed = LicenseChecker.isLicensedOrGrace()
        val state = AyuIslandsSettings.getInstance().state
        loadStored(state)
        val probeAllowsMainToolbar = ChromeDecorationsProbe.isCustomHeaderActive()

        val collapsible =
            panel.collapsibleGroup(GROUP_TITLE) {
                if (!licensed) {
                    row { comment("Chrome tinting requires a Pro license.") }
                    return@collapsibleGroup
                }

                row {
                    val cb = checkBox("Status bar")
                    cb.component.isSelected = pendingChromeStatusBar
                    cb.component.addActionListener {
                        pendingChromeStatusBar = cb.component.isSelected
                    }
                    statusBarCheckbox = cb.component
                }
                row {
                    val cb = checkBox("Main toolbar")
                    cb.component.isSelected = pendingChromeMainToolbar
                    cb.component.isEnabled = probeAllowsMainToolbar
                    cb.component.addActionListener {
                        pendingChromeMainToolbar = cb.component.isSelected
                    }
                    mainToolbarCheckbox = cb.component
                    if (!probeAllowsMainToolbar) {
                        val commentText = disabledMainToolbarComment()
                        cb.comment(commentText)
                        mainToolbarComment = commentText
                    }
                }
                row {
                    val cb = checkBox("Tool window stripe")
                    cb.component.isSelected = pendingChromeToolWindowStripe
                    cb.component.addActionListener {
                        pendingChromeToolWindowStripe = cb.component.isSelected
                    }
                    toolWindowStripeCheckbox = cb.component
                }
                row {
                    val cb = checkBox("Navigation bar")
                    cb.component.isSelected = pendingChromeNavBar
                    cb.component.addActionListener {
                        pendingChromeNavBar = cb.component.isSelected
                    }
                    navBarCheckbox = cb.component
                }
                row {
                    val cb = checkBox("Panel borders")
                    cb.component.isSelected = pendingChromePanelBorder
                    cb.component.addActionListener {
                        pendingChromePanelBorder = cb.component.isSelected
                    }
                    panelBorderCheckbox = cb.component
                }
                row("Intensity (%):") {
                    val slider = JSlider(MIN_INTENSITY, MAX_INTENSITY, pendingChromeTintIntensity)
                    slider.paintTicks = true
                    slider.majorTickSpacing = INTENSITY_MAJOR_TICK
                    slider.minorTickSpacing = INTENSITY_MINOR_TICK
                    val valueLabel = JLabel("${slider.value}")
                    slider.addChangeListener {
                        pendingChromeTintIntensity = slider.value
                        valueLabel.text = "${slider.value}"
                    }
                    cell(slider).resizableColumn().align(Align.FILL)
                    cell(valueLabel)
                    intensitySlider = slider
                    intensityValueLabel = valueLabel
                }
                row {
                    val cb = checkBox("Keep foreground readable")
                    cb.component.isSelected = pendingChromeTintKeepForegroundReadable
                    cb.component.addActionListener {
                        pendingChromeTintKeepForegroundReadable = cb.component.isSelected
                    }
                    keepForegroundReadableCheckbox = cb.component
                }
            }

        collapsibleExpanded = state.chromeTintingGroupExpanded
        collapsible.expanded = collapsibleExpanded
        val listener: (Boolean) -> Unit = { expanded ->
            collapsibleExpanded = expanded
            AyuIslandsSettings.getInstance().state.chromeTintingGroupExpanded = expanded
        }
        expandedListener = listener
        collapsible.addExpandedListener { expanded -> listener(expanded) }
    }

    override fun isModified(): Boolean =
        pendingChromeStatusBar != storedChromeStatusBar ||
            pendingChromeMainToolbar != storedChromeMainToolbar ||
            pendingChromeToolWindowStripe != storedChromeToolWindowStripe ||
            pendingChromeNavBar != storedChromeNavBar ||
            pendingChromePanelBorder != storedChromePanelBorder ||
            pendingChromeTintIntensity != storedChromeTintIntensity ||
            pendingChromeTintKeepForegroundReadable != storedChromeTintKeepForegroundReadable

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = pendingChromeStatusBar
        state.chromeMainToolbar = pendingChromeMainToolbar
        state.chromeToolWindowStripe = pendingChromeToolWindowStripe
        state.chromeNavBar = pendingChromeNavBar
        state.chromePanelBorder = pendingChromePanelBorder
        state.chromeTintIntensity = pendingChromeTintIntensity
        state.chromeTintKeepForegroundReadable = pendingChromeTintKeepForegroundReadable
        loadStored(state)

        // Re-run the EP chain so the 5 chrome AccentElement impls repaint now —
        // mirrors AyuIslandsElementsPanel.apply, which also routes through
        // applyForFocusedProject so per-project / per-language overrides are not
        // stomped by the accent applied earlier in the Configurable.apply cycle.
        val currentVariant = variant ?: return
        AccentApplicator.applyForFocusedProject(currentVariant)
    }

    override fun reset() {
        val state = AyuIslandsSettings.getInstance().state
        loadStored(state)
        statusBarCheckbox?.isSelected = pendingChromeStatusBar
        mainToolbarCheckbox?.isSelected = pendingChromeMainToolbar
        toolWindowStripeCheckbox?.isSelected = pendingChromeToolWindowStripe
        navBarCheckbox?.isSelected = pendingChromeNavBar
        panelBorderCheckbox?.isSelected = pendingChromePanelBorder
        intensitySlider?.value = pendingChromeTintIntensity
        intensityValueLabel?.text = "$pendingChromeTintIntensity"
        keepForegroundReadableCheckbox?.isSelected = pendingChromeTintKeepForegroundReadable
    }

    private fun loadStored(state: AyuIslandsState) {
        storedChromeStatusBar = state.chromeStatusBar
        pendingChromeStatusBar = storedChromeStatusBar
        storedChromeMainToolbar = state.chromeMainToolbar
        pendingChromeMainToolbar = storedChromeMainToolbar
        storedChromeToolWindowStripe = state.chromeToolWindowStripe
        pendingChromeToolWindowStripe = storedChromeToolWindowStripe
        storedChromeNavBar = state.chromeNavBar
        pendingChromeNavBar = storedChromeNavBar
        storedChromePanelBorder = state.chromePanelBorder
        pendingChromePanelBorder = storedChromePanelBorder
        storedChromeTintIntensity = state.chromeTintIntensity
        pendingChromeTintIntensity = storedChromeTintIntensity
        storedChromeTintKeepForegroundReadable = state.chromeTintKeepForegroundReadable
        pendingChromeTintKeepForegroundReadable = storedChromeTintKeepForegroundReadable
    }

    /**
     * Per-OS honest comment for the disabled Main Toolbar row (CHROME-02).
     *
     * IDE 2026.1+ removed the "Merge main menu with window title" toggle from Appearance
     * Settings AND the underlying custom-header registry entries. An earlier revision of
     * this panel offered an actionable link to Settings → Appearance, but the target
     * option no longer exists on macOS, so the link navigated users to a panel with
     * nothing relevant to toggle. Instead, explain honestly per platform what the
     * situation is so the user knows why MainToolbar tint is disabled.
     */
    private fun disabledMainToolbarComment(): String =
        when {
            SystemInfo.isMac ->
                "Disabled: macOS paints the native title bar (IDE 2026.1+ no longer exposes a toggle)."
            SystemInfo.isWindows ->
                "Disabled: your IDE is not using custom window decorations."
            SystemInfo.isLinux ->
                "Disabled: your window manager paints the native title bar."
            else ->
                "Disabled: your OS paints the native title bar."
        }

    // ── @TestOnly seams ───────────────────────────────────────────────────────
    //
    // Project convention (see OverridesGroupBuilderProportionsTest) is to test UI
    // panels through deterministic test hooks rather than traversing a live
    // DialogPanel component tree — no BasePlatformTestCase harness, no Swing
    // runtime dependency. Every accessor below is annotated @TestOnly so the IDE
    // inspection surfaces accidental production calls.

    @TestOnly
    internal fun collapsibleRenderedLicensedForTest(): Boolean = licensed

    @TestOnly
    internal fun surfaceCheckboxCountForTest(): Int =
        listOfNotNull(
            statusBarCheckbox,
            mainToolbarCheckbox,
            toolWindowStripeCheckbox,
            navBarCheckbox,
            panelBorderCheckbox,
        ).size

    @TestOnly
    internal fun intensitySliderForTest(): JSlider? = intensitySlider

    @TestOnly
    internal fun keepForegroundReadableCheckboxForTest(): JBCheckBox? = keepForegroundReadableCheckbox

    @TestOnly
    internal fun mainToolbarRowEnabledForTest(): Boolean = mainToolbarCheckbox?.isEnabled ?: false

    @TestOnly
    internal fun mainToolbarRowCommentForTest(): String? = mainToolbarComment

    @TestOnly
    internal fun collapsibleExpandedForTest(): Boolean = collapsibleExpanded

    @TestOnly
    internal fun triggerCollapsibleExpandedForTest(expanded: Boolean) {
        expandedListener?.invoke(expanded)
    }

    @TestOnly
    internal fun setPendingChromeStatusBarForTest(value: Boolean) {
        pendingChromeStatusBar = value
    }

    @TestOnly
    internal fun setPendingChromeMainToolbarForTest(value: Boolean) {
        pendingChromeMainToolbar = value
    }

    @TestOnly
    internal fun setPendingChromeToolWindowStripeForTest(value: Boolean) {
        pendingChromeToolWindowStripe = value
    }

    @TestOnly
    internal fun setPendingChromeNavBarForTest(value: Boolean) {
        pendingChromeNavBar = value
    }

    @TestOnly
    internal fun setPendingChromePanelBorderForTest(value: Boolean) {
        pendingChromePanelBorder = value
    }

    @TestOnly
    internal fun setPendingChromeTintIntensityForTest(value: Int) {
        pendingChromeTintIntensity = value
    }

    @TestOnly
    internal fun setPendingChromeTintKeepForegroundReadableForTest(value: Boolean) {
        pendingChromeTintKeepForegroundReadable = value
    }

    @TestOnly
    internal fun getPendingChromeStatusBarForTest(): Boolean = pendingChromeStatusBar

    @TestOnly
    internal fun getPendingChromeTintIntensityForTest(): Int = pendingChromeTintIntensity

    @TestOnly
    internal fun getPendingChromeTintKeepForegroundReadableForTest(): Boolean = pendingChromeTintKeepForegroundReadable

    companion object {
        private const val GROUP_TITLE = "Chrome Tinting"
        private const val MIN_INTENSITY = 10
        private const val MAX_INTENSITY = 100
        private const val INTENSITY_MAJOR_TICK = 20
        private const val INTENSITY_MINOR_TICK = 10
    }
}
