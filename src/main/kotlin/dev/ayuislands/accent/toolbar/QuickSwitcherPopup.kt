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
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentDefaults
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.toolbar.popup.AccentStripe
import dev.ayuislands.accent.toolbar.popup.BlockSeparator
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.SectionCard
import dev.ayuislands.licensing.LicenseChecker
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Builds the left-click popup for the Ayu Quick-Switcher chip. The container
 * is a vertical stack of custom [SectionCard] primitives — Variant + Accent in
 * the FREE block, Toggles + Quick Actions in the PREMIUM block — prefaced by a
 * 2-px [AccentStripe] on the top edge that paints the current resolved accent.
 * A [BlockSeparator] hairline sits between the FREE and PREMIUM blocks.
 *
 * Premium rows are wrapped in `visibleIf` gates driven by [LicenseChecker]
 * (Pattern J). Three gates share one live predicate so they cannot drift
 * across paint cycles (toggles SectionCard + actions SectionCard + the
 * separator above them). The locked-with-tooltip pattern is intentionally NOT
 * used — premium rows simply disappear when the license is invalid (locked by
 * `QuickSwitcherPremiumBlockGateTest`).
 *
 * Popup is built with the exact six-flag combination locked by
 * `QuickSwitcherPopupTest`. The opened popup notifies the chip via a per-popup
 * [JBPopupListener] so the chip can paint its popup-attached focused ring; the
 * listener auto-disposes with the popup (Pattern E — never attached to the
 * chip's own lifetime).
 *
 * Belt-and-braces: if [AccentContext.detect] returns `null` (LAF flipped between
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
        val context = AccentContext.detectQuickSwitcher() ?: return

        val accentGrid = QuickSwitcherAccentGrid()
        val togglesSection = QuickSwitcherRelatedTogglesSection()
        val actionsRow = QuickSwitcherQuickActionsRow(anchor, context)

        val variantCard =
            when (context) {
                is AccentContext.Ayu ->
                    SectionCard("Variant").apply { setContent(VariantSwitcherRow(context.ayuVariant).component) }
                AccentContext.External -> null
            }
        val accentCard = SectionCard("Accent").apply { setContent(accentGrid.component) }
        val togglesCard = SectionCard("Toggles").apply { setContent(togglesSection.component) }
        val actionsCard = SectionCard("Actions").apply { setContent(actionsRow.component) }

        val stripe = AccentStripe { resolveCurrentAccentHex(context) }

        val licenseGate = licenseGate()
        val content =
            panel {
                row { cell(stripe).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                if (variantCard != null) {
                    row { cell(variantCard).align(AlignX.FILL) }
                        .topGap(TopGap.NONE)
                        .bottomGap(BottomGap.NONE)
                }
                row { cell(accentCard).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                row { cell(BlockSeparator()).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                    .visibleIf(licenseGate)
                row { cell(togglesCard).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                    .visibleIf(licenseGate)
                row { cell(actionsCard).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                    .visibleIf(licenseGate)
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

    private fun resolveCurrentAccentHex(context: AccentContext): String =
        try {
            AccentResolver.resolve(AccentApplicator.resolveFocusedProject(), context)
        } catch (exception: RuntimeException) {
            LOG.warn("AccentStripe resolve failed", exception)
            DEFAULT_ACCENT_FALLBACK
        }

    /**
     * Live license predicate — re-evaluates [LicenseChecker.isLicensedOrGrace]
     * per `invoke()`. Replaces the original `ComponentPredicate.fromValue(...)`
     * snapshot, which captured the license state at panel-build time and went
     * stale when the trial expired (or a license was activated) while the popup
     * was open.
     *
     * All three premium gates (separator + toggles card + actions card) share
     * one instance so they cannot drift; if one card hides on trial expiry,
     * the other two AND the separator hide in the same paint pass.
     *
     * `addListener` is intentionally a no-op — the popup is short-lived (closes
     * on outside click), there is no model whose change would fan out to the
     * predicate's subscribers, and the predicate is re-asked by the DSL on each
     * `update` tick anyway. Wiring a real listener here would risk a leaked
     * subscription against [LicenseChecker]'s global state.
     */
    private fun licenseGate(): ComponentPredicate =
        object : ComponentPredicate() {
            override fun invoke(): Boolean = LicenseChecker.isLicensedOrGrace()

            override fun addListener(listener: (Boolean) -> Unit) = Unit
        }

    private const val DEFAULT_ACCENT_FALLBACK: String = AccentDefaults.MIRAGE_HEX
}
