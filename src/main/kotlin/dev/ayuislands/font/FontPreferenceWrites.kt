package dev.ayuislands.font

import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.ModifiableFontPreferences

/** Writes only the guarded group through the scheme's public copy callback. */
internal class FontPreferenceWrites(
    private val owner: FontPreferences,
    private val field: FontPreferenceField,
    private val expected: FontData,
    private val value: FontData,
) : FontPreferences() {
    override fun copyTo(preferences: FontPreferences) {
        check(preferences === owner) { "Font preference owner changed" }
        val writable = preferences as? ModifiableFontPreferences ?: error("Font preferences are not writable")
        check(FontPreferencePolicy.matches(field, FontData.captureExact(writable), expected)) { "Font field changed" }
        when (field) {
            FontPreferenceField.FAMILIES -> writeFamilies(writable)
            FontPreferenceField.LIGATURES -> writable.setUseLigatures(value.ligatures)
            FontPreferenceField.SPACING -> writable.lineSpacing = value.lineSpacing
            FontPreferenceField.REGULAR_SUBFAMILY -> writable.regularSubFamily = value.regularSubFamily
            FontPreferenceField.BOLD_SUBFAMILY -> writable.boldSubFamily = value.boldSubFamily
        }
        check(
            FontPreferencePolicy.matches(field, FontData.captureExact(writable), value),
        ) { "Font write did not settle" }
    }

    private fun writeFamilies(preferences: ModifiableFontPreferences) {
        // Changing the real-family list exposes sizes retained before the native reset step.
        val retainedSizes =
            value.families.associate { family ->
                family.name to if (preferences.hasSize(family.name)) preferences.getSize2D(family.name) else null
            }
        val states = FontPreferencePolicy.familyWrites(expected, value, retainedSizes).iterator()

        fun write(action: () -> Unit) {
            action()
            check(FontPreferencePolicy.matches(field, FontData.captureExact(preferences), states.next())) {
                "Font family write did not settle"
            }
        }
        write { preferences.setEffectiveFontFamilies(value.effectiveFamilies) }
        write { preferences.setRealFontFamilies(value.families.map(FontFamily::name)) }
        write { preferences.setTemplateFontSize(value.templateSize) }
        write { preferences.resetFontSizes() }
        for ((name, size) in value.families) {
            if (size != null) write { preferences.setFontSize(name, size) }
        }
    }
}
