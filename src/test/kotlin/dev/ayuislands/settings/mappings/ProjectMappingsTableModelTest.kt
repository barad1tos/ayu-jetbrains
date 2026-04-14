package dev.ayuislands.settings.mappings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectMappingsTableModelTest {
    @Test
    fun `replaceAll resets rows and fires structural change`() {
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/tmp/a", "A", "#111111"))

        model.replaceAll(listOf(ProjectMapping("/tmp/b", "B", "#222222")))

        assertEquals(1, model.rowCount)
        assertEquals("B", model.getValueAt(0, ProjectMappingsTableModel.COLUMN_PROJECT))
    }

    @Test
    fun `add returns inserted row index`() {
        val model = ProjectMappingsTableModel()

        assertEquals(0, model.add(ProjectMapping("/tmp/a", "A", "#111111")))
        assertEquals(1, model.add(ProjectMapping("/tmp/b", "B", "#222222")))
    }

    @Test
    fun `remove at valid index drops the row`() {
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/tmp/a", "A", "#111111"))
        model.add(ProjectMapping("/tmp/b", "B", "#222222"))

        model.remove(0)

        assertEquals(1, model.rowCount)
        assertEquals("/tmp/b", model.getValueAt(0, ProjectMappingsTableModel.COLUMN_PATH))
    }

    @Test
    fun `remove at invalid index is a no-op`() {
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/tmp/a", "A", "#111111"))

        model.remove(-1)
        model.remove(99)

        assertEquals(1, model.rowCount)
    }

    @Test
    fun `updateHex mutates the target row only`() {
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/tmp/a", "A", "#111111"))
        model.add(ProjectMapping("/tmp/b", "B", "#222222"))

        model.updateHex(1, "#ABCDEF")

        assertEquals("#111111", model.getValueAt(0, ProjectMappingsTableModel.COLUMN_COLOR))
        assertEquals("#ABCDEF", model.getValueAt(1, ProjectMappingsTableModel.COLUMN_COLOR))
    }

    @Test
    fun `containsPath is a pure lookup`() {
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/tmp/a", "A", "#111111"))

        assertTrue(model.containsPath("/tmp/a"))
        assertFalse(model.containsPath("/tmp/b"))
    }

    @Test
    fun `snapshot returns a defensive copy`() {
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/tmp/a", "A", "#111111"))

        val snap = model.snapshot()
        model.add(ProjectMapping("/tmp/b", "B", "#222222"))

        assertEquals(1, snap.size)
    }

    @Test
    fun `getValueAt returns column payload by index`() {
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/tmp/a", "Alpha", "#ABCDEF"))

        assertEquals("#ABCDEF", model.getValueAt(0, ProjectMappingsTableModel.COLUMN_COLOR))
        assertEquals("Alpha", model.getValueAt(0, ProjectMappingsTableModel.COLUMN_PROJECT))
        assertEquals("/tmp/a", model.getValueAt(0, ProjectMappingsTableModel.COLUMN_PATH))
        assertNull(model.getValueAt(0, 99))
    }

    @Test
    fun `cells are not editable`() {
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/tmp/a", "A", "#111111"))

        assertFalse(model.isCellEditable(0, 0))
        assertFalse(model.isCellEditable(0, 1))
        assertFalse(model.isCellEditable(0, 2))
    }
}
