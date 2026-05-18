package dev.ayuislands.accent.toolbar

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import javax.swing.JComponent

/**
 * Builds the left-click popup for the Ayu Quick-Switcher chip. FREE block — variant
 * switcher row + 12-preset accent grid + Custom/More links — renders for every user.
 * PREMIUM block (related toggles + quick actions) is wrapped in two
 * `ComponentPredicate.fromValue(...)` gates driven by `LicenseChecker` per D-07 /
 * D-09: free users see a shorter popup, licensed users see the full surface. NO
 * locked-with-tooltip pattern — D-07 explicit rejection (locked by
 * `QuickSwitcherPremiumBlockGateTest`).
 *
 * Popup is built with the exact flag combination from RESEARCH §3 — that combo is locked
 * by the popup smoke test in `QuickSwitcherPopupTest` (Pitfall 4).
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
                group("Related toggles") {
                    row { cell(QuickSwitcherRelatedTogglesSection().component).align(AlignX.FILL) }
                }.visibleIf(ComponentPredicate.fromValue(LicenseChecker.isLicensedOrGrace()))

                group("Quick actions") {
                    row { cell(QuickSwitcherQuickActionsRow(anchor).component).align(AlignX.FILL) }
                }.visibleIf(ComponentPredicate.fromValue(LicenseChecker.isLicensedOrGrace()))
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
