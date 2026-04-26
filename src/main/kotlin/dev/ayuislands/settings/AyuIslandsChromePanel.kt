package dev.ayuislands.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ChromeDecorationsProbe
import dev.ayuislands.accent.ChromeSupport
import dev.ayuislands.accent.ChromeTintContext
import dev.ayuislands.accent.ChromeTintSnapshot
import dev.ayuislands.accent.TintIntensity
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
 *  - On [apply], after the 6 chrome fields persist into [AyuIslandsState], the panel calls
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
            pendingChromeTintIntensity != storedChromeTintIntensity

    override fun apply() {
        if (!isModified()) return
        // License may have flipped mid-session (grace expired, entitlement revoked,
        // user signed out of JBA). When the panel was built licensed but the gate
        // has since closed, refuse to persist — otherwise chrome state writes keep
        // happening behind a "requires Pro" UI the user can no longer interact with.
        if (!LicenseChecker.isLicensedOrGrace()) {
            LOG.info(
                "AyuIslandsChromePanel.apply: license no longer active; " +
                    "skipping chrome state persistence and refocus re-apply",
            )
            return
        }
        // Re-detect the variant at apply time so a LAF switch between buildPanel and
        // apply (user flipped themes inside the same Settings session) doesn't drop
        // the re-apply — the stored variant reference can go stale. If detection
        // still returns null (no Ayu theme active), log at INFO and bail without
        // persisting: H-1 from the Phase 40.2 audit forbade the earlier silent skip.
        val resolvedVariant = variant ?: AyuVariant.detect()
        if (resolvedVariant == null) {
            LOG.info(
                "AyuIslandsChromePanel.apply: no Ayu variant resolvable — " +
                    "skipping chrome apply (theme may not be an Ayu variant)",
            )
            return
        }

        val chromeSnapshot =
            ChromeTintSnapshot(
                chromeStatusBar = pendingChromeStatusBar,
                chromeMainToolbar = pendingChromeMainToolbar,
                chromeToolWindowStripe = pendingChromeToolWindowStripe,
                chromeNavBar = pendingChromeNavBar,
                chromePanelBorder = pendingChromePanelBorder,
                intensity = TintIntensity.of(pendingChromeTintIntensity),
            )

        // H-4: run the EP chain FIRST so a throw from applyForFocusedProject leaves
        // persisted state untouched and the user can retry after fixing the cause.
        // The applicator consumes the pending chrome snapshot above, so this ordering
        // no longer makes the visible tint one Apply behind the slider.
        try {
            ChromeTintContext.withSnapshot(chromeSnapshot) {
                AccentApplicator.applyForFocusedProject(resolvedVariant)
            }
        } catch (exception: RuntimeException) {
            LOG.warn(
                "AyuIslandsChromePanel.apply: chrome re-apply threw; " +
                    "leaving pending chrome state uncommitted so the user can retry",
                exception,
            )
            notifyApplyFailed()
            return
        }
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = pendingChromeStatusBar
        state.chromeMainToolbar = pendingChromeMainToolbar
        state.chromeToolWindowStripe = pendingChromeToolWindowStripe
        state.chromeNavBar = pendingChromeNavBar
        state.chromePanelBorder = pendingChromePanelBorder
        state.chromeTintIntensity = chromeSnapshot.intensity.percent
        loadStored(state)
    }

    /**
     * Surface a balloon when the chrome re-apply fails so the user is not left
     * staring at an unchanged Settings dialog wondering whether their click
     * registered. The balloon points at idea.log for the actual stack — the
     * `LOG.warn` at the catch site carries the original throwable there.
     * Notification dispatch is itself wrapped in a try/catch so a
     * notification-subsystem hiccup (rare, but possible in shutdown races)
     * cannot mask the real apply failure.
     */
    private fun notifyApplyFailed() {
        try {
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup("Ayu Islands")
                .createNotification(
                    "Chrome tint could not be applied",
                    "Settings were not saved — see idea.log for details. " +
                        "Adjust any value in the Chrome Tinting panel and click Apply again to retry.",
                    NotificationType.WARNING,
                ).notify(null)
        } catch (notificationException: RuntimeException) {
            LOG.warn(
                "AyuIslandsChromePanel.apply: failed to surface chrome-apply error balloon " +
                    "(original cause logged above)",
                notificationException,
            )
        }
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
        // Stored intensity mirrors whatever AyuIslandsState holds (including legacy
        // out-of-range values 51-100 from sessions predating the cap); the pending
        // value is clamped into [MIN_INTENSITY, MAX_INTENSITY] so the slider can
        // always display it. A legacy value outside that range leaves
        // stored != pending on purpose — isModified() reports true and the user sees
        // a one-time Apply button that persists the capped value back into state.
        //
        // coerceIn (not coerceAtMost) is load-bearing: a negative persisted value
        // (corrupted XML, older schema, third-party migration) would otherwise slip
        // through the cap and reach the JSlider constructor, which throws when
        // value < min. The lower bound keeps the slider construction total.
        storedChromeTintIntensity = state.chromeTintIntensity
        pendingChromeTintIntensity = state.chromeTintIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
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
     *
     * Phase 40.3c Refactor 3: reads the reason code from [ChromeDecorationsProbe.probe]
     * instead of re-sampling `SystemInfo` — keeps the probe as the single source of
     * truth for "why chrome tinting is unavailable".
     */
    private fun disabledMainToolbarComment(): String {
        val unsupported = ChromeDecorationsProbe.probe() as? ChromeSupport.Unsupported ?: return ""
        return when (unsupported) {
            ChromeSupport.Unsupported.NativeMacTitleBar ->
                "Disabled: macOS paints the native title bar (IDE 2026.1+ no longer exposes a toggle)."
            ChromeSupport.Unsupported.WindowsNoCustomHeader ->
                "Disabled: your IDE is not using custom window decorations."
            ChromeSupport.Unsupported.GnomeSsd ->
                "Disabled: your window manager paints the native title bar."
            ChromeSupport.Unsupported.UnknownOs ->
                "Disabled: your OS paints the native title bar."
        }
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
    internal fun getPendingChromeStatusBarForTest(): Boolean = pendingChromeStatusBar

    @TestOnly
    internal fun getPendingChromeTintIntensityForTest(): Int = pendingChromeTintIntensity

    companion object {
        private val LOG = logger<AyuIslandsChromePanel>()
        private const val GROUP_TITLE = "Chrome Tinting"
        private const val MIN_INTENSITY = 10

        /**
         * User-facing cap for the chrome tint intensity slider.
         *
         * Saturation values above ~50 drive the tinted background so close to the raw
         * accent that even the always-on WCAG foreground pick (see
         * `dev.ayuislands.accent.WcagForeground`) cannot restore readable contrast on
         * warm/pastel accents — the text ends up washed out against an almost-solid-accent
         * chrome surface. Capping the slider at 50 keeps the usable range inside the
         * readable band.
         *
         * `dev.ayuislands.accent.ChromeTintBlender` still accepts intensities up to 100
         * internally — that's a math-safety clamp for the blend formula and intentionally
         * unaffected by the user-visible cap here.
         *
         * Users returning from older sessions with a persisted
         * [AyuIslandsState.chromeTintIntensity] above this cap see the slider snap to
         * 50 on panel load (via [loadStored]) and a visible Apply button so the capped
         * value can be persisted back on their next interaction.
         */
        internal const val MAX_INTENSITY = 50
        private const val INTENSITY_MAJOR_TICK = 10
        private const val INTENSITY_MINOR_TICK = 5
    }
}
