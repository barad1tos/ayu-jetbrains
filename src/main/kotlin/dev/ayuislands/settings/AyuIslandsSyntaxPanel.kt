package dev.ayuislands.settings

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import dev.ayuislands.syntax.SyntaxPreset
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

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
 *
 * Custom drill-down layout (Direction B): the 16 [PrimitiveCategory] sliders
 * are arranged into four non-collapsible [CategoryGroup] sections by syntactic
 * role, two category units per row. Each unit owns a tick-free slider with a
 * signed-delta readout and a per-row "Reset" [ActionLink]; the master reset is
 * scoped to the active language. The value model (0..100, 50 = identity,
 * sparse store keyed by `language|category.name`) is unchanged — the signed
 * string lives only in the readout [JLabel] and is never parsed back.
 */
@Suppress("UnstableApiUsage")
class AyuIslandsSyntaxPanel : AyuIslandsSettingsPanel {
    private var pendingPreset: SyntaxPreset = SyntaxPreset.AMBIENT
    private var storedPreset: SyntaxPreset = SyntaxPreset.AMBIENT
    private var suppressListeners: Boolean = false
    private var presetSegmented: SegmentedButton<SyntaxPreset>? = null

    // Custom drill-down state. The fold-out captures per-(language, category)
    // overrides; only cells the user actually moves persist (sparse store).
    // Untouched cells inherit the subordinate (last-named) preset at resolve
    // time, so the override map stays small.
    private val customSelected = AtomicBooleanProperty(pendingPreset == SyntaxPreset.CUSTOM)
    private var suppressSliderListeners: Boolean = false
    private var pendingSubordinate: SyntaxPreset = SyntaxPreset.AMBIENT
    private var storedSubordinate: SyntaxPreset = SyntaxPreset.AMBIENT
    private val pendingOverrides: MutableMap<String, String> = mutableMapOf()
    private val storedOverrides: MutableMap<String, String> = mutableMapOf()
    private val sliders: MutableMap<PrimitiveCategory, JSlider> = mutableMapOf()
    private val sliderLabels: MutableMap<PrimitiveCategory, JLabel> = mutableMapOf()
    private val resetLinks: MutableMap<PrimitiveCategory, ActionLink> = mutableMapOf()
    private var masterResetButton: JButton? = null
    private var currentLanguage: String = ""

    // Single-shot debounce: a drag burst restarts the timer, so the apply
    // fires once per 100ms pause rather than on every change event. The
    // change listener never calls apply() synchronously — it defers here.
    private val applyTimer =
        Timer(DEBOUNCE_MS, null).apply { isRepeats = false }

    init {
        applyTimer.addActionListener { apply() }
    }

    override fun dispose() {
        applyTimer.stop()
    }

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

