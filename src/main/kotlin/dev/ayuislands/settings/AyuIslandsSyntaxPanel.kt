package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.StyleAxis
import dev.ayuislands.syntax.SyntaxModeService
import dev.ayuislands.syntax.SyntaxModeState
import dev.ayuislands.syntax.SyntaxMood
import javax.swing.JCheckBox
import javax.swing.JRadioButton

/**
 * Phase 49 Settings tab (Plan 49-03). Renders the mood radio + axis checkboxes
 * + a `browserLink` pointer to the built-in `Settings → Editor → Color Scheme`
 * editor for per-key tuning.
 *
 * Free feature per D-01 / SYNTAX-08 — no license-gating call appears in this
 * file (regression-locked by a Pattern L source-regex test in
 * `AyuIslandsSyntaxPanelTest`).
 *
 * Dirty-buffer pattern mirrors [AyuIslandsElementsPanel]: `pending*` is the UI
 * draft, `stored*` is the persisted truth; `isModified()` compares the two,
 * `apply()` writes pending → service → persisted, `reset()` reverts pending to
 * stored.
 *
 * Apply-before-persist ordering follows Anti-Pattern #4 (Phase 40.4 lesson):
 * [SyntaxModeService.apply] runs FIRST so any failure surfaces before the
 * state is mutated; persist runs SECOND so the next [SyntaxModeService]
 * `reapplyForActiveLaf` call sees a consistent (mood, axes) pair.
 */
class AyuIslandsSyntaxPanel : AyuIslandsSettingsPanel {
    private var pendingMood: SyntaxMood = SyntaxMood.MAXIMUM
    private var storedMood: SyntaxMood = SyntaxMood.MAXIMUM
    private val pendingAxes: MutableSet<StyleAxis> = mutableSetOf()
    private var storedAxes: Set<StyleAxis> = emptySet()
    private val axisCheckboxes: MutableMap<StyleAxis, JCheckBox> = mutableMapOf()
    private val moodRadios: MutableMap<SyntaxMood, JRadioButton> = mutableMapOf()

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        loadStateIntoPending()
        with(panel) {
            group("Syntax intensity") {
                buttonsGroup {
                    SyntaxMood.entries.forEach { mood ->
                        row {
                            radioButton("${mood.displayName} -- ~${mood.approximateKeyCount} keys")
                                .applyToComponent {
                                    isSelected = (mood == pendingMood)
                                    addActionListener { pendingMood = mood }
                                    moodRadios[mood] = this
                                }
                        }
                    }
                }
            }
            group("Style modifiers") {
                StyleAxis.entries.forEach { axis ->
                    row {
                        checkBox(axis.displayName)
                            .comment(axis.description)
                            .applyToComponent {
                                isSelected = (axis in pendingAxes)
                                addActionListener {
                                    if (isSelected) pendingAxes.add(axis) else pendingAxes.remove(axis)
                                }
                                axisCheckboxes[axis] = this
                            }
                    }
                }
            }
            row {
                browserLink(
                    "Settings -> Editor -> Color Scheme",
                    "https://www.jetbrains.com/help/idea/configuring-colors-and-fonts.html",
                )
            }
            row {
                comment(
                    "Per-key tuning lives in the built-in editor. This tab applies curated " +
                        "profiles + cross-cutting axes; the editor handles single-key precision.",
                )
            }
        }
    }

    override fun isModified(): Boolean = pendingMood != storedMood || pendingAxes != storedAxes

    override fun apply() {
        if (!isModified()) return
        // Apply FIRST, persist SECOND (Anti-Pattern #4 / Phase 40.4 lesson).
        SyntaxModeService.getInstance().apply(pendingMood, pendingAxes.toSet())
        val state = SyntaxModeState.getInstance().state
        state.mood = pendingMood.name
        state.axes.clear()
        state.axes.addAll(pendingAxes.map { it.name })
        storedMood = pendingMood
        storedAxes = pendingAxes.toSet()
    }

    override fun reset() {
        // Null-guard (warning #8 fix): if reset() runs before buildPanel() (a
        // deviated Configurable lifecycle), the radio/checkbox maps are empty
        // and there is no UI to refresh. Return early instead of silently
        // no-opping the iteration; explicit guard documents the invariant.
        if (moodRadios.isEmpty()) return
        pendingMood = storedMood
        pendingAxes.clear()
        pendingAxes.addAll(storedAxes)
        moodRadios.forEach { (mood, radio) -> radio.isSelected = (mood == storedMood) }
        axisCheckboxes.forEach { (axis, cb) -> cb.isSelected = (axis in storedAxes) }
    }

    private fun loadStateIntoPending() {
        val state = SyntaxModeState.getInstance().state
        storedMood = SyntaxMood.fromName(state.mood)
        pendingMood = storedMood
        storedAxes =
            state.axes
                .mapNotNullTo(mutableSetOf()) { runCatching { StyleAxis.valueOf(it) }.getOrNull() }
                .toSet()
        pendingAxes.clear()
        pendingAxes.addAll(storedAxes)
    }
}
