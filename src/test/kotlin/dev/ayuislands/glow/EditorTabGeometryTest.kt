package dev.ayuislands.glow

import java.awt.Color
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorTabGeometryTest {
    @Test
    fun `DEFAULT_TAB_HEIGHT is 28`() {
        assertEquals(28, EditorTabGeometry.DEFAULT_TAB_HEIGHT)
    }

    @Test
    fun `calculateTabStripHeight returns default for empty host`() {
        val emptyHost = JPanel()

        val height = EditorTabGeometry.calculateTabStripHeight(emptyHost)
        assertEquals(EditorTabGeometry.DEFAULT_TAB_HEIGHT, height)
    }

    @Test
    fun `calculateTabStripHeight returns default when no EditorTabs child`() {
        val host = JPanel()
        host.add(JLabel("not a tab"))

        val height = EditorTabGeometry.calculateTabStripHeight(host)
        assertEquals(EditorTabGeometry.DEFAULT_TAB_HEIGHT, height)
    }

    // Positive path (EditorTabs + TabLabel present) requires IDE component classes -- tested via runIde

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

        val result = EditorTabGeometry.findEditorTabsComponent(child)
        assertEquals(null, result)
    }
}
