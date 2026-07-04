package dev.ayuislands.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.selected
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
 * Settings panel rendering the "Chrome Tinting" collapsible group inside the Accent
 * tab. Follows the same shape as [AyuIslandsElementsPanel]:
 *
 *  - Pending/stored bookkeeping delegated to a [SettingsSection] over the immutable
 *    [ChromeTintSettings] snapshot so [isModified] can diff without touching the live
 *    [AyuIslandsState] until [apply] is called.
 *  - Premium gate: unlicensed users see the full settings surface, but controls
 *    are locked. The underlying apply path re-checks [LicenseChecker] so a
 *    mid-session license revoke cannot persist premium state.
 *  - Probe gate: the Main Toolbar row is present but disabled (never hidden) with
 *    a user-visible comment when [ChromeDecorationsProbe.isCustomHeaderActive]
 *    reports native OS chrome. The row stays in the panel so users understand why
 *    toolbar tinting is unavailable rather than silently disappearing. The
 *    disabled-state comment is per-OS honest: on IDE 2026.1+ JetBrains removed
 *    the "Merge main menu with window title" toggle and the corresponding
 *    custom-header registry entries, making custom title bar painting impossible
 *    on macOS — the comment explains this without pointing users at a setting
 *    that no longer exists.
 *  - On [apply], after the 6 chrome fields persist into [AyuIslandsState], the panel calls
 *    [AccentApplicator.applyForFocusedProject] so the 5 chrome AccentElement impls repaint
 *    immediately without requiring a second settings open.
 */
class AyuIslandsChromePanel : AyuIslandsSettingsPanel {
    // ── Pending / stored state ────────────────────────────────────────────────

    private data class ChromeTintSettings(
        val statusBar: Boolean = false,
        val mainToolbar: Boolean = false,
        val toolWindowStripe: Boolean = false,
        val navBar: Boolean = false,
        val panelBorder: Boolean = false,
        val intensity: Int = AyuIslandsState.DEFAULT_CHROME_TINT_INTENSITY,
    )

    private var variant: AyuVariant? = null
    private var licensed: Boolean = false

    private val section =
        SettingsSection(initial = ChromeTintSettings()) {
            val state = AyuIslandsSettings.getInstance().state
            // Stored intensity mirrors whatever AyuIslandsState holds (including legacy
            // out-of-range values 51-100 from sessions predating the cap) while the
            // user is licensed; unlicensed users get the clamped value so the locked
            // preview never opens dirty (free users cannot apply the clamp anyway).
            val clamped = state.chromeTintIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
            ChromeTintSettings(
                statusBar = state.chromeStatusBar,
                mainToolbar = state.chromeMainToolbar,
                toolWindowStripe = state.chromeToolWindowStripe,
                navBar = state.chromeNavBar,
                panelBorder = state.chromePanelBorder,
                intensity = if (licensed) state.chromeTintIntensity else clamped,
            )
        }

    // ── Swing references (kept for reset-time refresh + test seams) ───────────

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
        val gate =
            PremiumFeatureGate(
                featureName = "Chrome tinting",
                lockedDescription =
                    "Chrome tinting is a Pro feature. " +
                        "Preview the status bar, toolbar, stripe, navigation bar, and border controls here.",
                requestMessage = "Unlock chrome tinting",
                isUnlocked = licensed,
            )
        val state = AyuIslandsSettings.getInstance().state
        loadStored()
        val probeAllowsMainToolbar = ChromeDecorationsProbe.isCustomHeaderActive()

