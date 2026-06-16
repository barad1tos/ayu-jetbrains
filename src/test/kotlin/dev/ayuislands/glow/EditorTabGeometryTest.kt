package dev.ayuislands.glow

import java.awt.Color
import java.awt.Rectangle
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

    // --- calculateEditorOverlayBounds tests ---

    @Test
    fun `calculateEditorOverlayBounds uses default when no EditorTabs found`() {
        val host = JPanel()
        host.setSize(800, 600)

        val bounds = EditorTabGeometry.calculateEditorOverlayBounds(host)
        val defaultH = EditorTabGeometry.DEFAULT_TAB_HEIGHT
        assertEquals(Rectangle(0, defaultH, 800, 600 - defaultH), bounds)
    }

    @Test
    fun `calculateEditorOverlayBounds finds nested EditorTabs and uses TabLabel bottom`() {
        val host = JPanel()
        host.setSize(800, 600)

        val wrapper = JPanel()
        val editorTabs = MockEditorTabs()
        val tabLabel = MockTabLabel()
        tabLabel.setBounds(0, 0, 200, 32)
        editorTabs.add(tabLabel)
        wrapper.add(editorTabs)
        host.add(wrapper)

        val bounds = EditorTabGeometry.calculateEditorOverlayBounds(host)
        assertEquals(Rectangle(0, 32, 800, 568), bounds)
    }

    @Test
    fun `calculateEditorOverlayBounds uses default when EditorTabs has no TabLabel`() {
        val host = JPanel()
        host.setSize(800, 600)

        val editorTabs = MockEditorTabs()
        editorTabs.add(JLabel("not a tab"))
        host.add(editorTabs)

        val bounds = EditorTabGeometry.calculateEditorOverlayBounds(host)
        val defaultH = EditorTabGeometry.DEFAULT_TAB_HEIGHT
        assertEquals(Rectangle(0, defaultH, 800, 600 - defaultH), bounds)
    }

    @Test
    fun `calculateEditorOverlayBounds clamps to zero when host is shorter than tab strip`() {
        val host = JPanel()
        host.setSize(800, 20)

        val editorTabs = MockEditorTabs()
        val tabLabel = MockTabLabel()
        tabLabel.setBounds(0, 0, 200, 32)
        editorTabs.add(tabLabel)
        host.add(editorTabs)

        val bounds = EditorTabGeometry.calculateEditorOverlayBounds(host)
        assertEquals(0, bounds.height)
    }

    // Mock classes — javaClass.name contains the substrings that
    // ComponentHierarchyUtils.findChildByClassName matches against
    private class MockEditorTabs : JPanel()

    private class MockTabLabel : JComponent()
}
