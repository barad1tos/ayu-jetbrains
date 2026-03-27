package dev.ayuislands.glow

import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ComponentHierarchyUtilsTest {
    @Test
    fun `findAncestorByClassName returns matching parent when present`() {
        val grandparent = JPanel()
        val parent = JPanel()
        val child = JLabel("test")
        grandparent.add(parent)
        parent.add(child)

        val result = ComponentHierarchyUtils.findAncestorByClassName(child, "JPanel")
        assertNotNull(result)
        assertEquals(parent, result)
    }

    @Test
    fun `findAncestorByClassName returns null when className not found`() {
        val parent = JPanel()
        val child = JLabel("test")
        parent.add(child)

        val result = ComponentHierarchyUtils.findAncestorByClassName(child, "NonExistentClass")
        assertNull(result)
    }

    @Test
    fun `findAncestorByClassName returns null for orphan component`() {
        val orphan = JLabel("alone")

        val result = ComponentHierarchyUtils.findAncestorByClassName(orphan, "JPanel")
        assertNull(result)
    }

    @Test
    fun `findAncestorByClassName respects maxDepth limit`() {
        val level3 = JPanel()
        val level2 = JPanel()
        val level1 = JPanel()
        val child = JLabel("deep")
        level3.add(level2)
        level2.add(level1)
        level1.add(child)

        // level3 is at depth 3 from child -- found with maxDepth=3
        val found = ComponentHierarchyUtils.findAncestorByClassName(child, "JPanel", maxDepth = 3)
        assertNotNull(found)

        // maxDepth=0 means no ancestors are examined
        val notFound = ComponentHierarchyUtils.findAncestorByClassName(child, "JPanel", maxDepth = 0)
        assertNull(notFound)
    }

    @Test
    fun `findAncestorByClassName uses substring matching on class name`() {
        val parent = JPanel()
        val child = JLabel("test")
        parent.add(child)

        // "Panel" is a substring of "javax.swing.JPanel"
        val result = ComponentHierarchyUtils.findAncestorByClassName(child, "Panel")
        assertNotNull(result)
        assertEquals(parent, result)
    }

    @Test
    fun `findChildByClassName returns matching child`() {
        val container = JPanel()
        val label = JLabel("hello")
        val button = JButton("click")
        container.add(label)
        container.add(button)

        val result = ComponentHierarchyUtils.findChildByClassName(container, "JLabel")
        assertNotNull(result)
        assertEquals(label, result)
    }

    @Test
    fun `findChildByClassName returns null when no child matches`() {
        val container = JPanel()
        container.add(JLabel("hello"))

        val result = ComponentHierarchyUtils.findChildByClassName(container, "JButton")
        assertNull(result)
    }

    @Test
    fun `findGlowHost returns component itself when no known ancestor found`() {
        val standalone = JPanel()

        val result = ComponentHierarchyUtils.findGlowHost(standalone)
        assertEquals(standalone, result)
    }

    @Test
    fun `findEditorHost returns null when no EditorsSplitters ancestor found`() {
        val panel = JPanel()
        val child = JPanel()
        panel.add(child)

        val result = ComponentHierarchyUtils.findEditorHost(child)
        assertNull(result)
    }
}
