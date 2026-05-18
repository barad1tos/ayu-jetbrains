package dev.ayuislands.accent.toolbar

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentDefaults
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.popup.AccentStripe
import dev.ayuislands.accent.toolbar.popup.BlockSeparator
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.SectionCard
import dev.ayuislands.licensing.LicenseChecker
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Builds the left-click popup for the Ayu Quick-Switcher chip per the Wave-7
 * redesign (48-REDESIGN-SPEC §2). The container is a vertical stack of custom
 * [SectionCard] primitives — Variant + Accent in the FREE block, Toggles + Quick
 * Actions in the PREMIUM block — prefaced by a 2-px [AccentStripe] on the top
 * edge that paints the current resolved accent (Locked Answer #2). A
 * [BlockSeparator] hairline sits between the FREE and PREMIUM blocks.
 *
 * Premium rows are wrapped in `visibleIf` gates driven by [LicenseChecker]
 * per D-07 / D-09 / Pattern J. Three gates fire on the same predicate (toggles
 * SectionCard + actions SectionCard + the separator above them). The locked-with-
 * tooltip pattern is NOT used — D-07 explicit rejection (locked by
 * QuickSwitcherPremiumBlockGateTest Test 21).
 *
 * Popup is built with the exact six-flag combination locked by
 * QuickSwitcherPopupTest (Pitfall 4). The opened popup notifies the chip via a
 * per-popup [JBPopupListener] so the chip can paint its popup-attached focused
 * ring; the listener auto-disposes with the popup (Pattern E — never attached
 * to the chip's own Disposable).
 *
 * Belt-and-braces: if `AyuVariant.detect()` returns `null` (LAF flipped between
 * the chip's BGT update tick and the click landing), early-return without
 * building the popup so the user does not see a half-built panel against a
 * non-Ayu theme.
 */
internal object QuickSwitcherPopup {
    private val LOG = logger<QuickSwitcherPopup>()

    @JvmOverloads
    fun show(
        anchor: JComponent,
        chip: QuickSwitcherChipComponent? = null,
    ) {
        val variant = AyuVariant.detect() ?: return // WIDGET-11 belt-and-braces

        val variantRow = VariantSwitcherRow(variant)
        val accentGrid = QuickSwitcherAccentGrid()
        val togglesSection = QuickSwitcherRelatedTogglesSection()
        val actionsRow = QuickSwitcherQuickActionsRow(anchor)

        val variantCard = SectionCard("Variant").apply { setContent(variantRow.component) }
        val accentCard = SectionCard("Accent").apply { setContent(accentGrid.component) }
        val togglesCard = SectionCard("Toggles").apply { setContent(togglesSection.component) }
        val actionsCard = SectionCard("Actions").apply { setContent(actionsRow.component) }

        val stripe = AccentStripe { resolveCurrentAccentHex(variant) }

        val content =
            panel {
                row { cell(stripe).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                row { cell(variantCard).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                row { cell(accentCard).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                row { cell(BlockSeparator()).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                    .visibleIf(ComponentPredicate.fromValue(LicenseChecker.isLicensedOrGrace()))
                row { cell(togglesCard).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                    .visibleIf(ComponentPredicate.fromValue(LicenseChecker.isLicensedOrGrace()))
                row { cell(actionsCard).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                    .visibleIf(ComponentPredicate.fromValue(LicenseChecker.isLicensedOrGrace()))
            }.apply {
                border = JBUI.Borders.empty(JBUI.scale(Density.POPUP_PAD))
            }

        val popup: JBPopup =
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

        if (chip != null) {
            popup.addListener(
                object : JBPopupListener {
                    override fun beforeShown(event: LightweightWindowEvent) {
                        SwingUtilities.invokeLater { chip.setPopupAttached(true) }
                    }

                    override fun onClosed(event: LightweightWindowEvent) {
                        SwingUtilities.invokeLater { chip.setPopupAttached(false) }
                    }
                },
            )
        }

        popup.showUnderneathOf(anchor)
    }

    private fun resolveCurrentAccentHex(variant: AyuVariant): String =
        try {
            AccentResolver.resolve(AccentApplicator.resolveFocusedProject(), variant)
        } catch (exception: RuntimeException) {
            LOG.warn("AccentStripe resolve failed", exception)
            DEFAULT_ACCENT_FALLBACK
        }

    private const val DEFAULT_ACCENT_FALLBACK: String = AccentDefaults.MIRAGE_HEX
}
