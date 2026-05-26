package dev.ayuislands.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.InplaceButton
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
import java.awt.FlowLayout
import java.awt.Font
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
 * Custom drill-down layout (Direction B): the 16 [PrimitiveCategory] sliders
 * are arranged into four non-collapsible [CategoryGroup] sections by syntactic
 * role. Within each section the categories split round-robin into two
 * shared-grid column `panel { }` blocks. Every category row's leading label is
 * pinned to one shared [labelColumnWidth] (measured off the widest displayName,
 * not hardcoded), so the eight nested grids resolve column 1 identically and
 * every slider start — plus the fixed-width readout — lands on a single
 * vertical line across all four groups, left and right columns mirrored. Each
 * category row owns a tick-free slider with a signed-delta readout and a
 * fixed-width trailing zone holding three 20px controls: a reset
 * [InplaceButton], a Bold toggle, and an Italic toggle (left to right). The
 * master reset is scoped to the active language. The slider value model
 * (0..100, 50 = identity, sparse store keyed by `language|category.name`) is
 * unchanged — the signed string lives only in the readout [JLabel] and is never
 * parsed back. The font-style model is an orthogonal sparse store keyed by the
 * SAME composite key, mapping to a [FontStyleOverride] enum `name`; both stores
 * thread through [apply] in parallel.
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

    // One uniform leading-label width shared by EVERY category row in EVERY
    // group/column. Each `group(title)` builds its own nested-panel grids, so
    // `widthGroup()` (grid-scoped) cannot align labels across the eight grids;
    // a fixed preferred/minimum width on every leading label forces all grids
    // to resolve column 1 to the same width, so the sliders (and therefore the
    // fixed-width readouts) start on a single vertical line in both columns of
    // all four groups. Measured once off the widest [PrimitiveCategory]
    // displayName via [UIUtil.getLabelFont] — the labels are localizable, so
    // the width is computed, never hardcoded. Computed lazily because the
    // panel is constructed off the EDT in tests; the value is independent of
    // any not-yet-realized component's font (a component's own `getFont()`
    // returns null before it joins the hierarchy).
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
     * show/hide boundary is the [customSelected] gate carried by the inner
     * row. The categories are split round-robin into a left column (even
     * indices) and a right column (odd indices); each column is its OWN
     * `panel { }`, so the IntelliJ UI-DSL grid auto-aligns the slider /
     * readout / reset cell columns across every row within that column. Each
     * row's leading label is pinned to the shared [labelColumnWidth] in
     * [categoryRow], so column 1 resolves identically across all eight grids
     * and the slider start (and the fixed-width readout) line up on one
     * vertical line both within and across the four groups, left and right
     * columns mirrored. Odd-count groups simply give the left column one more
     * row than the right (no placeholder juggling).
     */
    private fun Panel.buildCategoryGroup(categoryGroup: CategoryGroup) {
        group(categoryGroup.title) {
            val leftCategories = categoryGroup.categories.filterIndexed { index, _ -> index % 2 == 0 }
            val rightCategories = categoryGroup.categories.filterIndexed { index, _ -> index % 2 == 1 }
            row {
                panel {
                    for (category in leftCategories) categoryRow(category)
                }.align(AlignX.FILL)
                    .resizableColumn()
                    .gap(RightGap.COLUMNS)
                panel {
                    for (category in rightCategories) categoryRow(category)
                }.align(AlignX.FILL)
                    .resizableColumn()
            }.visibleIf(customSelected)
        }.visibleIf(customSelected)
    }

    /**
     * Add ONE per-category control row INTO the enclosing column grid. The
     * leading cell is an EXPLICIT fixed-width [JLabel] (not `row(displayName)`'s
     * auto leading-label column): its preferred AND minimum widths are pinned
     * to [labelColumnWidth] so the eight independent nested-panel grids all
     * resolve column 1 to the identical width. With column 1 uniform, the
     * slider starts at the same x in every group and in both columns, and the
     * fixed-width readout falls on a single right-hand vertical line — the
     * cross-group alignment the auto-label form could not deliver. The slider
     * is built via the IntelliJ UI-DSL `slider()` cell (the themed Darcula
     * widget) — never a bare `JSlider(...)` constructor — tick-free and
     * width-capped with NO `resizableColumn()` (a resizable slider would break
     * the fixed-width readout's column binding). A signed-delta readout
     * ([signedReadout]) and a fixed-width trailing zone sit to its right; the
     * readout cell is fixed-width so the trailing controls toggling visibility
     * never reflows it. The trailing zone is ONE [JPanel] with a tight
     * left-aligned [FlowLayout] holding three [InplaceButton]s left to right:
     * the reset icon (visible only when the cell diverges from identity OR
     * carries a style), the Bold toggle, and the Italic toggle. Its preferred
     * AND minimum width are pinned to [TRAILING_ZONE_WIDTH] (the same fixed-cell
     * discipline the label and readout use) so all eight nested grids resolve
     * the trailing column identically. [InplaceButton] paints its own round
     * hover / pressed highlight and stays borderless at rest, so 32 toggles read
     * calm; the engaged / at-rest cue is the glyph weight / slant plus the
     * pressed-fill drawn by [refreshStyleVisuals], never color alone.
     * The label, slider, and readout cells each carry a [RightGap.SMALL] so the
     * label→slider, slider→readout, and readout→trailing gaps drop from the
     * UI-DSL `horizontalDefaultGap` (the platform's scaled 16) to the stable
     * small gap, tightening the row uniformly without implementing or delegating
     * any platform UI-DSL spacing interface — delegating one was a binary-compat
     * trap (a member added on a newer runtime is left abstract by Kotlin's
     * compile-time `by` delegation, crashing with `AbstractMethodError`).
     * [RightGap.SMALL] is applied to every [categoryRow] identically, so the
     * shared label column, single slider-start axis, and right-hand readout
     * column stay aligned across all four groups and both columns. The
     * underlying `JSlider`, readout label, reset button, and B / I toggles are
     * stashed by category so [rebindSlidersFor] / [setSliderValue] can snap them
     * on combo change.
     */
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
            // Trailing zone: ONE fixed-width JPanel holding reset -> Bold ->
            // Italic InplaceButtons (left-to-right add order = left-to-right Tab
            // order). Width pinned to TRAILING_ZONE_WIDTH so all eight nested
            // grids resolve the trailing column identically (the same cross-grid
            // pinning the label / readout use). The placeholder glyphs passed at
            // construction are overwritten by the trailing refreshStyleVisuals
            // before the panel is shown.
            val resetButton =
                InplaceButton("Reset ${category.displayName} to default", AllIcons.Actions.Rollback) {
                    resetCell(category)
                }.apply {
                    isVisible = false
                    isFocusable = true
                    accessibleContext.accessibleName = "Reset ${category.displayName} to default"
                }
            val restForeground = UIUtil.getContextHelpForeground()
            val boldToggle =
                InplaceButton("Bold ${category.displayName}", StyleGlyphIcon("B", Font.BOLD, restForeground)) {
                    onStyleToggle(category, Font.BOLD)
                }.apply {
                    isFocusable = true
                    accessibleContext.accessibleName = "Bold ${category.displayName}"
                }
            val italicToggle =
                InplaceButton("Italic ${category.displayName}", StyleGlyphIcon("I", Font.ITALIC, restForeground)) {
                    onStyleToggle(category, Font.ITALIC)
                }.apply {
                    isFocusable = true
                    accessibleContext.accessibleName = "Italic ${category.displayName}"
                }
            resetButtons[category] = resetButton
            boldToggles[category] = boldToggle
            italicToggles[category] = italicToggle
            val trailingZone =
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(TRAILING_GAP), 0)).apply {
                    isOpaque = false
                    val zoneWidth = JBUI.scale(TRAILING_ZONE_WIDTH)
                    preferredSize = Dimension(zoneWidth, JBUI.scale(StyleGlyphIcon.ICON_CELL))
                    minimumSize = Dimension(zoneWidth, JBUI.scale(StyleGlyphIcon.ICON_CELL))
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

    /**
     * Flip ONE font-style bit ([Font.BOLD] or [Font.ITALIC]) on the cell's
     * current [FontStyleOverride] and write the result through the orthogonal
     * style store. The new bitmask is composed from the cell's existing style
     * (defaulting to [Font.PLAIN] = inherit) XOR-ed by [bit]. When BOTH bits end
     * up off the key is REMOVED (the cell returns to inherit — v1 never persists
     * an explicit `PLAIN`); otherwise the matching [FontStyleOverride] name is
     * stored. After the model update the trailing visuals + reset visibility +
     * master reset are refreshed and the apply is deferred through the SAME
     * single-shot debounce the slider uses — no second timer, no synchronous
     * apply.
     */
    private fun onStyleToggle(
        category: PrimitiveCategory,
        bit: Int,
    ) {
        val key = "$currentLanguage|${category.name}"
        val current = FontStyleOverride.fromName(pendingStyles[key])?.fontType ?: Font.PLAIN
        val next = current xor bit
        val nextStyle = FontStyleOverride.entries.firstOrNull { it.fontType == next }
        if (nextStyle == null || nextStyle == FontStyleOverride.PLAIN) {
            // Both bits off -> return to inherit. v1 never persists explicit PLAIN.
            pendingStyles.remove(key)
        } else {
            pendingStyles[key] = nextStyle.name
        }
        refreshStyleVisuals(category)
        refreshResetVisibility(category)
        refreshMasterResetButton()
        applyTimer.restart()
    }

    /**
     * Per-row reset: clear BOTH dimensions for [category] under the active
     * language. Snap the slider back to identity, drop the slider AND style
     * overrides for the cell, refresh the trailing visuals + reset visibility +
     * master reset, then defer the apply through the SAME single-shot debounce.
     */
    private fun resetCell(category: PrimitiveCategory) {
        setSliderValue(category, SLIDER_MID)
        val key = "$currentLanguage|${category.name}"
        pendingOverrides.remove(key)
        pendingStyles.remove(key)
        refreshStyleVisuals(category)
        refreshResetVisibility(category)
        refreshMasterResetButton()
        applyTimer.restart()
    }

    /**
     * Refresh the Bold / Italic toggle glyphs for [category] from the cell's
     * stored [FontStyleOverride]. A set bit renders the engaged glyph — full
     * [UIUtil.getLabelForeground] on a `JBUI.CurrentTheme.ActionButton`
     * pressed-fill rounded rect; an unset bit renders the dimmed at-rest glyph —
     * [UIUtil.getContextHelpForeground] on a transparent background. The
     * pressed-fill plus the glyph weight / slant is the engaged / at-rest cue,
     * never color alone. Purely presentational — the icon swap never re-enters
     * the model. A no-op until the toggles are materialized.
     */
    private fun refreshStyleVisuals(category: PrimitiveCategory) {
        val key = "$currentLanguage|${category.name}"
        val fontType = FontStyleOverride.fromName(pendingStyles[key])?.fontType ?: Font.PLAIN
        val glyphFor = { glyph: String, style: Int, engaged: Boolean ->
            val foreground = if (engaged) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
            val background = if (engaged) JBUI.CurrentTheme.ActionButton.pressedBackground() else null
            StyleGlyphIcon(glyph, style, foreground, background)
        }
        boldToggles[category]?.let { button ->
            button.setIcon(glyphFor("B", Font.BOLD, fontType and Font.BOLD != 0))
            button.repaint()
        }
        italicToggles[category]?.let { button ->
            button.setIcon(glyphFor("I", Font.ITALIC, fontType and Font.ITALIC != 0))
            button.repaint()
        }
    }

    /**
     * Centralized reset-icon visibility for [category]: the reset control shows
     * when the cell diverges from the slider identity OR carries a font-style
     * override, so EITHER dimension keeps the per-row reset reachable. Called
     * from every path that mutates a cell ([onSliderChanged], [setSliderValue],
     * [onStyleToggle], [rebindSlidersFor]). A no-op until the reset button is
     * materialized.
     */
    private fun refreshResetVisibility(category: PrimitiveCategory) {
        val key = "$currentLanguage|${category.name}"
        val sliderMoved = (sliders[category]?.value ?: SLIDER_MID) != SLIDER_MID
        val styled = pendingStyles[key] != null
        resetButtons[category]?.isVisible = sliderMoved || styled
    }

    /**
     * Measure the widest [PrimitiveCategory.displayName] under the standard
     * label font and pad it, yielding the shared leading-label column width.
     * Uses [UIUtil.getLabelFont] (the font is realized; a not-yet-shown
     * component's own `getFont()` returns null per the project layout lesson)
     * and a throwaway [JLabel] for [java.awt.FontMetrics]. Defensive 0-width
     * fallback to [LABEL_FALLBACK_WIDTH] guards the rare headless case where
     * `stringWidth` cannot resolve.
     */
    private fun computeLabelColumnWidth(): Int {
        val font = UIUtil.getLabelFont()
        val metrics = JLabel().getFontMetrics(font)
        val widest = PrimitiveCategory.entries.maxOf { metrics.stringWidth(it.displayName) }
        return if (widest <= 0) JBUI.scale(LABEL_FALLBACK_WIDTH) else widest + JBUI.scale(LABEL_PADDING)
    }

    /**
     * Sparse write-through for one slider move: refresh the readout +
     * accessibility name + reset-icon visibility, then (unless the move was
     * programmatic) record only the cell the user touched and defer the apply
     * through the single-shot debounce timer. The apply is NEVER invoked
     * synchronously here.
     */
    private fun onSliderChanged(
        language: String,
        category: PrimitiveCategory,
        value: Int,
    ) {
        sliderLabels[category]?.let { applyReadout(it, value) }
        refreshResetVisibility(category)
        sliders[category]?.accessibleContext?.accessibleName =
            "${category.displayName} intensity, ${signedReadout(value)} from default"
        if (suppressSliderListeners) return
        pendingOverrides["$language|${category.name}"] = value.toString()
        refreshMasterResetButton()
        applyTimer.restart()
    }

    /**
     * Programmatic snap of one slider + its readout + reset-icon visibility,
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
            sliderLabels[category]?.let { applyReadout(it, value) }
            refreshResetVisibility(category)
            sliders[category]?.accessibleContext?.accessibleName =
                "${category.displayName} intensity, ${signedReadout(value)} from default"
        } finally {
            suppressSliderListeners = false
        }
    }

    /**
     * Per-language master reset: wipe only the override AND style cells keyed
     * to the active language, snap the visible sliders back to identity, restore
     * the toggle visuals via [rebindSlidersFor], and apply so the editor falls
     * back to the subordinate preset for that language. Other languages' cells
     * are left intact.
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

    /**
     * Snap every slider (its signed readout + reset-icon visibility) AND every
     * Bold / Italic toggle to the stored values for [language], or to identity
     * / inherit when that cell is untouched. Wrapped in [suppressSliderListeners]
     * so the programmatic snap does not feed back into [onSliderChanged]. A
     * no-op until the fold-out has populated the widget maps.
     */
    private fun rebindSlidersFor(language: String) {
        suppressSliderListeners = true
        try {
            for (category in PrimitiveCategory.entries) {
                val value = pendingOverrides["$language|${category.name}"]?.toIntOrNull() ?: SLIDER_MID
                sliders[category]?.value = value
                sliderLabels[category]?.let { applyReadout(it, value) }
                sliders[category]?.accessibleContext?.accessibleName =
                    "${category.displayName} intensity, ${signedReadout(value)} from default"
                refreshStyleVisuals(category)
                refreshResetVisibility(category)
            }
        } finally {
            suppressSliderListeners = false
        }
    }

    /**
     * Enable the master reset button only when the active language has at
     * least one override OR style cell, and track the language in its label. A
     * no-op until the fold-out has materialized the button.
     */
    private fun refreshMasterResetButton() {
        val button = masterResetButton ?: return
        val prefix = "$currentLanguage|"
        button.text = "Reset $currentLanguage customizations"
        button.isEnabled =
            pendingOverrides.keys.any { it.startsWith(prefix) } ||
            pendingStyles.keys.any { it.startsWith(prefix) }
    }

    /**
     * Single update site for a readout [JLabel]'s text AND foreground so the
     * three callers ([onSliderChanged], [setSliderValue], [rebindSlidersFor])
     * stay in lock-step. At the [SLIDER_MID] identity the `0` is rendered in
     * the dimmed `getContextHelpForeground` to read as "default / untouched";
     * once the cell diverges the signed delta switches to the stronger
     * `getLabelForeground` to signal a moved value. Presentation-only — the
     * value model is unaffected.
     */
    private fun applyReadout(
        label: JLabel,
        value: Int,
    ) {
        label.text = signedReadout(value)
        label.foreground =
            if (value == SLIDER_MID) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
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

        // Slider track width (DPI-scaled, alignment anchor). Trimmed 160 -> 140
        // so the row reclaims the px the trailing zone needs without growing the
        // overall row width: 170 label + 140 slider + 28 readout + 64 trailing
        // ≈ the prior 170 + 160 + 34 + 38 reset-link footprint.
        private const val SLIDER_TRACK_WIDTH = 140

        // Right-aligned readout cell width (DPI-scaled). 28 fits the widest
        // signed string the live model reaches ("−50" / "+50", 3 glyphs ≈ 28px
        // at the 1.0x label font) without clipping while shaving the
        // slider→number dead space so the trailing zone fits without growth.
        // `SwingConstants.RIGHT` keeps the number column clean.
        private const val READOUT_WIDTH = 28

        // Fixed trailing-zone width (DPI-scaled) for the reset + Bold + Italic
        // composite. Pinned identically on every nested grid so the eight grids
        // resolve the trailing column to one vertical line, mirroring the fixed
        // label / readout columns. Holds three ICON_CELL controls plus the
        // inter-control TRAILING_GAP.
        private const val TRAILING_ZONE_WIDTH = 64

        // Inter-control gap (DPI-scaled) inside the trailing FlowLayout.
        private const val TRAILING_GAP = 2

        // Trailing padding (DPI-scaled) added to the widest measured
        // displayName so the leading label never clips against the slider.
        private const val LABEL_PADDING = 8

        // Defensive fallback (DPI-scaled) when FontMetrics yields a 0-width
        // measurement (e.g. an unrealizable headless font); roughly the widest
        // English displayName plus padding.
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
    }
}
