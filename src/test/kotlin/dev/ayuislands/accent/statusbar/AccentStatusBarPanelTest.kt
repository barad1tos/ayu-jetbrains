package dev.ayuislands.accent.statusbar

import dev.ayuislands.accent.AccentResolutionChain
import dev.ayuislands.accent.AccentResolutionStep
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.StepOutcome
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccentStatusBarPanelTest {
    @Test
    fun `status text includes winning source and reason`() {
        val winner =
            AccentResolutionStep(
                source = AccentResolver.Source.PROJECT_OVERRIDE,
                hex = "#7DCFFF",
                outcome = StepOutcome.WON,
                detail = "Pinned accent for this project",
            )
        val panel = AccentStatusBarPanel {}

        panel.updateFromChain(AccentResolutionChain(listOf(winner), winner, verdict = null))

        assertEquals(
            "Project override · Pinned accent for this project",
            statusLabel(panel).text,
        )
    }

    @Test
    fun `tooltip is html multiline diagnostics`() {
        val blocked =
            AccentResolutionStep(
                source = AccentResolver.Source.LANGUAGE_OVERRIDE,
                hex = null,
                outcome = StepOutcome.NOT_DOMINANT,
                detail = "No dominant language: Kotlin 52%, Java 48%",
            )
        val winner =
            AccentResolutionStep(
                source = AccentResolver.Source.PROJECT_FALLBACK,
                hex = "#FFB454",
                outcome = StepOutcome.WON,
                detail = "Project fallback (polyglot project)",
            )
        val panel = AccentStatusBarPanel {}

        panel.updateFromChain(AccentResolutionChain(listOf(blocked, winner), winner, verdict = null))

        assertTrue(panel.toolTipText.startsWith("<html>"))
        assertTrue(panel.toolTipText.contains("<br>"))
        assertTrue(panel.toolTipText.contains("No dominant language"))
    }

    private fun statusLabel(panel: AccentStatusBarPanel): JLabel = panel.components.filterIsInstance<JLabel>().last()
}
