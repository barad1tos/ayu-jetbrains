package dev.ayuislands.accent.toolbar

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import javax.swing.JComponent

/**
 * Builds the left-click popup for the Ayu Quick-Switcher chip. FREE block only — variant
 * switcher row + 12-preset accent grid + Custom/More links. The PREMIUM block (related
 * toggles + quick actions) is a placeholder comment owned by Wave 4 (Plan 48-04), which
 * will wrap the new group in `.visibleIf { LicenseChecker.isLicensedOrGrace() }` per D-09.
 *
 * Popup is built with the exact flag combination from RESEARCH §3 — that combo is locked
 * by the popup smoke test in [QuickSwitcherPopupTest] (Pitfall 4).
 *
 * Belt-and-braces: if [AyuVariant.detect] returns `null` (LAF flipped between the chip's
 * BGT update tick and the click landing), early-return without building the popup so the
 * user does not see a half-built panel against a non-Ayu theme.
 */
internal object QuickSwitcherPopup {
    fun show(anchor: JComponent) {
        val variant = AyuVariant.detect() ?: return // WIDGET-11 belt-and-braces
        val variantRow = VariantSwitcherRow(variant)
        val accentGrid = QuickSwitcherAccentGrid()
        val content =
            panel {
                group("Variant") {
                    row { cell(variantRow.component) }
                }
                group("Accent") {
                    row { cell(accentGrid.component) }
                    row {
                        link("Custom…") {
                            // TODO Plan 48-04 may surface this via EditAccentColorDialog; for
                            // now open the Settings page where the user can edit accent.
                            openAyuSettings()
                        }
                        link("More…") { openAyuSettings() }
                    }
                }
                // PREMIUM block — Wave 4 (Plan 48-04) inserts the related toggles + quick
                // actions group here, wrapped in `.visibleIf { LicenseChecker.isLicensedOrGrace() }`
                // per D-09.
            }
        val popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(content, content)
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setCancelOnWindowDeactivation(false)
                .setMovable(false)
                .setResizable(false)
                .setCancelKeyEnabled(true)
                .createPopup()
        popup.showUnderneathOf(anchor)
    }

    private fun openAyuSettings() {
        val project = AccentApplicator.resolveFocusedProject()
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ayu Islands")
    }
}
