package dev.ayuislands.glow

import java.awt.Color
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorTabGeometryTest {
    @Test
    fun `DEFAULT_TAB_HEIGHT is 28`() {
        assertEquals(28, EditorTabGeometry.DEFAULT_TAB_HEIGHT)
    }

    @Test
    fun `calculateTabStripHeight returns default for empty host`() {
        val height = EditorTabGeometry.calculateTabStripHeight(JPanel())
        assertEquals(EditorTabGeometry.DEFAULT_TAB_HEIGHT, height)
    }

    @Test
    fun `calculateTabStripHeight returns default when no EditorTabs child`() {
        val host = JPanel()
        host.add(JLabel("not a tab"))

        val height = EditorTabGeometry.calculateTabStripHeight(host)
        assertEquals(EditorTabGeometry.DEFAULT_TAB_HEIGHT, height)
    }

    @Test
    fun `calculateTabStripHeight returns child y plus height when TabLabel found`() {
        val host = JPanel()
        // Class name contains "EditorTabs" for findChildByClassName match
        val editorTabs = MockEditorTabs()
        // Class name contains "TabLabel" for inner loop match
        val tabLabel = MockTabLabel()
        tabLabel.setBounds(0, 5, 100, 30)
        editorTabs.add(tabLabel)
        host.add(editorTabs)

        val height = EditorTabGeometry.calculateTabStripHeight(host)
        assertEquals(35, height) // y=5 + height=30
    }

    @Test
    fun `calculateTabStripHeight returns default when EditorTabs present but no TabLabel child`() {
        val host = JPanel()
        val editorTabs = MockEditorTabs()
        editorTabs.add(JLabel("not a tab label"))
        host.add(editorTabs)

        val height = EditorTabGeometry.calculateTabStripHeight(host)
        assertEquals(EditorTabGeometry.DEFAULT_TAB_HEIGHT, height)
    }

    @Test
    fun `safeDecodeColor returns decoded color for valid hex`() {
        val color = GlowOverlayManager.safeDecodeColor("#FF0000")
        assertEquals(Color.RED, color)
    }

    @Test
    fun `safeDecodeColor returns fallback for invalid hex`() {
        val fallback = GlowOverlayManager.safeDecodeColor("not-a-color")
        assertEquals(Color.decode("#FFCC66"), fallback)
    }

    @Test
    fun `findEditorTabsComponent returns null when no matching ancestor`() {
        val panel = JPanel()
        val child = JLabel("test")
        panel.add(child)

        assertNull(EditorTabGeometry.findEditorTabsComponent(child))
    }

    // Mock classes — javaClass.name contains the substrings that
    // ComponentHierarchyUtils.findChildByClassName matches against
    @Suppress("unused") // Name matched via javaClass.name.contains("EditorTabs")
    private class MockEditorTabs : JPanel()

    @Suppress("unused") // Name matched via javaClass.name.contains("TabLabel")
    private class MockTabLabel : JComponent()
}
