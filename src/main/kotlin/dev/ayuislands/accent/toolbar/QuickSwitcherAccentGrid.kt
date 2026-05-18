package dev.ayuislands.accent.toolbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.PopupSwatch
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Accent grid inside the quick-switcher popup: a 2-row × 6-column grid of
 * 36×24 [PopupSwatch] cells, plus a "Custom… / More…" link row underneath
 * driven by the bundled `pipette.svg` icon.
 *
 * Each swatch click flows through [AccentApplicator.applyFromHexString] and —
 * gated on the Boolean return per Pattern D — publishes via
 * [ProjectAccentSwapService]. Pattern B catches transient [RuntimeException]
 * so a one-off resolver hiccup does not crash the popup.
 *
 * Selection state mirrors the currently-resolved accent at construction;
 * later applies re-stamp every swatch's `selected` flag so the visual matches
 * reality.
 */
internal class QuickSwitcherAccentGrid {
    private val swatches: List<PopupSwatch>
    val component: JComponent

    init {
        val resolvedHex = resolveCurrentAccent()
        swatches =
            AYU_ACCENT_PRESETS.map { preset ->
                PopupSwatch(
                    hex = preset.hex,
                    isSelected = preset.hex.equals(resolvedHex, ignoreCase = true),
                    onClick = { applyPreset(it) },
                )
            }
        val grid =
            JPanel(
                GridLayout(
                    GRID_ROWS,
                    GRID_COLS,
                    JBUI.scale(Density.SWATCH_GAP),
                    JBUI.scale(Density.SWATCH_GAP),
                ),
            ).apply {
                isOpaque = false
                swatches.forEach { add(it) }
            }
        val pipetteIcon = IconLoader.getIcon(PIPETTE_ICON_PATH, QuickSwitcherAccentGrid::class.java)
        val customRow =
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(CUSTOM_ROW_GAP), 0)).apply {
                isOpaque = false
                add(iconLink("Custom…", pipetteIcon) { openAyuSettings() })
                add(iconLink("More…", AllIcons.Actions.More) { openAyuSettings() })
            }
        component =
            JPanel(BorderLayout(0, JBUI.scale(CUSTOM_ROW_TOP_PAD))).apply {
                isOpaque = false
                add(grid, BorderLayout.NORTH)
                add(customRow, BorderLayout.SOUTH)
            }
    }

    private fun iconLink(
        text: String,
        icon: Icon,
        onClick: () -> Unit,
    ): JComponent {
        // `ActionLink` is the Kotlin UI DSL `link(...)` factory's underlying type;
        // we compose it next to a leading icon label to honour spec §3.4.
        val link = ActionLink(text) { onClick() }
        val wrapper =
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(ICON_LINK_GAP), 0)).apply {
                isOpaque = false
                add(JLabel(icon))
                add(link)
            }
        return wrapper
    }

    private fun resolveCurrentAccent(): String {
        val variant = AyuVariant.detect() ?: return AYU_ACCENT_PRESETS.first().hex
        val project = AccentApplicator.resolveFocusedProject()
        return try {
            AccentResolver.resolve(project, variant)
        } catch (exception: RuntimeException) {
            LOG.warn("Accent resolve failed for grid construction", exception)
            AYU_ACCENT_PRESETS.first().hex
        }
    }

    private fun applyPreset(hex: String) {
        try {
            val applied = AccentApplicator.applyFromHexString(hex)
            if (applied) {
                ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
                swatches.forEach { swatch -> swatch.setSelected(swatch.hex.equals(hex, ignoreCase = true)) }
            } else {
                LOG.warn("Accent preset apply rejected hex=$hex")
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Accent preset apply failed hex=$hex", exception)
        }
    }

    private fun openAyuSettings() {
        val project = AccentApplicator.resolveFocusedProject()
        // Pattern B — [ShowSettingsUtil.showSettingsDialog] throws
        // [IllegalArgumentException] when the configurable id cannot be resolved
        // (plugin reload mid-flight, malformed plugin.xml) and the platform's
        // `ProcessCanceledException` (a [RuntimeException] subclass on 2025.1+)
        // when the user dismisses the dialog mid-build. Neither is a programming
        // error worth bubbling — the link handler stays clickable for the next
        // attempt.
        try {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ayu Islands")
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to open Ayu Islands settings dialog", exception)
        }
    }

    private companion object {
        const val GRID_ROWS = 2
        const val GRID_COLS = 6
        const val CUSTOM_ROW_GAP = 12
        const val CUSTOM_ROW_TOP_PAD = 4
        const val ICON_LINK_GAP = 4
        const val PIPETTE_ICON_PATH = "/icons/pipette.svg"
        val LOG = logger<QuickSwitcherAccentGrid>()
    }
}
