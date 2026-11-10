package dev.ayuislands.studio.ui

import org.junit.Test
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioResponsiveLayoutTest {
    @Test
    fun `narrow content stacks controls below intact labels at every supported scale`(): Unit =
        onEdt {
            for (scale in scales) {
                val fixture = fixture(scale)
                resize(fixture.panel, pixels(200, scale))

                for ((label, control) in fixture.fields) {
                    assertEquals(label.x, control.x)
                    assertTrue(control.y >= label.y + label.height + pixels(6, scale))
                }
                assertRowOrder(fixture.fields)
                assertContained(fixture)
            }
        }

    @Test
    fun `default content shares one label column without stretching native controls`(): Unit =
        onEdt {
            for (scale in scales) {
                val fixture = fixture(scale)
                resize(fixture.panel, pixels(400, scale))

                val firstControl = fixture.fields.first().control
                for ((label, control) in fixture.fields) {
                    assertEquals(firstControl.x, control.x)
                    assertTrue(control.x >= label.x + label.width + pixels(6, scale))
                    assertEquals(control.preferredSize.width, control.width)
                    assertTrue(label.bounds.intersects(Rectangle(0, control.y, 1_000, control.height)))
                }
                assertRowOrder(fixture.fields)
                assertContained(fixture)
            }
        }

    @Test
    fun `wide content aligns both columns while retaining row major setting order`(): Unit =
        onEdt {
            for (scale in scales) {
                val fixture = fixture(scale)
                resize(fixture.panel, pixels(800, scale))

                val (first, second) = fixture.fields
                val (third, fourth) = fixture.fields.drop(2)
                assertEquals(first.label.y, second.label.y)
                assertEquals(first.control.y, second.control.y)
                assertEquals(third.label.y, fourth.label.y)
                assertEquals(first.control.x, third.control.x)
                assertEquals(second.control.x, fourth.control.x)
                assertTrue(second.label.x >= first.control.x + first.control.width + pixels(18, scale))
                assertTrue(third.label.y > first.control.y + first.control.height)
                assertEquals(fixture.fields.flatMap { listOf(it.label, it.control) }, fixture.panel.components.toList())
                assertContained(fixture)
            }
        }

    @Test
    fun `a setting moves with its control when the adjacent pair no longer fits`(): Unit =
        onEdt {
            val fixture = fixture(1f)
            resize(fixture.panel, 800)
            val first = fixture.fields[0]
            val second = fixture.fields[1]
            assertEquals(first.control.y, second.control.y)

            resize(fixture.panel, 400)

            val firstBottom = maxOf(first.label.y + first.label.height, first.control.y + first.control.height)
            assertTrue(second.label.y >= firstBottom + 14)
            assertTrue(second.control.y >= firstBottom + 14)
            assertEquals(first.label.x, second.label.x)
            assertEquals(first.control.x, second.control.x)
            assertContained(fixture)
        }

    @Test
    fun `minimum width follows the longest native label and remeasures changed typography`(): Unit =
        onEdt {
            val label = JLabel("Sync editor color scheme with active Ayu theme")
            val control = JComboBox(arrayOf("Enabled", "Disabled"))
            control.minimumSize = Dimension(80, control.minimumSize.height)
            val panel = JPanel(StudioResponsiveLayout())
            panel.border = BorderFactory.createEmptyBorder(7, 11, 9, 13)
            panel.add(label)
            panel.add(control)
            val firstMinimum = panel.minimumSize
            assertTrue(firstMinimum.width >= label.preferredSize.width + 24)
            assertTrue(firstMinimum.width < label.preferredSize.width + control.minimumSize.width + 24)

            label.font = label.font.deriveFont(label.font.size2D * 2f)
            val enlargedMinimum = panel.minimumSize
            assertTrue(enlargedMinimum.width > firstMinimum.width)
            assertTrue(enlargedMinimum.width >= label.preferredSize.width + 24)

            resize(panel, enlargedMinimum.width)
            assertEquals(11, label.x)
            assertTrue(label.width >= label.preferredSize.width)
            assertTrue(label.x + label.width <= panel.width - 13)
            assertTrue(control.y >= label.y + label.height)
            assertTrue(control.width >= control.minimumSize.width)
            assertTrue(control.y + control.height <= panel.height - 9)
        }

    @Test
    fun `wide layout minimum height does not reserve space for narrow stacked rows`(): Unit =
        onEdt {
            val fixture = fixture(1f)
            resize(fixture.panel, 800)
            val occupiedHeight =
                fixture.fields.maxOf { maxOf(it.label.y + it.label.height, it.control.y + it.control.height) }

            assertEquals(occupiedHeight, fixture.panel.preferredSize.height)
            assertEquals(occupiedHeight, fixture.panel.minimumSize.height)
        }

    @Test
    fun `repeated measurement and resizing preserve selections models and listeners`(): Unit =
        onEdt {
            val fixture = fixture(1f)
            val models = fixture.fields.map { it.control.model }
            val selections = fixture.fields.map { it.control.selectedItem }
            var edits = 0
            fixture.fields.forEach { field -> field.control.addItemListener { edits++ } }

            for (width in listOf(800, 400, 200, 800, 200, 400)) {
                fixture.panel.minimumSize
                fixture.panel.preferredSize
                resize(fixture.panel, width)
                assertContained(fixture)
            }

            assertEquals(0, edits)
            assertEquals(models, fixture.fields.map { it.control.model })
            assertEquals(selections, fixture.fields.map { it.control.selectedItem })
            assertEquals(fixture.fields.flatMap { listOf(it.label, it.control) }, fixture.panel.components.toList())
        }

    @Test
    fun `hidden settings leave no empty row and return in their original position`(): Unit =
        onEdt {
            val fixture = fixture(1f)
            resize(fixture.panel, 400)
            val original = fixture.fields.map { it.control.bounds }
            val fullHeight = fixture.panel.preferredSize.height
            val hidden = fixture.fields[1]
            hidden.label.isVisible = false
            hidden.control.isVisible = false

            resize(fixture.panel, 400)

            assertTrue(fixture.panel.preferredSize.height < fullHeight)
            assertTrue(fixture.fields[2].control.y < original[2].y)
            assertRowOrder(fixture.fields.filter { it.control.isVisible })
            hidden.label.isVisible = true
            hidden.control.isVisible = true
            resize(fixture.panel, 400)
            assertEquals(original, fixture.fields.map { it.control.bounds })
        }

    private fun fixture(scale: Float): LayoutFixture {
        val spacing = StudioUiTokens.spacing { logical -> pixels(logical, scale) }
        val panel = JPanel(StudioResponsiveLayout(spacing))
        val fields =
            listOf("Follow system accent", "Glow", "Accent rotation", "Chrome tint").mapIndexed { index, text ->
                val label = JLabel(text)
                label.preferredSize = Dimension(pixels(listOf(180, 80, 120, 60)[index], scale), pixels(20, scale))
                label.minimumSize = Dimension(0, pixels(20, scale))
                val control = JComboBox(arrayOf("Selected", "Other"))
                control.preferredSize = Dimension(pixels(100, scale), pixels(28, scale))
                control.minimumSize = Dimension(pixels(80, scale), pixels(28, scale))
                label.labelFor = control
                panel.add(label)
                panel.add(control)
                LayoutField(label, control)
            }
        return LayoutFixture(panel, fields)
    }

    private fun resize(
        panel: JPanel,
        width: Int,
    ) {
        panel.setSize(width, 0)
        panel.setSize(width, panel.preferredSize.height)
        panel.doLayout()
    }

    private fun assertContained(fixture: LayoutFixture) {
        for ((label, control) in fixture.fields.filter { it.control.isVisible }) {
            assertTrue(label.width >= label.preferredSize.width)
            assertTrue(control.width >= control.minimumSize.width)
            assertTrue(!label.bounds.intersects(control.bounds))
            for (component in listOf(label, control)) {
                assertTrue(component.x >= 0 && component.y >= 0)
                assertTrue(component.x + component.width <= fixture.panel.width)
                assertTrue(component.y + component.height <= fixture.panel.height)
            }
        }
    }

    private fun assertRowOrder(fields: List<LayoutField>) {
        for ((previous, next) in fields.zipWithNext()) {
            assertTrue(next.label.y > previous.control.y + previous.control.height)
        }
    }

    private fun pixels(
        logical: Int,
        scale: Float,
    ): Int = (logical * scale).roundToInt()

    private fun onEdt(action: () -> Unit) {
        SwingUtilities.invokeAndWait(action)
    }

    private data class LayoutField(
        val label: JLabel,
        val control: JComboBox<String>,
    )

    private data class LayoutFixture(
        val panel: JPanel,
        val fields: List<LayoutField>,
    )

    private companion object {
        val scales: List<Float> = listOf(1f, 1.5f, 2f)
    }
}
