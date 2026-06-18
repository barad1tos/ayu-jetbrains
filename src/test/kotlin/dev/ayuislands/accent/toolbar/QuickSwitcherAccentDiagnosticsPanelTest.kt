package dev.ayuislands.accent.toolbar

import dev.ayuislands.accent.AccentResolutionChain
import dev.ayuislands.accent.AccentResolutionStep
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.StepOutcome
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickSwitcherAccentDiagnosticsPanelTest {
    @Test
    fun `collapsed diagnostics shows summary only and expand action`() {
        val panel = QuickSwitcherAccentDiagnosticsPanel(polyglotFallbackChain())

        val texts = panel.visibleTexts()

        assertTrue(texts.containsText("Project fallback", "polyglot project"))
        assertTrue(texts.contains("Show resolution chain..."))
        assertFalse(texts.any { it.contains("Project override") })
        assertFalse(texts.any { it.contains("Language override") })
    }

    @Test
    fun `collapsed diagnostics hides expand action for single-step winner`() {
        val panel = QuickSwitcherAccentDiagnosticsPanel(projectOverrideChain())

        val texts = panel.visibleTexts()

        assertTrue(
            texts.containsText(
                "Project override",
                "Pinned accent for this project",
            ),
        )
        assertFalse(texts.contains("Show resolution chain..."))
        assertFalse(texts.contains("Collapse"))
    }

    @Test
    fun `collapsed summary centers marker and text vertically`() {
        val panel = QuickSwitcherAccentDiagnosticsPanel(projectOverrideChain())

        panel.layoutForTest()
        val markerCell =
            panel
                .descendants()
                .first { component ->
                    component.name ==
                        QuickSwitcherAccentDiagnosticsPanel.MARKER_COLUMN_NAME
                }
        val summaryLabel =
            panel
                .descendants()
                .filterIsInstance<JLabel>()
                .first { label -> label.text.contains("Project override") }
        val markerCenter = markerCell.paintedContentCenterYIn(panel)
        val labelCenter = summaryLabel.paintedContentCenterYIn(panel)

        assertTrue(abs(markerCenter - labelCenter) <= 1)
    }

    @Test
    fun `expanded diagnostics shows supporting steps without repeating winner`() {
        val panel = QuickSwitcherAccentDiagnosticsPanel(polyglotFallbackChain())

        panel.clickAction("Show resolution chain...")
        val texts = panel.visibleTexts()

        assertTrue(texts.contains("Collapse"))
        assertTrue(texts.containsText("Project override", "not set"))
        assertTrue(texts.containsText("Language override", "Kotlin 52%, Java 48%"))
        assertTrue(texts.containsText("Project fallback", "polyglot project"))
        assertEquals(
            1,
            texts.countText(
                "Project fallback",
                "polyglot project",
            ),
        )
    }

    @Test
    fun `expanded diagnostics uses one fixed marker column for summary and chain rows`() {
        val panel = QuickSwitcherAccentDiagnosticsPanel(polyglotFallbackChain())

        panel.clickAction("Show resolution chain...")
        val markerColumnName = QuickSwitcherAccentDiagnosticsPanel.MARKER_COLUMN_NAME
        val markerCells = panel.descendants().filter { it.name == markerColumnName }
        val markerWidths = markerCells.map { it.preferredSize.width }.toSet()

        assertEquals(
            setOf(QuickSwitcherAccentDiagnosticsPanel.scaledMarkerColumnWidth()),
            markerWidths,
        )
    }

    private fun polyglotFallbackChain(): AccentResolutionChain {
        val steps =
            listOf(
                AccentResolutionStep(
                    source = AccentResolver.Source.PROJECT_OVERRIDE,
                    hex = null,
                    outcome = StepOutcome.NOT_SET,
                    detail = "not set",
                ),
                AccentResolutionStep(
                    source = AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE,
                    hex = null,
                    outcome = StepOutcome.NOT_SET,
                    detail = "not set",
                ),
                AccentResolutionStep(
                    source = AccentResolver.Source.LANGUAGE_OVERRIDE,
                    hex = null,
                    outcome = StepOutcome.NOT_DOMINANT,
                    detail = "Kotlin 52%, Java 48%",
                ),
                AccentResolutionStep(
                    source = AccentResolver.Source.PROJECT_FALLBACK,
                    hex = "#FFB454",
                    outcome = StepOutcome.WON,
                    detail = "polyglot project",
                ),
            )
        return AccentResolutionChain(steps = steps, winner = steps.last(), verdict = null)
    }

    private fun projectOverrideChain(): AccentResolutionChain {
        val step =
            AccentResolutionStep(
                source = AccentResolver.Source.PROJECT_OVERRIDE,
                hex = "#5CCFE6",
                outcome = StepOutcome.WON,
                detail = "Pinned accent for this project",
            )
        return AccentResolutionChain(steps = listOf(step), winner = step, verdict = null)
    }

    private fun Component.layoutForTest() {
        size = preferredSize
        layoutTree()
    }

    private fun Component.layoutTree() {
        doLayout()
        if (this is Container) {
            components.forEach { component -> component.layoutTree() }
        }
    }

    private fun Component.paintedContentCenterYIn(root: Component): Int {
        val image =
            BufferedImage(
                width.coerceAtLeast(1),
                height.coerceAtLeast(1),
                BufferedImage.TYPE_INT_ARGB,
            )
        val graphics = image.createGraphics()
        paint(graphics)
        graphics.dispose()

        val paintedRows =
            (0 until image.height).filter { y ->
                (0 until image.width).any { x ->
                    val alpha = image.getRGB(x, y) ushr ALPHA_SHIFT
                    alpha != 0
                }
            }
        require(paintedRows.isNotEmpty()) {
            "Expected ${javaClass.simpleName} to paint visible content"
        }

        val parent = requireNotNull(parent)
        val location = SwingUtilities.convertPoint(parent, x, y, root)
        return location.y + (paintedRows.first() + paintedRows.last()) / 2
    }

    private fun Component.visibleTexts(): List<String> =
        descendants()
            .filter { it.isVisible }
            .mapNotNull { component ->
                when (component) {
                    is AbstractButton -> component.text
                    is JLabel -> component.text
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }.toList()

    private fun List<String>.containsText(
        first: String,
        second: String,
    ): Boolean =
        any { text ->
            text.contains(first) && text.contains(second)
        }

    private fun List<String>.countText(
        first: String,
        second: String,
    ): Int =
        count { text ->
            text.contains(first) && text.contains(second)
        }

    private fun Component.clickAction(text: String) {
        descendants()
            .filterIsInstance<AbstractButton>()
            .first { it.text == text }
            .doClick()
    }

    private fun Component.descendants(): Sequence<Component> =
        sequence {
            yield(this@descendants)
            if (this@descendants is Container) {
                components.forEach { yieldAll(it.descendants()) }
            }
        }

    private companion object {
        const val ALPHA_SHIFT = 24
    }
}
