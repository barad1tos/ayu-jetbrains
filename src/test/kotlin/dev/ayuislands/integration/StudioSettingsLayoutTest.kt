package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.ui.StudioSectionContent
import dev.ayuislands.studio.ui.StudioSettingsPanel
import org.junit.Rule
import org.junit.Test
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.Scrollable
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSettingsLayoutTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `native Settings tabs wrap labels while yielding minimum width to the host`() =
        onHostEdt {
            val tabs = tabs()

            assertEquals(JTabbedPane.WRAP_TAB_LAYOUT, tabs.tabLayoutPolicy)
            assertEquals(Dimension(0, 0), tabs.minimumSize)
            assertEquals(StudioSettingsSection.entries.map { it.title }, (0 until tabs.tabCount).map(tabs::getTitleAt))
        }

    @Test
    fun `every native Settings section retains its vertical scroll wrapper`() =
        onHostEdt {
            val tabs = tabs()

            for (index in 0 until tabs.tabCount) {
                val scroll = assertIs<JScrollPane>(tabs.getComponentAt(index), tabs.getTitleAt(index))
                assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, scroll.horizontalScrollBarPolicy)
                assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, scroll.verticalScrollBarPolicy)
                val content = scroll.viewport.view
                assertTrue(assertIs<Scrollable>(content).scrollableTracksViewportWidth)
                assertTrue(scroll.preferredSize.width <= 420)
            }
        }

    @Test
    fun `native Settings content tracks a compact viewport width`() =
        onHostEdt {
            val scroll = assertIs<JScrollPane>(tabs().getComponentAt(0))
            val content = scroll.viewport.view

            scroll.setSize(420, 240)
            scroll.doLayout()
            scroll.viewport.doLayout()

            assertEquals(scroll.viewport.extentSize.width, content.width)
        }

    private fun tabs(): JTabbedPane {
        val sections =
            StudioSettingsSection.entries.map { section ->
                val content = JPanel().apply { preferredSize = Dimension(1200, 240) }
                StudioSectionContent(section, content) { }
            }
        val panel = StudioSettingsPanel(sections, {}, {}, {})
        return hostDescendants(panel).filterIsInstance<JTabbedPane>().single()
    }
}
