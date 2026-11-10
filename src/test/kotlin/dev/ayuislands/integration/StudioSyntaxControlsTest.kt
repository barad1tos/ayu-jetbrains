package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.TitledSeparator
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.settings.SyntaxControlGrid
import dev.ayuislands.settings.SyntaxIntensityReadout
import dev.ayuislands.settings.SyntaxStyleControl
import dev.ayuislands.syntax.PrimitiveCategory
import org.junit.Rule
import org.junit.Test
import javax.swing.JLabel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Retains native control contracts previously exercised through the removed legacy panel adapter. */
class StudioSyntaxControlsTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `intensity readout retains signed values and distinguishes moved values`() =
        onHostEdt {
            assertEquals("0", SyntaxIntensityReadout.signed(50, 50))
            assertEquals("+25", SyntaxIntensityReadout.signed(75, 50))
            assertEquals("\u221220", SyntaxIntensityReadout.signed(30, 50))
            assertEquals("+50", SyntaxIntensityReadout.signed(100, 50))
            assertEquals("\u221250", SyntaxIntensityReadout.signed(0, 50))
            val identity = JLabel()
            val above = JLabel()
            val below = JLabel()

            SyntaxIntensityReadout.apply(identity, 50, 50)
            SyntaxIntensityReadout.apply(above, 75, 50)
            SyntaxIntensityReadout.apply(below, 30, 50)

            assertEquals("", identity.text)
            assertEquals("+25", above.text)
            assertEquals("\u221220", below.text)
            assertNotEquals(identity.foreground.rgb, above.foreground.rgb)
            assertNotEquals(identity.foreground.rgb, below.foreground.rgb)
        }

    @Test
    fun `native grid retains every category once and the familiar two column group order`() =
        onHostEdt {
            val controls = grid()
            val content = panel { controls.build(this) }
            val groups = hostDescendants(content).filterIsInstance<TitledSeparator>().map { it.text }

            assertEquals(listOf("Declarations", "Keywords & Docs", "Identifiers & Members", "Literals"), groups)
            assertEquals(PrimitiveCategory.entries.toSet(), controls.sliders.keys)
            assertEquals(PrimitiveCategory.entries.size, controls.categoryLabels.size)
            assertEquals(PrimitiveCategory.entries.size, controls.styleControls.size)
            assertEquals(PrimitiveCategory.entries.size, controls.resetButtons.size)
        }

    @Test
    fun `native grid keeps continuous slider ranges readable labels and compact paired controls`() =
        onHostEdt {
            val controls = grid()
            panel { controls.build(this) }
            val metrics = JLabel().getFontMetrics(UIUtil.getLabelFont())
            val widestLabel = PrimitiveCategory.entries.maxOf { metrics.stringWidth(it.displayName) }
            val widestReadout = maxOf(metrics.stringWidth("\u221250"), metrics.stringWidth("+50"))

            for (category in PrimitiveCategory.entries) {
                val slider = controls.sliders.getValue(category)
                assertFalse(slider.paintTicks)
                assertFalse(slider.paintLabels)
                assertFalse(slider.snapToTicks)
                assertEquals(0, slider.minimum)
                assertEquals(100, slider.maximum)
                assertEquals(JBUI.scale(140), slider.preferredSize.width)
                assertTrue(
                    controls.categoryLabels
                        .getValue(category)
                        .preferredSize.width >= widestLabel,
                )
                val readout = controls.sliderLabels.getValue(category)
                assertEquals(JBUI.scale(28), readout.preferredSize.width)
                assertTrue(readout.preferredSize.width >= widestReadout)
                val reset = controls.resetButtons.getValue(category)
                assertEquals(JBUI.scale(20), reset.parent.preferredSize.width)
                assertEquals(1, reset.parent.componentCount)
                assertEquals(
                    slider.parent,
                    controls.styleControls
                        .getValue(category)
                        .component.parent,
                )
                assertFalse(reset.isVisible)
            }
        }

    private fun grid() =
        SyntaxControlGrid(
            identityValue = 50,
            createStyleControl = { category -> SyntaxStyleControl(category, { "Kotlin" }, { null }, {}) },
            sliderChanged = { _, _ -> },
            sliderReleased = {},
            reset = {},
            updateReadout = { label, value -> SyntaxIntensityReadout.apply(label, value, 50) },
        )
}
