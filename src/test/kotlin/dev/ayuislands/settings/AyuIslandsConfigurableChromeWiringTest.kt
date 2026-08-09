package dev.ayuislands.settings

import com.intellij.ui.components.JBTabbedPane
import java.awt.Color
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.Scrollable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Observable JetBrains adapter behavior that remains outside settings composition. */
class AyuIslandsConfigurableChromeWiringTest {
    @Test
    fun `apply failure maps failed and skipped sections with its cause`() {
        val cause = IllegalStateException("accent failed")
        val failure =
            SettingsApplyResult.Failed(
                failed = "Accent",
                skipped = listOf("Chrome", "Elements"),
                cause = cause,
            )

        val exception = failure.toConfigurationException()

        assertEquals(
            "Failed to apply Accent settings. Cause: accent failed. " +
                "Skipped: Chrome, Elements. Review the IDE log and retry Apply.",
            exception.localizedMessage,
        )
        assertSame(cause, exception.cause)
    }

    @Test
    fun `Configurable tabs keep labels visible while yielding width to Settings window`() {
        val tabs = JBTabbedPane()
        val configure =
            AyuIslandsConfigurable::class.java.getDeclaredMethod(
                "configureSettingsTabsForResize",
                JBTabbedPane::class.java,
            )
        configure.isAccessible = true
        configure.invoke(AyuIslandsConfigurable(), tabs)

        assertEquals(JTabbedPane.WRAP_TAB_LAYOUT, tabs.tabLayoutPolicy)
        assertEquals(0, tabs.minimumSize.width)
        assertEquals(0, tabs.minimumSize.height)
    }

    @Test
    fun `Configurable tab content tracks compact viewport width`() {
        val wideContent =
            JPanel().apply {
                preferredSize = Dimension(WIDE_CONTENT_WIDTH, TAB_CONTENT_HEIGHT)
            }
        val createScrollableContent =
            AyuIslandsConfigurable::class.java.getDeclaredMethod(
                "createScrollableTabContent",
                JComponent::class.java,
            )
        createScrollableContent.isAccessible = true

        val scrollPane = createScrollableContent.invoke(AyuIslandsConfigurable(), wideContent) as JScrollPane
        val viewportView = scrollPane.viewport.view

        assertTrue(scrollPane.preferredSize.width <= COMPACT_TAB_WIDTH)
        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, scrollPane.horizontalScrollBarPolicy)
        assertTrue(viewportView is Scrollable)
        assertTrue((viewportView as Scrollable).scrollableTracksViewportWidth)

        scrollPane.setSize(COMPACT_TAB_WIDTH, TAB_CONTENT_HEIGHT)
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()

        assertEquals(scrollPane.viewport.extentSize.width, viewportView.width)
    }

    @Test
    fun `Configurable assembles every content tab with width tracking scroll wrapper`() {
        val intParameter = Int::class.javaPrimitiveType ?: error("Int primitive type must be available")
        val createSettingsTabs =
            AyuIslandsConfigurable::class.java.getDeclaredMethod(
                "createSettingsTabs",
                List::class.java,
                Color::class.java,
                intParameter,
                Function1::class.java,
            )
        createSettingsTabs.isAccessible = true
        val expectedTitles = listOf("Accent", "Font", "Glow", "Syntax", "VCS", "Workspace", "Plugins")
        val contentTabs =
            expectedTitles.map { title ->
                title to
                    JPanel().apply {
                        preferredSize = Dimension(WIDE_CONTENT_WIDTH, TAB_CONTENT_HEIGHT)
                    }
            }

        val tabs =
            createSettingsTabs.invoke(
                AyuIslandsConfigurable(),
                contentTabs,
                Color.CYAN,
                0,
                { _: Int -> },
            ) as JBTabbedPane

        assertEquals(expectedTitles.size + LINK_TAB_COUNT, tabs.tabCount)
        for ((index, title) in expectedTitles.withIndex()) {
            assertEquals(title, tabs.getTitleAt(index))
            val scrollPane = tabs.getComponentAt(index)
            assertTrue(scrollPane is JScrollPane, "$title tab must be wrapped in a scroll pane")
            assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, scrollPane.horizontalScrollBarPolicy)
            val viewportView = scrollPane.viewport.view
            assertTrue(viewportView is Scrollable)
            assertTrue(viewportView.scrollableTracksViewportWidth)
            assertTrue(scrollPane.preferredSize.width <= COMPACT_TAB_WIDTH)
        }
        assertFalse(tabs.isEnabledAt(expectedTitles.size))
        assertFalse(tabs.isEnabledAt(expectedTitles.size + 1))
    }

    private companion object {
        const val WIDE_CONTENT_WIDTH = 1200
        const val COMPACT_TAB_WIDTH = 420
        const val TAB_CONTENT_HEIGHT = 240
        const val LINK_TAB_COUNT = 2
    }
}
