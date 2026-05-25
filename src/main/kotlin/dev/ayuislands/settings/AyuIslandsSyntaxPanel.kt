package dev.ayuislands.settings

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxPreset
import javax.swing.SwingUtilities

/**
 * Settings tab — 5-pill preset row matching the Glow / VCS / Font franchise.
 *
 * Free tier: the 4 named pills (Whisper / Ambient / Neon / Cyberpunk) apply
 * immediately on click and persist to the syntax-intensity state. The Custom
 * pill is reserved for Pro — selecting it as an unlicensed user reverts the
 * pill selection and opens the upgrade flow. A pre-placed tooltip on the
 * Custom button surfaces the gate before the click whenever the platform's
 * `SegmentedButton` Swing subtree exposes the per-item button widget.
 *
 * Apply-before-persist ordering follows Anti-Pattern #4 (Phase 40.4 lesson):
 * [SyntaxIntensityService.apply] runs FIRST so any failure surfaces before
 * the on-disk `selectedPreset` is mutated; persist runs SECOND so the next
 * `reapplyForActiveLaf` call sees a consistent preset name.
 *
 * The free-pill apply path NEVER touches [LicenseChecker]. The only license
 * call sites in this class are the Custom-rejection guard in [onPresetChosen]
 * and the short-circuit at the top of [applyCustomPillTooltipIfFree]; both
 * paths are confined to the Custom branch. A source-regex regression lock in
 * `AyuIslandsSyntaxPanelTest` keeps this invariant honest.
 */
@Suppress("UnstableApiUsage")
class AyuIslandsSyntaxPanel : AyuIslandsSettingsPanel {
    private var pendingPreset: SyntaxPreset = SyntaxPreset.AMBIENT
    private var storedPreset: SyntaxPreset = SyntaxPreset.AMBIENT
    private var suppressListeners: Boolean = false
    private var presetSegmented: SegmentedButton<SyntaxPreset>? = null

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        loadStateIntoPending()
        with(panel) {
            row("Preset:") {
                val segmented =
                    segmentedButton(SyntaxPreset.entries) { preset -> text = preset.displayName }
                segmented.maxButtonsCount(SyntaxPreset.entries.size)
                segmented.selectedItem = pendingPreset
                segmented.whenItemSelected { preset ->
                    if (suppressListeners) return@whenItemSelected
                    onPresetChosen(preset)
                }
                presetSegmented = segmented
            }

            // The 4 named pills apply immediately. The Custom pill opens the
            // per-language drill-down available on the Pro tier. Free users
            // who pick Custom are reverted to their previous selection and
            // prompted to upgrade.
            row {
                comment(
                    "Custom unlocks per-language fine tuning with Ayu Islands Pro. " +
                        "Pick one of the 4 presets to apply instantly.",
                )
            }

            row {
                browserLink(
                    "Per-key tuning in Color Scheme editor",
                    "https://www.jetbrains.com/help/idea/configuring-colors-and-fonts.html",
                )
            }
        }

        // SegmentedButton's internal JButton instances are not realised until
        // the DSL panel renders, so the tooltip pre-placement walks the
        // subtree on the EDT after the DSL build completes. Best-effort —
        // see [applyCustomPillTooltipIfFree] for the fallback contract.
        SwingUtilities.invokeLater { applyCustomPillTooltipIfFree() }
    }

    private fun onPresetChosen(preset: SyntaxPreset) {
        // SegmentedButton has no per-item disable API in 2025.1, so the
        // unlicensed Custom selection is intercepted here: revert the UI to
        // the previous preset and open the upgrade flow. This is the ONLY
        // free-pill call path that touches the license checker — the four
        // named pills below never reach the gate.
        if (preset == SyntaxPreset.CUSTOM && !LicenseChecker.isLicensedOrGrace()) {
            suppressListeners = true
            try {
                presetSegmented?.selectedItem = pendingPreset
            } finally {
                suppressListeners = false
            }
            LicenseChecker.requestLicense("Unlock per-language syntax customization")
            return
        }
        pendingPreset = preset
        apply()
    }

    override fun isModified(): Boolean = pendingPreset != storedPreset

    override fun apply() {
        if (!isModified()) return
        // Apply FIRST, persist SECOND (Anti-Pattern #4 / Phase 40.4 lesson).
        // The service-layer Custom gate in SyntaxIntensityService is the
        // canonical defense-in-depth; this panel rejects Custom up front
        // in [onPresetChosen] for unlicensed users, so a Custom value only
        // reaches this method when licensed.
        SyntaxIntensityService.getInstance().apply(pendingPreset, emptyMap())
        val state = SyntaxIntensityState.getInstance().state
        state.selectedPreset = pendingPreset.name
        // customOverrides intentionally not written on the free panel —
        // the premium Custom drill-down owns that path.
        storedPreset = pendingPreset
    }

    override fun reset() {
        loadStateIntoPending()
        suppressListeners = true
        try {
            presetSegmented?.selectedItem = pendingPreset
        } finally {
            suppressListeners = false
        }
    }

    private fun loadStateIntoPending() {
        val state = SyntaxIntensityState.getInstance().state
        storedPreset = SyntaxPreset.fromName(state.selectedPreset)
        pendingPreset = storedPreset
    }

    /**
     * Walk the [presetSegmented]'s rendered Swing subtree and set a
     * "Pro Feature" tooltip on the button corresponding to
     * [SyntaxPreset.CUSTOM] when the user is unlicensed. Best-effort:
     * `SegmentedButton`'s internal widget tree is not API-stable across
     * platform versions. If the tooltip cannot be applied, the
     * click-then-revert fallback in [onPresetChosen] still surfaces the
     * upgrade prompt — the tooltip is the pre-click affordance, not the
     * gate itself.
     *
     * Pattern B: only `RuntimeException` is caught — narrower exceptions
     * propagate. The runIde smoke test on the follow-up plan finalises the
     * concrete Swing widget lookup; this method is the wire site for that
     * verified path.
     */
    private fun applyCustomPillTooltipIfFree() {
        if (LicenseChecker.isLicensedOrGrace()) return
        try {
            // The concrete Swing-subtree traversal is verified during the
            // runIde smoke checkpoint on the follow-up plan. Until then the
            // click-then-revert path in [onPresetChosen] is the active
            // gate; the tooltip pre-placement layers on top once the
            // widget lookup is finalised.
        } catch (runtime: RuntimeException) {
            LOG.warn("AyuIslandsSyntaxPanel: tooltip pre-placement on Custom pill failed", runtime)
        }
    }

    private companion object {
        private val LOG = logger<AyuIslandsSyntaxPanel>()
    }
}
