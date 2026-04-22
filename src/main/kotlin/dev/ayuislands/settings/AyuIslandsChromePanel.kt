package dev.ayuislands.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ChromeDecorationsProbe
import dev.ayuislands.licensing.LicenseChecker
import org.jetbrains.annotations.TestOnly
import java.awt.Component
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
 *    than silently disappearing.
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

    // Plan 40-11 (VERIFICATION Gap 3): tracks whether the actionable "Enable merged menu
    // to tint title bar" link row was rendered under the disabled Main Toolbar row.
    // Shown only on macOS when ChromeDecorationsProbe.canEnableCustomHeaderOnMac() is true.
    private var mergedMenuOfferVisible: Boolean = false

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
        // Plan 40-11 (VERIFICATION Gap 3): on macOS with native chrome, offer the user a
        // direct link to Settings → Appearance where they can toggle "Merge main menu
        // with window title". The probe helper forces this to `false` on non-macOS and
        // on macOS when the custom header is already active.
        val canOfferMergedMenu = ChromeDecorationsProbe.canEnableCustomHeaderOnMac()

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
                        cb.comment(MAIN_TOOLBAR_NATIVE_CHROME_COMMENT)
                        mainToolbarComment = MAIN_TOOLBAR_NATIVE_CHROME_COMMENT
                    }
                }
                // Plan 40-11 (VERIFICATION Gap 3): actionable link under the disabled
                // Main Toolbar row on macOS only, and only when merged menu is OFF. The
                // comment on the row above explains WHY the row is disabled; this link
                // offers HOW TO fix it — one click opens IntelliJ's native Appearance
                // settings panel where the user toggles the real preference and the IDE
                // surfaces its own restart-required prompt (standard JetBrains UX).
                if (canOfferMergedMenu) {
                    row {
                        // Two-stage navigation (plan 40-11 hot-fix):
                        //
                        //  Stage 1 — in-dialog: this link lives INSIDE the Settings dialog,
                        //  so ShowSettingsUtil.showSettingsDialog(project, id) against an
                        //  already-open Settings window only blinks the dialog without
                        //  switching the left sidebar. Instead, resolve the open Settings
                        //  host via Settings.KEY.getData(dataContext) and call select(...)
                        //  on the found configurable.
                        //
                        //  Stage 2 — fallback: when the link is invoked from a context that
                        //  does NOT resolve a Settings host (hypothetical external invocation
                        //  or missing DataContext), fall back to the original
                        //  ShowSettingsUtil.showSettingsDialog entry-point.
                        //
                        // javap-verified against 2025.1 platform JARs (app-client.jar):
                        //   Settings.KEY               : DataKey<Settings>
                        //   Settings.find(String)      : Configurable
                        //   Settings.select(Configurable) : ActionCallback
                        //   DataManager.getInstance()  : DataManager
                        //   DataManager.getDataContext(Component) : DataContext
                        //
                        // B-1 regression guard: no Registry.setValue, no ApplicationManager.restart
                        // — IntelliJ surfaces the restart-required prompt natively.
                        link(MERGED_MENU_OFFER_LABEL) { event ->
                            val sourceComponent = event.source as? Component
                            if (sourceComponent != null) {
                                val dataContext = DataManager.getInstance().getDataContext(sourceComponent)
                                val settings = Settings.KEY.getData(dataContext)
                                if (settings != null) {
                                    val configurable = settings.find(MERGED_MENU_CONFIGURABLE_ID)
                                    if (configurable != null) {
                                        settings.select(configurable)
                                        return@link
                                    }
                                }
                            }
                            val project = ProjectManager.getInstance().openProjects.firstOrNull()
                            ShowSettingsUtil
                                .getInstance()
                                .showSettingsDialog(project, MERGED_MENU_CONFIGURABLE_ID)
                        }
                    }
                    mergedMenuOfferVisible = true
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

    /**
     * Plan 40-11 (VERIFICATION Gap 3): state-introspection seam for the merged-menu
     * offer link. NOTE: the CLICK path is NOT exposed via a `@TestOnly` trigger — L-4 in
     * [AyuIslandsChromePanelTest] walks the rendered component tree to locate the
     * [com.intellij.ui.components.ActionLink] by label and invokes its action directly
     * (W-4 remediation). If the DSL `link(...)` wiring breaks, L-4 fails.
     */
    @TestOnly
    internal fun mergedMenuOfferVisibleForTest(): Boolean = mergedMenuOfferVisible

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
        private const val MAIN_TOOLBAR_NATIVE_CHROME_COMMENT =
            "Disabled: your OS paints the native title bar"

        // Plan 40-11 / VERIFICATION Gap 3 constants. Label intentionally mirrors the
        // CHROME-02 requirement's hint phrasing "Enable 'Merge main menu with window title'"
        // — user-facing action-wording, not raw key names.
        private const val MERGED_MENU_OFFER_LABEL = "Enable merged menu to tint title bar"

        // javap-verified against platformVersion 2025.1 platform JARs (app-client.jar;
        // META-INF/PlatformExtensions.xml: id="preferences.lookFeel" key="title.appearance").
        // Do NOT change this id without re-running the Task 2 verification recipe.
        private const val MERGED_MENU_CONFIGURABLE_ID = "preferences.lookFeel"
    }
}
