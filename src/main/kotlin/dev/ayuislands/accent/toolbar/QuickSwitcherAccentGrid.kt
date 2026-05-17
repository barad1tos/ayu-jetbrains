package dev.ayuislands.accent.toolbar

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentColor
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Slim 12-cell preset grid for the FREE Quick-Switcher popup. Constructs one [JButton]
 * per entry in [AYU_ACCENT_PRESETS] (the canonical 12-preset list at the top level of
 * `dev.ayuislands.accent.AccentColor`). Click flows through
 * [AccentApplicator.applyFromHexString] and — gated on the Boolean return per Pattern D —
 * publishes via [ProjectAccentSwapService.notifyExternalApply].
 *
 * Intentionally NOT a reuse of `AccentColorPanel` (RESEARCH §4 — its constructor takes
 * six Settings-lifecycle callbacks and exposes no popup entry point). Keeping the popup
 * grid as a slim peer component avoids dragging the Settings tab's state machine into
 * the toolbar widget.
 */
internal class QuickSwitcherAccentGrid {
    val component: JPanel =
        JPanel(GridLayout(GRID_ROWS, GRID_COLS, GAP, GAP)).apply {
            for (preset in AYU_ACCENT_PRESETS) {
                add(presetButton(preset))
            }
        }

    private fun presetButton(preset: AccentColor): JButton {
        val swatchPx = JBUI.scale(SWATCH_PX)
        val color = ColorUtil.fromHex(preset.hex)
        return JButton().apply {
            icon = ColorIcon(swatchPx, color)
            toolTipText = "${preset.name} — ${preset.hex}"
            isBorderPainted = false
            isContentAreaFilled = false
            addActionListener { applyPreset(preset.hex) }
        }
    }

    /**
     * Pattern D — gate [ProjectAccentSwapService.notifyExternalApply] on the Boolean return
     * of [AccentApplicator.applyFromHexString]. Pattern B — catch [RuntimeException] only;
     * never swallow a `Throwable` so JVM-fatal signals propagate.
     */
    private fun applyPreset(hex: String) {
        try {
            val applied = AccentApplicator.applyFromHexString(hex)
            if (applied) {
                ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
            } else {
                LOG.warn("Accent preset apply rejected hex=$hex")
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Accent preset apply failed hex=$hex", exception)
        }
    }

    private companion object {
        const val GRID_ROWS = 3
        const val GRID_COLS = 4
        const val GAP = 4
        const val SWATCH_PX = 16
        val LOG = logger<QuickSwitcherAccentGrid>()
    }
}
