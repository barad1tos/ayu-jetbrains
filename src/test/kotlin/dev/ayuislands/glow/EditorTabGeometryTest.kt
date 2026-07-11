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

    @Test
    fun `calculateEditorOverlayBounds rejects selected content that spans the whole host`() {
        // 2026.x tab layouts report the selected tab's component as covering
        // the entire host (y=0). Trusting that rectangle drew the top-anchored
        // strip along the window top; the sanity gate must fall through to the
        // real tab-label measurement instead.
        val host = JPanel(null)
        host.setSize(800, 600)
        val editorTabs = SelectionEditorTabsFake()
        editorTabs.setBounds(0, 0, 800, 600)
        host.add(editorTabs)

        val fullHostContent = JPanel()
        fullHostContent.setBounds(0, 0, 800, 600)
        editorTabs.add(fullHostContent)
        editorTabs.infoForTest = TabInfoFake(fullHostContent)

        val label = MockTabLabel()
        label.setBounds(0, 0, 120, 25)
        editorTabs.add(label)
        editorTabs.labelForTest = label

        val bounds = EditorTabGeometry.calculateEditorOverlayBounds(host)

        assertEquals(Rectangle(0, 25, 800, 575), bounds)
    }

    @Test
    fun `calculateEditorOverlayBounds trusts selected content that starts below the tab strip`() {
        val host = JPanel(null)
        host.setSize(800, 600)
        val editorTabs = SelectionEditorTabsFake()
        editorTabs.setBounds(0, 0, 800, 600)
        host.add(editorTabs)

        val content = JPanel()
        content.setBounds(0, 30, 800, 570)
        editorTabs.add(content)
        editorTabs.infoForTest = TabInfoFake(content)

        val bounds = EditorTabGeometry.calculateEditorOverlayBounds(host)

        assertEquals(Rectangle(0, 30, 800, 570), bounds)
    }

    @Test
    fun `editor geometry returns visible tab and action spans in content coordinates`() {
        val host = JPanel(null)
        host.setSize(800, 600)
        val editorTabs = SelectionEditorTabsFake()
        editorTabs.setBounds(20, 0, 760, 600)
        host.add(editorTabs)

        val content = JPanel()
        content.setBounds(10, 30, 720, 570)
        editorTabs.add(content)
        editorTabs.infoForTest = TabInfoFake(content)

        val tabLabel = MockTabLabel()
        tabLabel.setBounds(10, 0, 180, 30)
        editorTabs.add(tabLabel)
        val toolbar = MockActionToolbar()
        toolbar.setBounds(650, 0, 80, 30)
        editorTabs.add(toolbar)
        val hiddenToolbar = MockActionToolbar()
        hiddenToolbar.setBounds(400, 0, 100, 30)
        hiddenToolbar.isVisible = false
        editorTabs.add(hiddenToolbar)

        val geometry = EditorTabGeometry.editorOverlayGeometry(host)

        assertEquals(Rectangle(30, 30, 720, 570), geometry.contentBounds)
        assertEquals(listOf(0..179, 640..719), geometry.occupiedTopSpans)
    }

    @Test
    fun `editor geometry leaves top spans empty when tab internals are unavailable`() {
        val host = JPanel()
        host.setSize(800, 600)

        val geometry = EditorTabGeometry.editorOverlayGeometry(host)

        assertEquals(emptyList(), geometry.occupiedTopSpans)
        assertEquals(EditorTabGeometry.calculateEditorOverlayBounds(host), geometry.contentBounds)
    }

    @Test
    fun `tab label bottom converts through offset containers into host coordinates`() {
        // The strip anchor must be measured in HOST coordinates: a tabs
        // component sitting 10px below the host top means the strip starts at
        // label bottom + that offset, not at the label's local bottom.
        val host = JPanel(null)
        host.setSize(800, 600)
        val editorTabs = MockEditorTabs()
        editorTabs.setBounds(0, 10, 800, 25)
        host.add(editorTabs)

        val tabLabel = MockTabLabel()
        tabLabel.setBounds(0, 0, 100, 20)
        editorTabs.add(tabLabel)

        val bounds = EditorTabGeometry.calculateEditorOverlayBounds(host)

        assertEquals(Rectangle(0, 30, 800, 570), bounds)
    }

    @Test
    fun `tab label whose converted bottom is at the host top falls back to default`() {
        val host = JPanel(null)
        host.setSize(800, 600)
        val editorTabs = MockEditorTabs()
        editorTabs.setBounds(0, 0, 800, 25)
        host.add(editorTabs)

        val tabLabel = MockTabLabel()
        tabLabel.setBounds(0, -25, 100, 25)
        editorTabs.add(tabLabel)

        val bounds = EditorTabGeometry.calculateEditorOverlayBounds(host)

        val defaultH = EditorTabGeometry.DEFAULT_TAB_HEIGHT
        assertEquals(Rectangle(0, defaultH, 800, 600 - defaultH), bounds)
    }

    // Mock classes — javaClass.name contains the substrings that
    // ComponentHierarchyUtils.findChildByClassName matches against
    private class MockEditorTabs : JPanel()

    private class MockTabLabel : JComponent()

    private class MockActionToolbar : JComponent()
}

// File-level fakes (NOT private nested classes): the production code invokes
// getSelectedInfo/getSelectedLabel/getComponent reflectively, and reflection
// on a private nested Kotlin class throws IllegalAccessException, which the
// production catch would silently convert into a fallback — making the tests
// pass for the wrong reason.
internal class SelectionEditorTabsFake : JPanel(null) {
    var infoForTest: Any? = null
    var labelForTest: JComponent? = null

    fun getSelectedInfo(): Any? = infoForTest

    fun getSelectedLabel(): JComponent? = labelForTest
}

internal class TabInfoFake(
    private val content: JComponent,
) {
    fun getComponent(): JComponent = content
}