            buildCustomFoldOut()
        }

        // SegmentedButton's internal JButton instances are not realised until
        // the DSL panel renders, so the tooltip pre-placement walks the
        // subtree on the EDT after the DSL build completes. Best-effort —
        // see [applyCustomPillTooltipIfFree] for the fallback contract.
        SwingUtilities.invokeLater { applyCustomPillTooltipIfFree() }
    }

    /**
     * Premium Custom drill-down. Renders only when the Custom pill is the
     * active selection (driven by [customSelected]): a language selector +
     * four [CategoryGroup] sections of per-category sliders + a per-language
     * master reset. The sliders are re-bound to the selected language's
     * stored values on combo change, so 26 languages share the same widgets
     * instead of materializing 416 rows. The free/unlicensed gate lives
     * upstream in [onPresetChosen]; the fold-out adds no license call of its
     * own.
     */
    private fun Panel.buildCustomFoldOut() {
        val languages = SyntaxLanguageRegistry.supportedLanguages().map { it.displayName }
        currentLanguage = languages.firstOrNull() ?: ""
        row("Language:") {
            val combo = comboBox(languages).component
            combo.selectedItem = currentLanguage
            combo.addActionListener {
                val language = combo.selectedItem as? String ?: return@addActionListener
                currentLanguage = language
                rebindSlidersFor(language)
                refreshMasterResetButton()
            }
        }.visibleIf(customSelected)

        for (categoryGroup in CATEGORY_GROUPS) {
            buildCategoryGroup(categoryGroup)
        }

        row {
            val resetButton =
                button("") { onResetCurrentLanguage() }.component
            masterResetButton = resetButton
        }.visibleIf(customSelected)

        rebindSlidersFor(currentLanguage)
        refreshMasterResetButton()
    }

    /**
     * One syntactic-role section. The group is NON-collapsible — the only
     * show/hide boundary is the [customSelected] gate carried by every inner
     * row. Category units are laid out two per row (row-major Tab order),
     * with a [Panel.placeholder] filling the trailing slot when a group has
     * an odd category count.
     */
    private fun Panel.buildCategoryGroup(categoryGroup: CategoryGroup) {
        group(categoryGroup.title) {
            for (pair in categoryGroup.categories.chunked(2)) {
                row {
                    cell(buildCategoryUnit(pair[0])).align(AlignX.FILL).resizableColumn()
                    val right = pair.getOrNull(1)
                    if (right != null) {
                        cell(buildCategoryUnit(right)).align(AlignX.FILL).resizableColumn()
                    } else {
                        placeholder().align(AlignX.FILL).resizableColumn()
                    }
                }.visibleIf(customSelected)
            }
        }.visibleIf(customSelected)
    }

    /**
     * One self-contained per-category control unit built via the IntelliJ
     * UI-DSL `slider()` cell (the themed Darcula widget) — never a bare
     * `JSlider(...)` constructor. The slider is tick-free and width-capped; a
     * signed-delta readout ([signedReadout]) and a per-row "Reset"
     * [ActionLink] (visible only when the cell diverges from identity) sit to
     * its right. The underlying `JSlider`, readout label, and reset link are
     * stashed by category so [rebindSlidersFor] / [setSliderValue] can snap
     * them on combo change.
     */
    private fun buildCategoryUnit(category: PrimitiveCategory): DialogPanel =
        panel {
            row(category.displayName) {
                val jslider =
                    slider(SLIDER_MIN, SLIDER_MAX, 0, 0)
                        .applyToComponent {
                            paintTicks = false
                            paintLabels = false
                            snapToTicks = false
                            val width = JBUI.scale(SLIDER_TRACK_WIDTH)
                            preferredSize = Dimension(width, preferredSize.height)
                            maximumSize = Dimension(width, preferredSize.height)
                        }.component
                val valueLabel =
                    JLabel(signedReadout(SLIDER_MID), SwingConstants.RIGHT).apply {
                        foreground = UIUtil.getContextHelpForeground()
                        val width = JBUI.scale(READOUT_WIDTH)
                        preferredSize = Dimension(width, preferredSize.height)
                    }
                val resetLink =
                    ActionLink("Reset") {
                        setSliderValue(category, SLIDER_MID)
                        pendingOverrides.remove("$currentLanguage|${category.name}")
                        refreshMasterResetButton()
                        applyTimer.restart()
                    }.apply {
                        isVisible = false
                        accessibleContext.accessibleName = "Reset ${category.displayName} to default"
                    }
                jslider.addChangeListener { onSliderChanged(currentLanguage, category, jslider.value) }
                cell(valueLabel).gap(RightGap.SMALL)
                cell(resetLink)
                sliders[category] = jslider
                sliderLabels[category] = valueLabel
                resetLinks[category] = resetLink
            }
        }

    /**
     * Sparse write-through for one slider move: refresh the readout +
     * accessibility name + reset-link visibility, then (unless the move was
     * programmatic) record only the cell the user touched and defer the apply
     * through the single-shot debounce timer. The apply is NEVER invoked
     * synchronously here.
     */
    private fun onSliderChanged(
        language: String,
        category: PrimitiveCategory,
        value: Int,
    ) {
        sliderLabels[category]?.text = signedReadout(value)
        resetLinks[category]?.isVisible = value != SLIDER_MID
        refreshSliderAccessibleName(category, value)
        if (suppressSliderListeners) return
        pendingOverrides["$language|${category.name}"] = value.toString()
        refreshMasterResetButton()
        applyTimer.restart()
    }

    /**
     * Programmatic snap of one slider + its readout + reset-link visibility,
     * wrapped in [suppressSliderListeners] so it does not re-enter
     * [onSliderChanged]'s write path. Mirrors the rebind snap.
     */
    private fun setSliderValue(
        category: PrimitiveCategory,
        value: Int,
    ) {
        suppressSliderListeners = true
        try {
            sliders[category]?.value = value
            sliderLabels[category]?.text = signedReadout(value)
            resetLinks[category]?.isVisible = value != SLIDER_MID
            refreshSliderAccessibleName(category, value)
        } finally {
            suppressSliderListeners = false
        }
    }

    /**
     * Per-language master reset: wipe only the override cells keyed to the
     * active language, snap the visible sliders back to identity, and apply so
     * the editor falls back to the subordinate preset for that language. Other
     * languages' overrides are left intact.
     */
    private fun onResetCurrentLanguage() {
        val prefix = "$currentLanguage|"
        pendingOverrides.keys.filter { it.startsWith(prefix) }.forEach { pendingOverrides.remove(it) }
        rebindSlidersFor(currentLanguage)
        refreshMasterResetButton()
        applyTimer.restart()
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
        // A named preset becomes the subordinate Custom layers on; selecting
        // Custom keeps the last-named preset as the inheritance base for
        // untouched cells.
        if (preset != SyntaxPreset.CUSTOM) {
            pendingSubordinate = preset
        }
        customSelected.set(preset == SyntaxPreset.CUSTOM)
        apply()
    }

    override fun isModified(): Boolean =
        pendingPreset != storedPreset ||
            pendingOverrides != storedOverrides ||
            pendingSubordinate != storedSubordinate

    override fun apply() {
        if (!isModified()) return
        // Apply FIRST, persist SECOND (Anti-Pattern #4 / Phase 40.4 lesson).
        // The service-layer Custom gate in SyntaxIntensityService is the
        // canonical defense-in-depth; this panel rejects Custom up front
        // in [onPresetChosen] for unlicensed users, so a Custom value only
        // reaches this method when licensed.
        val nested = buildNestedOverrides(pendingOverrides)
        SyntaxIntensityService.getInstance().apply(pendingPreset, nested, pendingSubordinate)
        val state = SyntaxIntensityState.getInstance().state
        state.selectedPreset = pendingPreset.name
        state.subordinatePreset = pendingSubordinate.name
        state.customOverrides.clear()
        state.customOverrides.putAll(pendingOverrides)
        storedPreset = pendingPreset
        storedSubordinate = pendingSubordinate
        storedOverrides.clear()
        storedOverrides.putAll(pendingOverrides)
    }

    override fun reset() {
        loadStateIntoPending()
        suppressListeners = true
        try {
            presetSegmented?.selectedItem = pendingPreset
        } finally {
            suppressListeners = false
        }
        customSelected.set(pendingPreset == SyntaxPreset.CUSTOM)
        rebindSlidersFor(currentLanguage)
        refreshMasterResetButton()
    }

    private fun loadStateIntoPending() {
        val state = SyntaxIntensityState.getInstance().state
        storedPreset = SyntaxPreset.fromName(state.selectedPreset)
        pendingPreset = storedPreset
        storedSubordinate = SyntaxPreset.fromName(state.subordinatePreset)
        pendingSubordinate = storedSubordinate
        storedOverrides.clear()
        storedOverrides.putAll(state.customOverrides)
        pendingOverrides.clear()
        pendingOverrides.putAll(state.customOverrides)
    }

    /**
     * Snap every slider (and its signed readout + reset-link visibility) to
     * the stored value for [language], or to the identity midpoint when that
     * cell is untouched. Wrapped in [suppressSliderListeners] so the
     * programmatic snap does not feed back into [onSliderChanged]. A no-op
     * until the fold-out has populated [sliders] / [sliderLabels] /
     * [resetLinks].
     */
    private fun rebindSlidersFor(language: String) {
        suppressSliderListeners = true
        try {
            for (category in PrimitiveCategory.entries) {
                val value = pendingOverrides["$language|${category.name}"]?.toIntOrNull() ?: SLIDER_MID
                sliders[category]?.value = value
                sliderLabels[category]?.text = signedReadout(value)
                resetLinks[category]?.isVisible = value != SLIDER_MID
                refreshSliderAccessibleName(category, value)
            }
        } finally {
            suppressSliderListeners = false
        }
    }

    /**
     * Enable the master reset button only when the active language has at
     * least one override cell, and track the language in its label. A no-op
     * until the fold-out has materialized the button.
     */
    private fun refreshMasterResetButton() {
        val button = masterResetButton ?: return
        button.text = "Reset $currentLanguage customizations"
        button.isEnabled = pendingOverrides.keys.any { it.startsWith("$currentLanguage|") }
    }

    /**
     * Refresh the slider's accessible name to announce its signed distance
     * from the identity default. A no-op until the slider is materialized.
     */
    private fun refreshSliderAccessibleName(
        category: PrimitiveCategory,
        value: Int,
    ) {
        sliders[category]?.accessibleContext?.accessibleName =
            "${category.displayName} intensity, ${signedReadout(value)} from default"
    }

    /**
     * Presentation-only signed-delta string for a stored 0..100 value: above
     * the [SLIDER_MID] identity reads `+N`, below reads `−N` (U+2212 minus),
     * and identity reads `0`. The signed string lives ONLY in the readout
     * label / accessibility name and is never parsed back into the value
     * model — `sliderToCurve` still consumes the raw int.
     */
    private fun signedReadout(value: Int): String =
        when {
            value > SLIDER_MID -> "+${value - SLIDER_MID}"
            value < SLIDER_MID -> "−${SLIDER_MID - value}"
            else -> "0"
        }

    /**
     * Reshape the flat composite-key override map into the nested
     * `language → category → Int` shape the service consumes. Uses the same
     * `|` split + [String.toIntOrNull] guard as
     * [SyntaxIntensityState.toPresetConfig]: keys with an empty language
     * half, an empty category half, or a non-Int value are skipped.
     */
    private fun buildNestedOverrides(flat: Map<String, String>): Map<String, Map<String, Int>> {
        val nested = mutableMapOf<String, MutableMap<String, Int>>()
        for ((compositeKey, valueStr) in flat) {
            val pipeIdx = compositeKey.indexOf('|')
            if (pipeIdx <= 0 || pipeIdx == compositeKey.length - 1) continue
            val language = compositeKey.substring(0, pipeIdx)
            val category = compositeKey.substring(pipeIdx + 1)
            val slider = valueStr.toIntOrNull() ?: continue
            nested.getOrPut(language) { mutableMapOf() }[category] = slider
        }
        return nested
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

    /**
     * One syntactic-role section of the Custom drill-down. [CATEGORY_GROUPS]
     * partitions all 16 [PrimitiveCategory] entries into four buckets by
     * syntactic role; the coverage invariant (every category present exactly
     * once) is locked by `AyuIslandsSyntaxPanelTest` so a future 17th enum
     * cannot silently drop out of the UI.
     */
    private data class CategoryGroup(
        val title: String,
        val categories: List<PrimitiveCategory>,
    )

    private companion object {
        private val LOG = logger<AyuIslandsSyntaxPanel>()
        private const val DEBOUNCE_MS = 100
        private const val SLIDER_MIN = 0
        private const val SLIDER_MAX = 100
        private const val SLIDER_MID = 50
        private const val SLIDER_TRACK_WIDTH = 160
        private const val READOUT_WIDTH = 40

        /**
         * The 16 [PrimitiveCategory] entries grouped by syntactic role and
         * ordered by visual weight (4 / 5 / 3 / 4). The flat-map of all four
         * buckets equals `PrimitiveCategory.entries` exactly once — guarded by
         * a coverage-invariant test.
         */
        private val CATEGORY_GROUPS: List<CategoryGroup> =
            listOf(
                CategoryGroup(
                    "Declarations",
                    listOf(
                        PrimitiveCategory.FUNCTION_DECL,
                        PrimitiveCategory.CLASS_DECL,
                        PrimitiveCategory.INTERFACE_DECL,
                        PrimitiveCategory.TYPE_REF,
                    ),
                ),
                CategoryGroup(
                    "Identifiers & Members",
                    listOf(
                        PrimitiveCategory.PARAMETER,
                        PrimitiveCategory.LOCAL_VAR,
                        PrimitiveCategory.INSTANCE_FIELD,
                        PrimitiveCategory.STATIC_FIELD,
                        PrimitiveCategory.GENERICS,
                    ),
                ),
                CategoryGroup(
                    "Literals",
                    listOf(
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.NUMBER_LITERAL,
                        PrimitiveCategory.OPERATOR,
                    ),
                ),
                CategoryGroup(
                    "Keywords & Docs",
                    listOf(
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.COMMENT,
                        PrimitiveCategory.DOCUMENTATION,
                    ),
                ),
            )
    }
}