        val collapsible =
            panel.collapsibleGroup(GROUP_TITLE) {
                premiumFeatureNotice(gate)
                buildChromeSurfaceRows(gate, probeAllowsMainToolbar)
                row("Intensity (%):") {
                    val valueLabel = JLabel("${section.pending.intensity}")
                    val sliderCell =
                        slider(MIN_INTENSITY, MAX_INTENSITY, INTENSITY_MAJOR_TICK, INTENSITY_MINOR_TICK)
                            .bindValue(
                                { section.pending.intensity },
                                { newValue ->
                                    section.update { it.copy(intensity = newValue) }
                                    valueLabel.text = "$newValue"
                                },
                            ).applyToComponent {
                                value = section.pending.intensity
                                paintTicks = true
                                applyPremiumLock(gate)
                                addChangeListener {
                                    if (!gate.isUnlocked) return@addChangeListener
                                    section.update { it.copy(intensity = value) }
                                    valueLabel.text = "$value"
                                }
                            }
                    val slider = sliderCell.component
                    sliderCell
                        .resizableColumn()
                        .align(Align.FILL)
                        .onReset {
                            section.update {
                                it.copy(intensity = section.stored.intensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY))
                            }
                            slider.value = section.pending.intensity
                            valueLabel.text = "${section.pending.intensity}"
                        }.onIsModified {
                            section.pending.intensity != section.stored.intensity
                        }
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

    private fun Panel.buildChromeSurfaceRows(
        gate: PremiumFeatureGate,
        probeAllowsMainToolbar: Boolean,
    ) {
        val disabledToolbarComment =
            if (probeAllowsMainToolbar) {
                null
            } else {
                disabledMainToolbarComment()
            }
        mainToolbarComment = disabledToolbarComment

        listOf(
            ChromeSurfaceRow(
                label = "Status bar",
                currentValue = { section.pending.statusBar },
                storedValue = { section.stored.statusBar },
                updatePending = { checked -> section.update { it.copy(statusBar = checked) } },
                rememberComponent = { statusBarCheckbox = it },
            ),
            ChromeSurfaceRow(
                label = "Main toolbar",
                currentValue = { section.pending.mainToolbar },
                storedValue = { section.stored.mainToolbar },
                updatePending = { checked -> section.update { it.copy(mainToolbar = checked) } },
                rememberComponent = { mainToolbarCheckbox = it },
                enabledWhenUnlocked = probeAllowsMainToolbar,
                disabledComment = disabledToolbarComment,
            ),
            ChromeSurfaceRow(
                label = "Tool window stripe",
                currentValue = { section.pending.toolWindowStripe },
                storedValue = { section.stored.toolWindowStripe },
                updatePending = { checked -> section.update { it.copy(toolWindowStripe = checked) } },
                rememberComponent = { toolWindowStripeCheckbox = it },
            ),
            ChromeSurfaceRow(
                label = "Navigation bar",
                currentValue = { section.pending.navBar },
                storedValue = { section.stored.navBar },
                updatePending = { checked -> section.update { it.copy(navBar = checked) } },
                rememberComponent = { navBarCheckbox = it },
            ),
            ChromeSurfaceRow(
                label = "Panel borders",
                currentValue = { section.pending.panelBorder },
                storedValue = { section.stored.panelBorder },
                updatePending = { checked -> section.update { it.copy(panelBorder = checked) } },
                rememberComponent = { panelBorderCheckbox = it },
            ),
        ).forEach { surfaceRow ->
            row {
                val checkboxCell =
                    checkBox(surfaceRow.label)
                        .selected(surfaceRow.currentValue())
                val checkbox = checkboxCell.component
                checkbox.applyPremiumLock(gate, enabledWhenUnlocked = surfaceRow.enabledWhenUnlocked)
                checkboxCell
                    .onChanged {
                        if (!gate.isUnlocked || !surfaceRow.enabledWhenUnlocked) return@onChanged
                        surfaceRow.updatePending(checkbox.isSelected)
                    }.onApply {
                        if (gate.isUnlocked && surfaceRow.enabledWhenUnlocked) {
                            surfaceRow.updatePending(checkbox.isSelected)
                        }
                    }.onReset {
                        surfaceRow.updatePending(surfaceRow.storedValue())
                        checkbox.isSelected = surfaceRow.currentValue()
                    }.onIsModified {
                        surfaceRow.currentValue() != surfaceRow.storedValue()
                    }
                surfaceRow.rememberComponent(checkbox)
                surfaceRow.disabledComment?.let { checkboxCell.comment(it) }
            }
        }
    }

    private class ChromeSurfaceRow(
        val label: String,
        val currentValue: () -> Boolean,
        val storedValue: () -> Boolean,
        val updatePending: (Boolean) -> Unit,
        val rememberComponent: (JBCheckBox) -> Unit,
        val enabledWhenUnlocked: Boolean = true,
        val disabledComment: String? = null,
    )

    override fun isModified(): Boolean = section.isModified()

    override fun apply() {
        if (!isModified()) return
        // License may have flipped mid-session (grace expired, entitlement revoked,
        // user signed out of JBA). When the panel was built licensed but the gate
        // has since closed, refuse to persist — otherwise chrome state writes keep
        // happening behind locked premium UI.
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
        // persisting (silent skip would mask broken state).
        val resolvedVariant = variant ?: AyuVariant.detect()
        if (resolvedVariant == null) {
            LOG.info(
                "AyuIslandsChromePanel.apply: no Ayu variant resolvable — " +
                    "skipping chrome apply (theme may not be an Ayu variant)",
            )
            return
        }

        try {
            section.commit { pending, _ ->
                val chromeTintingEnabled =
                    pending.statusBar ||
                        pending.mainToolbar ||
                        pending.toolWindowStripe ||
                        pending.navBar ||
                        pending.panelBorder
                val chromeSnapshot =
                    ChromeTintSnapshot(
                        chromeStatusBar = pending.statusBar,
                        chromeMainToolbar = pending.mainToolbar,
                        chromeToolWindowStripe = pending.toolWindowStripe,
                        chromeNavBar = pending.navBar,
                        chromePanelBorder = pending.panelBorder,
                        intensity = TintIntensity.of(pending.intensity),
                        chromeTintingEnabled = chromeTintingEnabled,
                    )

                // Run the EP chain FIRST so a throw from applyForFocusedProject leaves
                // persisted state untouched and the user can retry after fixing the cause.
                // The applicator consumes the pending chrome snapshot above, so this ordering
                // no longer makes the visible tint one Apply behind the slider.
                ChromeTintContext.withSnapshot(chromeSnapshot) {
                    AccentApplicator.applyForFocusedProject(resolvedVariant)
                }
                val state = AyuIslandsSettings.getInstance().state
                state.chromeTintingEnabled = chromeTintingEnabled
                state.chromeStatusBar = pending.statusBar
                state.chromeMainToolbar = pending.mainToolbar
                state.chromeToolWindowStripe = pending.toolWindowStripe
                state.chromeNavBar = pending.navBar
                state.chromePanelBorder = pending.panelBorder
                state.chromeTintIntensity = chromeSnapshot.intensity.percent
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
        loadStored()
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
        loadStored()
        val pending = section.pending
        statusBarCheckbox?.isSelected = pending.statusBar
        mainToolbarCheckbox?.isSelected = pending.mainToolbar
        toolWindowStripeCheckbox?.isSelected = pending.toolWindowStripe
        navBarCheckbox?.isSelected = pending.navBar
        panelBorderCheckbox?.isSelected = pending.panelBorder
        intensitySlider?.value = pending.intensity
        intensityValueLabel?.text = "${pending.intensity}"
    }

    private fun loadStored() {
        section.load()
        // Stored intensity keeps the raw persisted value (see the snapshot lambda);
        // the pending value is clamped into [MIN_INTENSITY, MAX_INTENSITY] so the
        // slider can always display it. A legacy value outside that range leaves
        // stored != pending on purpose — isModified() reports true and the user sees
        // a one-time Apply button that persists the capped value back into state.
        //
        // coerceIn (not coerceAtMost) is load-bearing: a negative persisted value
        // (corrupted XML, older schema, third-party migration) would otherwise slip
        // through the cap and reach the JSlider constructor, which throws when
        // value < min. The lower bound keeps the slider construction total.
        section.update { it.copy(intensity = it.intensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)) }
    }

    /**
     * Per-OS honest comment for the disabled Main Toolbar row.
     *
     * IDE 2026.1+ removed the "Merge main menu with window title" toggle from Appearance
     * Settings AND the underlying custom-header registry entries. An earlier revision of
     * this panel offered an actionable link to Settings → Appearance, but the target
     * option no longer exists on macOS, so the link navigated users to a panel with
     * nothing relevant to toggle. Instead, explain honestly per platform what the
     * situation is so the user knows why MainToolbar tint is disabled.
     *
     * Reads the reason code from [ChromeDecorationsProbe.probe] instead of re-sampling
     * `SystemInfo` — keeps the probe as the single source of truth for "why chrome
     * tinting is unavailable".
     */
    private fun disabledMainToolbarComment(): String {
        val unsupported = ChromeDecorationsProbe.probe() as? ChromeSupport.Unsupported ?: return ""
        return when (unsupported) {
            ChromeSupport.Unsupported.NativeMacTitleBar -> {
                "Disabled: macOS paints the native title bar (IDE 2026.1+ no longer exposes a toggle)."
            }

            ChromeSupport.Unsupported.WindowsNoCustomHeader -> {
                "Disabled: your IDE is not using custom window decorations."
            }

            ChromeSupport.Unsupported.GnomeSsd -> {
                "Disabled: your window manager paints the native title bar."
            }

            ChromeSupport.Unsupported.UnknownOs -> {
                "Disabled: your OS paints the native title bar."
            }
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
    internal fun enabledSurfaceCheckboxCountForTest(): Int =
        listOfNotNull(
            statusBarCheckbox,
            mainToolbarCheckbox,
            toolWindowStripeCheckbox,
            navBarCheckbox,
            panelBorderCheckbox,
        ).count { it.isEnabled }

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
        section.update { it.copy(statusBar = value) }
    }

    @TestOnly
    internal fun setPendingChromeMainToolbarForTest(value: Boolean) {
        section.update { it.copy(mainToolbar = value) }
    }

    @TestOnly
    internal fun setPendingChromeToolWindowStripeForTest(value: Boolean) {
        section.update { it.copy(toolWindowStripe = value) }
    }

    @TestOnly
    internal fun setPendingChromeNavBarForTest(value: Boolean) {
        section.update { it.copy(navBar = value) }
    }

    @TestOnly
    internal fun setPendingChromePanelBorderForTest(value: Boolean) {
        section.update { it.copy(panelBorder = value) }
    }

    @TestOnly
    internal fun setPendingChromeTintIntensityForTest(value: Int) {
        section.update { it.copy(intensity = value) }
    }

    @TestOnly
    internal fun getPendingChromeStatusBarForTest(): Boolean = section.pending.statusBar

    @TestOnly
    internal fun getPendingChromeTintIntensityForTest(): Int = section.pending.intensity

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
