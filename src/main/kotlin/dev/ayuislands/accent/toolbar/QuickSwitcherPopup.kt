package dev.ayuislands.accent.toolbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentDefaults
import dev.ayuislands.accent.AccentResolutionChain
import dev.ayuislands.accent.AccentResolutionStep
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.StepOutcome
import dev.ayuislands.accent.toolbar.popup.AccentStripe
import dev.ayuislands.accent.toolbar.popup.BlockSeparator
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.SectionCard
import dev.ayuislands.licensing.LicenseChecker
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Builds the left-click popup for the Ayu Quick-Switcher chip. The container
 * is a vertical stack of custom [SectionCard] primitives — Variant, Accent,
 * Toggles, and Quick Actions — prefaced by a 2-px [AccentStripe] on the top
 * edge that paints the current resolved accent.
 *
 * Free mode keeps Follow System Accent and Copy Hex visible, and replaces the
 * premium controls with one explanatory card. Open popups are closed when
 * entitlement changes, so they rebuild from current state.
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
    private val openPopups = mutableSetOf<JBPopup>()

    fun closeOpenPopups() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val popups = openPopups.toList()
        openPopups.clear()
        popups.forEach(JBPopup::cancel)
    }

    @JvmOverloads
    fun show(
        anchor: JComponent,
        chip: QuickSwitcherChipComponent? = null,
    ) {
        val context = AccentContext.detectQuickSwitcher() ?: return
        val focusedProject = AccentApplicator.resolveFocusedProject()

        val accentGrid = QuickSwitcherAccentGrid()
        val diagnosticsPanel =
            QuickSwitcherAccentDiagnosticsPanel(
                resolveCurrentAccentChain(focusedProject, context),
            )
        val isLicensed = LicenseChecker.isLicensedOrGrace()

        val variantCard =
            when (context) {
                is AccentContext.Ayu ->
                    SectionCard("Variant").apply {
                        setContent(VariantSwitcherRow(context.ayuVariant).component)
                    }
                AccentContext.External -> null
            }
        val accentCard =
            SectionCard("Accent").apply {
                setContent(buildAccentContent(accentGrid.component, diagnosticsPanel))
            }
        val togglesCard =
            SectionCard("Toggles").apply {
                setContent(
                    QuickSwitcherRelatedTogglesSection(
                        showPremiumToggles = isLicensed,
                    ).component,
                )
            }
        val actionsCard =
            SectionCard("Actions").apply {
                setContent(
                    QuickSwitcherQuickActionsRow(
                        anchor = anchor,
                        context = context,
                        showPremiumActions = isLicensed,
                    ).component,
                )
            }
        val premiumCard =
            if (isLicensed) {
                null
            } else {
                SectionCard("Premium").apply {
                    setContent(buildPremiumContent())
                }
            }

        val stripe = AccentStripe { resolveCurrentAccentHex(context) }

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
                row { cell(togglesCard).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                row { cell(actionsCard).align(AlignX.FILL) }
                    .topGap(TopGap.NONE)
                    .bottomGap(BottomGap.NONE)
                if (premiumCard != null) {
                    row { cell(BlockSeparator()).align(AlignX.FILL) }
                        .topGap(TopGap.NONE)
                        .bottomGap(BottomGap.NONE)
                    row { cell(premiumCard).align(AlignX.FILL) }
                        .topGap(TopGap.NONE)
                        .bottomGap(BottomGap.NONE)
                }
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

        openPopups.add(popup)
        popup.addListener(
            object : JBPopupListener {
                override fun beforeShown(event: LightweightWindowEvent) {
                    if (chip != null) {
                        SwingUtilities.invokeLater { chip.setPopupAttached(true) }
                    }
                }

                override fun onClosed(event: LightweightWindowEvent) {
                    openPopups.remove(popup)
                    if (chip != null) {
                        SwingUtilities.invokeLater { chip.setPopupAttached(false) }
                    }
                }
            },
        )

        popup.showUnderneathOf(anchor)
    }

    private fun resolveCurrentAccentHex(context: AccentContext): String =
        try {
            AccentResolver.resolve(AccentApplicator.resolveFocusedProject(), context)
        } catch (exception: RuntimeException) {
            LOG.warn("AccentStripe resolve failed", exception)
            DEFAULT_ACCENT_FALLBACK
        }

    private fun resolveCurrentAccentChain(
        project: Project?,
        context: AccentContext,
    ): AccentResolutionChain =
        try {
            AccentResolver.resolveChain(project, context)
        } catch (exception: RuntimeException) {
            LOG.warn("Accent diagnostics resolve failed", exception)
            fallbackChain()
        }

    private fun fallbackChain(): AccentResolutionChain {
        val winner =
            AccentResolutionStep(
                source = AccentResolver.Source.GLOBAL,
                hex = DEFAULT_ACCENT_FALLBACK,
                outcome = StepOutcome.WON,
                detail = "Global fallback",
            )
        return AccentResolutionChain(
            steps = listOf(winner),
            winner = winner,
            verdict = null,
        )
    }

    private fun buildAccentContent(
        accentGrid: JComponent,
        diagnosticsPanel: JComponent,
    ): JPanel =
        JPanel(BorderLayout(0, JBUI.scale(Density.CARD_GAP))).apply {
            isOpaque = false
            add(accentGrid, BorderLayout.NORTH)
            add(diagnosticsPanel, BorderLayout.CENTER)
        }

    private fun buildPremiumContent(): JComponent =
        panel {
            row {
                label(
                    "Chrome tinting, Glow, Accent rotation, Pin, Random, " +
                        "Lighter, and Darker are available with Premium.",
                )
            }
            row {
                link("Learn about Premium") {
                    LicenseChecker.requestLicense("Learn about Quick Switcher premium controls")
                }
            }
        }

    private const val DEFAULT_ACCENT_FALLBACK: String = AccentDefaults.MIRAGE_HEX
}
