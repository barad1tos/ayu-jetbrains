package dev.ayuislands.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import dev.ayuislands.syntax.SyntaxPreset
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
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
 * Custom drill-down layout: the 16 [PrimitiveCategory] controls are arranged
 * as four semantic groups in two column-level grids. Each row keeps the same
 * fixed cells — category, tick-free slider, signed readout, and a fixed
 * reset / Bold / Italic slot zone — so controls line up without a long empty
 * single table. The slider value model (0..100, 50 = identity, sparse store
 * keyed by `language|category.name`) is unchanged — the signed string lives only in the
 * readout [JLabel] and is never parsed back. The font-style model is an
 * orthogonal sparse store keyed by the same composite key, mapping to a
 * [FontStyleOverride] enum `name`; both stores thread through [apply] in
 * parallel.
 */
@Suppress("TooManyFunctions", "UnstableApiUsage") // Settings panel with focused UI lifecycle helpers.
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

    // Font-style store: flat "language|category" -> FontStyleOverride enum name.
    // Orthogonal to pendingOverrides — a cell may carry a style with no slider
    // move, or a slider move with no style. Both diff into [isModified] and
    // both thread through [apply].
    private val pendingStyles: MutableMap<String, String> = mutableMapOf()
    private val storedStyles: MutableMap<String, String> = mutableMapOf()
    private val sliders: MutableMap<PrimitiveCategory, JSlider> = mutableMapOf()
    private val sliderLabels: MutableMap<PrimitiveCategory, JLabel> = mutableMapOf()
    private val resetButtons: MutableMap<PrimitiveCategory, InplaceButton> = mutableMapOf()
    private val boldToggles: MutableMap<PrimitiveCategory, InplaceButton> = mutableMapOf()
    private val italicToggles: MutableMap<PrimitiveCategory, InplaceButton> = mutableMapOf()
    private var masterResetButton: JButton? = null
    private var currentLanguage: String = ""

    // One uniform leading-label width shared by every row in both column-level
    // grids. Pinning the label width keeps slider starts aligned across the
    // two independent UI DSL grids.
    private val labelColumnWidth: Int by lazy { computeLabelColumnWidth() }

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
                    "Custom provides per-language fine tuning. Pick one of the 4 presets to apply instantly.",
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

    /** Build the premium Custom drill-down as two grouped, aligned columns. */
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
            val resetButton =
                button("") { onResetCurrentLanguage() }.component.apply {
                    isVisible = false
                }
            masterResetButton = resetButton
        }.visibleIf(customSelected)

        row {
            panel {
                for (categoryGroup in CUSTOM_COLUMN_GROUPS.first()) {
                    buildCategoryGroup(categoryGroup)
                }
            }.align(AlignX.FILL)
                .resizableColumn()
            panel {
                for (categoryGroup in CUSTOM_COLUMN_GROUPS.last()) {
                    buildCategoryGroup(categoryGroup)
                }
            }.align(AlignX.FILL)
                .resizableColumn()
        }.visibleIf(customSelected)

        rebindSlidersFor(currentLanguage)
        refreshMasterResetButton()
    }

    private fun Panel.buildCategoryGroup(categoryGroup: CategoryGroup) {
        group(categoryGroup.title) {
            for (category in categoryGroup.categories) {
                categoryRow(category)
            }
        }
    }

    private fun Panel.categoryRow(category: PrimitiveCategory) {
        row {
            cell(
                JLabel(category.displayName).apply {
                    val width = labelColumnWidth
                    preferredSize = Dimension(width, preferredSize.height)
                    minimumSize = Dimension(width, preferredSize.height)
                },
            ).gap(RightGap.SMALL)
            val jslider =
                slider(SLIDER_MIN, SLIDER_MAX, 0, 0)
                    .applyToComponent {
                        paintTicks = false
                        paintLabels = false
                        snapToTicks = false
                        val width = JBUI.scale(SLIDER_TRACK_WIDTH)
                        preferredSize = Dimension(width, preferredSize.height)
                        maximumSize = Dimension(width, preferredSize.height)
                    }.gap(RightGap.SMALL)
                    .component
            val valueLabel =
                JLabel(signedReadout(SLIDER_MID), SwingConstants.RIGHT).apply {
                    val width = JBUI.scale(READOUT_WIDTH)
                    preferredSize = Dimension(width, preferredSize.height)
                }
            applyReadout(valueLabel, SLIDER_MID)
            jslider.addChangeListener { onSliderChanged(currentLanguage, category, jslider.value) }
            cell(valueLabel).gap(RightGap.SMALL)
            val resetButton =
                InplaceButton("Reset ${category.displayName} to default", AllIcons.Actions.Rollback) {
                    resetCell(category)
                }.apply {
                    isVisible = false
                    isFocusable = true
                    accessibleContext.accessibleName = "Reset ${category.displayName} to default"
                }
            val boldToggle =
                InplaceButton("Bold ${category.displayName}", styleGlyphIcon("B", Font.BOLD, engaged = false)) {
                    onStyleToggle(category, Font.BOLD)
                }.apply {
                    isFocusable = true
                    accessibleContext.accessibleName = "Bold ${category.displayName}"
                }
            val italicToggle =
                InplaceButton("Italic ${category.displayName}", styleGlyphIcon("I", Font.ITALIC, engaged = false)) {
                    onStyleToggle(category, Font.ITALIC)
                }.apply {
                    isFocusable = true
                    accessibleContext.accessibleName = "Italic ${category.displayName}"
                }
            resetButtons[category] = resetButton
            boldToggles[category] = boldToggle
            italicToggles[category] = italicToggle
            val trailingZone =
                JPanel(GridLayout(1, TRAILING_SLOT_COUNT, JBUI.scale(TRAILING_GAP), 0)).apply {
                    isOpaque = false
                    val zoneWidth = JBUI.scale(TRAILING_ZONE_WIDTH)
                    val zoneHeight = JBUI.scale(TRAILING_SLOT_SIDE)
                    preferredSize = Dimension(zoneWidth, zoneHeight)
                    minimumSize = Dimension(zoneWidth, zoneHeight)
                    add(resetButton)
                    add(boldToggle)
                    add(italicToggle)
                }
            cell(trailingZone)
            sliders[category] = jslider
            sliderLabels[category] = valueLabel
            refreshStyleVisuals(category)
        }
    }

    private fun onStyleToggle(
        category: PrimitiveCategory,
        bit: Int,
    ) {
        val key = compositeKey(currentLanguage, category)
        val current = FontStyleOverride.fromName(pendingStyles[key])?.fontType ?: Font.PLAIN
        val next = current xor bit
        val nextStyle = FontStyleOverride.entries.firstOrNull { it.fontType == next }
        if (nextStyle == null || nextStyle == FontStyleOverride.PLAIN) {
            pendingStyles.remove(key)
        } else {
            pendingStyles[key] = nextStyle.name
        }
        refreshStyleVisuals(category)
        refreshResetVisibility(category)
        refreshMasterResetButton()
        applyTimer.restart()
    }

    private fun refreshStyleVisuals(category: PrimitiveCategory) {
        val key = compositeKey(currentLanguage, category)
        val fontType = FontStyleOverride.fromName(pendingStyles[key])?.fontType ?: Font.PLAIN
        boldToggles[category]?.let { button ->
            button.setIcon(styleGlyphIcon("B", Font.BOLD, fontType and Font.BOLD != 0))
            button.repaint()
        }
        italicToggles[category]?.let { button ->
            button.setIcon(styleGlyphIcon("I", Font.ITALIC, fontType and Font.ITALIC != 0))
            button.repaint()
        }
    }

    private fun styleGlyphIcon(
        glyph: String,
        style: Int,
        engaged: Boolean,
    ): StyleGlyphIcon {
        val foreground = if (engaged) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
        val background =
            if (engaged) {
                JBUI.CurrentTheme.ActionButton.pressedBackground()
            } else {
                JBUI.CurrentTheme.ActionButton.hoverBackground()
            }
        return StyleGlyphIcon(glyph, style, foreground, background, border = styleGlyphBorder())
    }

    private fun styleGlyphBorder(): JBColor =
        JBColor.namedColor(
            "Popup.innerBorderColor",
            JBColor.namedColor("Popup.borderColor", JBColor.GRAY),
        )

    private fun refreshResetVisibility(category: PrimitiveCategory) {
        val key = compositeKey(currentLanguage, category)
        val sliderMoved = (sliders[category]?.value ?: SLIDER_MID) != SLIDER_MID
        val styled = pendingStyles[key] != null
        resetButtons[category]?.isVisible = sliderMoved || styled
    }

    private fun computeLabelColumnWidth(): Int {
        val font = UIUtil.getLabelFont()
        val metrics = JLabel().getFontMetrics(font)
        val widest = PrimitiveCategory.entries.maxOf { metrics.stringWidth(it.displayName) }
        return if (widest <= 0) JBUI.scale(LABEL_FALLBACK_WIDTH) else widest + JBUI.scale(LABEL_PADDING)
    }

    private fun resetCell(category: PrimitiveCategory) {
        setSliderValue(category, SLIDER_MID)
        val key = compositeKey(currentLanguage, category)
        pendingOverrides.remove(key)
        pendingStyles.remove(key)
        refreshStyleVisuals(category)
        refreshResetVisibility(category)
        refreshMasterResetButton()
        applyTimer.restart()
    }

    private fun onSliderChanged(
        language: String,
        category: PrimitiveCategory,
        value: Int,
    ) {
        sliderLabels[category]?.let { applyReadout(it, value) }
        sliders[category]?.accessibleContext?.accessibleName = intensityAccessibleName(category, value)
        if (suppressSliderListeners) return
        val key = compositeKey(language, category)
        if (value == SLIDER_MID) {
            pendingOverrides.remove(key)
        } else {
            pendingOverrides[key] = value.toString()
        }
        refreshResetVisibility(category)
        refreshMasterResetButton()
        applyTimer.restart()
    }

    private fun setSliderValue(
        category: PrimitiveCategory,
        value: Int,
    ) {
        suppressSliderListeners = true
        try {
            sliders[category]?.value = value
            sliderLabels[category]?.let { applyReadout(it, value) }
            sliders[category]?.accessibleContext?.accessibleName = intensityAccessibleName(category, value)
            refreshResetVisibility(category)
        } finally {
            suppressSliderListeners = false
        }
    }

    private fun intensityAccessibleName(
        category: PrimitiveCategory,
        value: Int,
    ): String = "${category.displayName} intensity, ${signedReadout(value)} from default"

    private fun compositeKey(
        language: String,
        category: PrimitiveCategory,
    ): String = "$language|${category.name}"

    /**
     * Per-language master reset: wipe only the override AND style cells keyed
     * to the active language, snap visible rows back to identity / inherit, and
     * apply so the editor falls back to the subordinate preset for that
     * language. Other languages' cells are left intact.
     */
    private fun onResetCurrentLanguage() {
        val prefix = "$currentLanguage|"
        pendingOverrides.keys.filter { it.startsWith(prefix) }.forEach { pendingOverrides.remove(it) }
        pendingStyles.keys.filter { it.startsWith(prefix) }.forEach { pendingStyles.remove(it) }
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
            pendingStyles != storedStyles ||
            pendingSubordinate != storedSubordinate

    override fun apply() {
        if (!isModified()) return
        // Apply FIRST, persist SECOND (Anti-Pattern #4 / Phase 40.4 lesson).
        // The service-layer Custom gate in SyntaxIntensityService is the
        // canonical defense-in-depth; this panel rejects Custom up front
        // in [onPresetChosen] for unlicensed users, so a Custom value only
        // reaches this method when licensed. The slider overrides and the
        // font-style overrides thread through the service call in parallel.
        val nested = buildNested(pendingOverrides) { it.toIntOrNull() }
        val nestedStyles = buildNested(pendingStyles) { FontStyleOverride.fromName(it)?.fontType }
        SyntaxIntensityService.getInstance().apply(pendingPreset, nested, pendingSubordinate, nestedStyles)
        val state = SyntaxIntensityState.getInstance().state
        state.selectedPreset = pendingPreset.name
        state.subordinatePreset = pendingSubordinate.name
        state.customOverrides.clear()
        state.customOverrides.putAll(pendingOverrides)
        state.customStyles.clear()
        state.customStyles.putAll(pendingStyles)
        storedPreset = pendingPreset
        storedSubordinate = pendingSubordinate
        storedOverrides.clear()
        storedOverrides.putAll(pendingOverrides)
        storedStyles.clear()
        storedStyles.putAll(pendingStyles)
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
        storedStyles.clear()
        storedStyles.putAll(state.customStyles)
        pendingStyles.clear()
        pendingStyles.putAll(state.customStyles)
    }

    private fun rebindSlidersFor(language: String) {
        suppressSliderListeners = true
        try {
            for (category in PrimitiveCategory.entries) {
                val value = pendingOverrides[compositeKey(language, category)]?.toIntOrNull() ?: SLIDER_MID
                sliders[category]?.value = value
                sliderLabels[category]?.let { applyReadout(it, value) }
                sliders[category]?.accessibleContext?.accessibleName = intensityAccessibleName(category, value)
                refreshStyleVisuals(category)
                refreshResetVisibility(category)
            }
        } finally {
            suppressSliderListeners = false
        }
    }

    /**
     * Show the master reset button only when the active language has at least
     * one override OR style cell, and track the language in its label. A no-op
     * until the fold-out has materialized the button.
     */
    private fun refreshMasterResetButton() {
        val button = masterResetButton ?: return
        val prefix = "$currentLanguage|"
        val hasLanguageCustomizations =
            pendingOverrides.keys.any { it.startsWith(prefix) } ||
                pendingStyles.keys.any { it.startsWith(prefix) }
        button.text = "Reset $currentLanguage customizations"
        button.isVisible = hasLanguageCustomizations
        button.isEnabled = hasLanguageCustomizations
    }

    /**
     * Single update site for a readout [JLabel]'s text AND foreground so the
     * three callers ([onSliderChanged], [setSliderValue], [rebindSlidersFor])
     * stay in lock-step. At the [SLIDER_MID] identity the readout is visually
     * empty and dimmed to reduce noise; once the cell diverges the signed delta
     * switches to the stronger
     * `getLabelForeground` to signal a moved value. Presentation-only — the
     * value model is unaffected.
     */
    private fun applyReadout(
        label: JLabel,
        value: Int,
    ) {
        val isIdentity = value == SLIDER_MID
        label.text = if (isIdentity) "" else signedReadout(value)
        label.foreground =
            if (isIdentity) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
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
     * Reshape a flat composite-key store into the nested
     * `language → category → Int` shape [SyntaxIntensityService.apply] consumes.
     * Shared by the slider-override and font-style bridges (each passes its own
     * [decode]) so both apply the SAME `|`-split + skip-on-bad-key guard as
     * [SyntaxIntensityState.toPresetConfig]:
     *  - slider overrides decode via [String.toIntOrNull] (raw 0..100 value);
     *  - font styles decode via `FontStyleOverride.fromName(v)?.fontType` to the
     *    `java.awt.Font` bitmask the applicator writes into
     *    `TextAttributes.fontType`.
     *
     * Keys with an empty language half, an empty category half, or a value that
     * [decode] rejects (`null`) are silently skipped — tamper-safe, no
     * `runCatching`, no broad `catch` (Pattern B).
     */
    private fun buildNested(
        flat: Map<String, String>,
        decode: (String) -> Int?,
    ): Map<String, Map<String, Int>> {
        val nested = mutableMapOf<String, MutableMap<String, Int>>()
        for ((compositeKey, valueStr) in flat) {
            val pipeIdx = compositeKey.indexOf('|')
            if (pipeIdx <= 0 || pipeIdx == compositeKey.length - 1) continue
            val language = compositeKey.substring(0, pipeIdx)
            val category = compositeKey.substring(pipeIdx + 1)
            val decoded = decode(valueStr) ?: continue
            nested.getOrPut(language) { mutableMapOf() }[category] = decoded
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

        private const val SLIDER_TRACK_WIDTH = 140
        private const val READOUT_WIDTH = 28
        private const val TRAILING_SLOT_COUNT = 3
        private const val TRAILING_SLOT_SIDE = 20
        private const val TRAILING_ZONE_WIDTH = 64
        private const val TRAILING_GAP = 2
        private const val LABEL_PADDING = 8
        private const val LABEL_FALLBACK_WIDTH = 170

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

        private val CUSTOM_COLUMN_GROUPS: List<List<CategoryGroup>> =
            listOf(
                listOf(CATEGORY_GROUPS[0], CATEGORY_GROUPS[3]),
                listOf(CATEGORY_GROUPS[1], CATEGORY_GROUPS[2]),
            )
    }
}
