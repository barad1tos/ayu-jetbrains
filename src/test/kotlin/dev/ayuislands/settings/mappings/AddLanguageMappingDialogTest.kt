package dev.ayuislands.settings.mappings

import kotlin.test.Test
import kotlin.test.assertEquals

class AddLanguageMappingDialogTest {
    @Test
    fun `display label annotates markup language ids`() {
        assertEquals(
            "YAML (markup/config - wins only in markup-only projects)",
            AddLanguageMappingDialog.displayLabelForTest("yaml", "YAML"),
        )
    }

    @Test
    fun `display label leaves code language ids unchanged`() {
        assertEquals(
            "Kotlin",
            AddLanguageMappingDialog.displayLabelForTest("kotlin", "Kotlin"),
        )
    }
}
