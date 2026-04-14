package dev.ayuislands.settings.mappings

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `containsLanguage is case-insensitive`() {
        val model = LanguageMappingsTableModel()
        model.add(LanguageMapping("kotlin", "Kotlin", "#111111"))

        assertTrue(model.containsLanguage("kotlin"))
        assertTrue(model.containsLanguage("Kotlin"))
        assertTrue(model.containsLanguage("KOTLIN"))
        assertFalse(model.containsLanguage("python"))
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
}
