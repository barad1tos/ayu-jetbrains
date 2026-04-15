package dev.ayuislands.settings.mappings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `ProjectMapping rejects blank canonicalPath`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectMapping(canonicalPath = "", displayName = "Empty", hex = "#FFCC66")
        }
    }

    @Test
    fun `ProjectMapping rejects malformed hex`() {
        // Three classes of malformed hex the regex ^#[0-9A-Fa-f]{6}$ rejects: short, long,
        // missing octothorpe. Each surfaces as IllegalArgumentException at the seam instead
        // of silently flowing into Color.decode downstream.
        assertFailsWith<IllegalArgumentException> {
            ProjectMapping(canonicalPath = "/tmp/a", displayName = "A", hex = "#FFC")
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectMapping(canonicalPath = "/tmp/a", displayName = "A", hex = "#AABBCCDD")
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectMapping(canonicalPath = "/tmp/a", displayName = "A", hex = "FFCC66")
        }
    }

    @Test
    fun `ProjectMapping accepts mixed-case hex`() {
        // The regex permits both upper and lower hex digits — production writers normalize to
        // upper, but legacy XML or hand-edits using `#aabbcc` shouldn't fail construction.
        ProjectMapping(canonicalPath = "/tmp/a", displayName = "A", hex = "#aAbBcC")
    }

    @Test
    fun `cells are not editable`() {
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/tmp/a", "A", "#111111"))

        assertFalse(model.isCellEditable(0, 0))
        assertFalse(model.isCellEditable(0, 1))
        assertFalse(model.isCellEditable(0, 2))
    }

    @Test
    fun `updateHex out-of-bounds row is a silent no-op`() {
        // Mirror of `remove at invalid index is a no-op` for the second mutator. UI code
        // calls updateHex(selectedRow, ...) and selectedRow can be -1 when nothing is
        // selected; without the bound guard this would throw IndexOutOfBoundsException
        // from the underlying ArrayList.set, killing the Settings dialog.
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/tmp/a", "A", "#111111"))

        model.updateHex(-1, "#FFFFFF")
        model.updateHex(99, "#FFFFFF")

        assertEquals("#111111", model.getValueAt(0, ProjectMappingsTableModel.COLUMN_COLOR))
    }

    @Test
    fun `getValueAt out-of-bounds row returns null`() {
        // Swing JTable can request stale row indices during repaint races; the
        // `rowAt(rowIndex) ?: return null` guard must hand back null instead of NPE'ing.
        val model = ProjectMappingsTableModel()
        assertNull(model.getValueAt(0, ProjectMappingsTableModel.COLUMN_COLOR))
        assertNull(model.getValueAt(99, ProjectMappingsTableModel.COLUMN_PATH))
    }

    @Test
    fun `isOrphan returns false for out-of-bounds row`() {
        // The renderer's prepareRenderer hook calls isOrphan(viewIndex), which can be stale
        // mid-repaint. Without the `rowAt(...) ?: return false` guard the access would NPE
        // and surface as a SEVERE in idea.log on every repaint.
        val model = ProjectMappingsTableModel()
        assertFalse(model.isOrphan(0), "no rows yet — out-of-bounds must report false")
        assertFalse(model.isOrphan(-1))
    }

    @Test
    fun `isOrphan returns true for path that does not exist on disk`() {
        // The whole point of the orphan flag — when a project is moved/deleted, the stored
        // canonicalPath stops resolving to a directory and the row should be styled
        // differently. A regression that flipped the !isDirectory check would mark every
        // valid mapping as orphaned.
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping("/this/path/definitely/does/not/exist", "Gone", "#111111"))

        assertTrue(model.isOrphan(0))
    }

    @Test
    fun `isOrphan returns false for path that resolves to a real directory`() {
        // System tempdir always exists on every platform — use it as the canonical "live"
        // directory so the test is portable across CI runners.
        val model = ProjectMappingsTableModel()
        model.add(ProjectMapping(System.getProperty("java.io.tmpdir"), "Tmp", "#111111"))

        assertFalse(model.isOrphan(0))
    }
}
