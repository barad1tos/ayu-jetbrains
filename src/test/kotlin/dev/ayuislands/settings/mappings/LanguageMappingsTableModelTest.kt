package dev.ayuislands.settings.mappings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageMappingsTableModelTest {
    @Test
    fun `replaceAll resets rows`() {
        val model = LanguageMappingsTableModel()
        model.add(LanguageMapping("kotlin", "Kotlin", "#111111"))

        model.replaceAll(listOf(LanguageMapping("python", "Python", "#222222")))

        assertEquals(1, model.rowCount)
        assertEquals("Python", model.getValueAt(0, LanguageMappingsTableModel.COLUMN_LANGUAGE))
    }

    @Test
    fun `add appends and returns insertion index`() {
        val model = LanguageMappingsTableModel()

        assertEquals(0, model.add(LanguageMapping("kotlin", "Kotlin", "#111111")))
        assertEquals(1, model.add(LanguageMapping("python", "Python", "#222222")))
    }

    @Test
    fun `updateHex mutates only the target row`() {
        val model = LanguageMappingsTableModel()
        model.add(LanguageMapping("kotlin", "Kotlin", "#111111"))
        model.add(LanguageMapping("python", "Python", "#222222"))

        model.updateHex(0, "#FFEEDD")

        assertEquals("#FFEEDD", model.getValueAt(0, LanguageMappingsTableModel.COLUMN_COLOR))
        assertEquals("#222222", model.getValueAt(1, LanguageMappingsTableModel.COLUMN_COLOR))
    }

    @Test
    fun `containsLanguage is exact match against the stored lowercase id`() {
        // LanguageMapping's init block enforces that `languageId` is always lowercase, and
        // AddLanguageMappingDialog.doOKAction lowercases the probe before calling this — so
        // the model doesn't need an ignoreCase compensator. The invariant lives in the type.
        val model = LanguageMappingsTableModel()
        model.add(LanguageMapping("kotlin", "Kotlin", "#111111"))

        assertTrue(model.containsLanguage("kotlin"))
        assertFalse(model.containsLanguage("python"))
    }

    @Test
    fun `LanguageMapping constructor rejects non-lowercase language id`() {
        // Regression guard: the type-level invariant catches a future caller that forgets
        // to .lowercase() before constructing — what used to be a silent drift is now a
        // loud IllegalArgumentException at the seam.
        assertFailsWith<IllegalArgumentException> {
            LanguageMapping("Kotlin", "Kotlin", "#111111")
        }
    }

    @Test
    fun `remove drops the row and shifts indices`() {
        val model = LanguageMappingsTableModel()
        model.add(LanguageMapping("kotlin", "Kotlin", "#111111"))
        model.add(LanguageMapping("python", "Python", "#222222"))

        model.remove(0)

        assertEquals(1, model.rowCount)
        assertEquals("python", model.rowAt(0)?.languageId)
    }

    @Test
    fun `getValueAt returns column payload by index`() {
        val model = LanguageMappingsTableModel()
        model.add(LanguageMapping("kotlin", "Kotlin", "#ABCDEF"))

        assertEquals("#ABCDEF", model.getValueAt(0, LanguageMappingsTableModel.COLUMN_COLOR))
        assertEquals("Kotlin", model.getValueAt(0, LanguageMappingsTableModel.COLUMN_LANGUAGE))
    }

    @Test
    fun `LanguageMapping constructor rejects blank language id`() {
        // Defensive guard against a UI regression that would let an empty option through —
        // a blank id would silently match against `containsLanguage("")` and corrupt the
        // table-row identity.
        assertFailsWith<IllegalArgumentException> {
            LanguageMapping(" ", "Whitespace", "#111111")
        }
    }

    @Test
    fun `LanguageMapping constructor rejects malformed hex`() {
        // The Settings panel persists hex via setAccentForVariant which never inserts a
        // bad value, but a hand-edited XML or future migration could feed `"oops"` /
        // `"#FFF"` / `"#ZZZZZZ"`. The require() guard surfaces a clear error at the seam.
        assertFailsWith<IllegalArgumentException> {
            LanguageMapping("kotlin", "Kotlin", "no-hash")
        }
        assertFailsWith<IllegalArgumentException> {
            LanguageMapping("kotlin", "Kotlin", "#FFF") // 3-digit hex not accepted
        }
        assertFailsWith<IllegalArgumentException> {
            LanguageMapping("kotlin", "Kotlin", "#ZZZZZZ")
        }
    }

    @Test
    fun `remove out-of-bounds row is a silent no-op`() {
        // Defensive bound check — UI code calls remove(selectedRow) and selectedRow can be
        // -1 when nothing is selected. A regression dropping the `if (row !in rows.indices)`
        // guard would NPE inside ArrayList.removeAt and kill the Settings dialog.
        val model = LanguageMappingsTableModel()
        model.add(LanguageMapping("kotlin", "Kotlin", "#111111"))

        model.remove(-1)
        model.remove(99)

        assertEquals(1, model.rowCount, "out-of-bounds remove must not mutate the table")
    }

    @Test
    fun `updateHex out-of-bounds row is a silent no-op`() {
        // Symmetric to remove: same UI code path can hand updateHex a stale or -1 index.
        val model = LanguageMappingsTableModel()
        model.add(LanguageMapping("kotlin", "Kotlin", "#111111"))

        model.updateHex(-1, "#FFFFFF")
        model.updateHex(99, "#FFFFFF")

        assertEquals("#111111", model.getValueAt(0, LanguageMappingsTableModel.COLUMN_COLOR))
    }

    @Test
    fun `getValueAt out-of-bounds row returns null without throwing`() {
        // Swing JTable can request stale row indices during repaint races. A regression
        // dropping the `rowAt(rowIndex) ?: return null` guard would surface as NPE inside
        // the table renderer thread.
        val model = LanguageMappingsTableModel()
        assertEquals(null, model.getValueAt(0, LanguageMappingsTableModel.COLUMN_COLOR))
        assertEquals(null, model.getValueAt(99, LanguageMappingsTableModel.COLUMN_LANGUAGE))
    }

    @Test
    fun `getValueAt unknown column returns null`() {
        // Future column-count growth would leave the existing JTable schema querying
        // out-of-range columns until repaint catches up. The `else -> null` arm prevents
        // those reads from throwing.
        val model = LanguageMappingsTableModel()
        model.add(LanguageMapping("kotlin", "Kotlin", "#ABCDEF"))

        assertEquals(null, model.getValueAt(0, 99))
    }

    @Test
    fun `getColumnName out-of-bounds returns empty string`() {
        // Algorithmic guard for JTable header renderer probes that may go beyond the
        // declared column count during animation/resize. Pure logic test — locks in the
        // `getOrElse { "" }` arm so a future refactor that drops it can't ship.
        val model = LanguageMappingsTableModel()

        assertEquals("", model.getColumnName(99))
    }

    @Test
    fun `isCellEditable always returns false`() {
        // Algorithmic guard: edits flow exclusively through the Edit dialog, never inline.
        // A regression returning true would let users type into the cell and bypass the
        // dialog's validation seam — the user-visible failure is "edit lands without
        // hex / language-id checks", but the regression itself is at the model level.
        val model = LanguageMappingsTableModel()
        model.add(LanguageMapping("kotlin", "Kotlin", "#111111"))

        assertFalse(model.isCellEditable(0, LanguageMappingsTableModel.COLUMN_COLOR))
        assertFalse(model.isCellEditable(0, LanguageMappingsTableModel.COLUMN_LANGUAGE))
    }
}
