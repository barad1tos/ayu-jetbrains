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
        val thrown =
            runCatching { LanguageMapping("Kotlin", "Kotlin", "#111111") }
                .exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
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
