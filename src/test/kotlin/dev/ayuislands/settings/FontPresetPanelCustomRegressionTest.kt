package dev.ayuislands.settings

import dev.ayuislands.font.FontPreset
import dev.ayuislands.onboarding.PremiumOnboardingPanel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Retained onboarding catalog invariant; Settings font resource coverage lives in the Studio suites. */
class FontPresetPanelCustomRegressionTest {
    @Test
    fun `PremiumOnboardingPanel FONT_PRESETS list contains only curated presets`() {
        // Reflective invariant on the private FONT_PRESETS field. Onboarding
        // font cards install curated catalog entries; CUSTOM is user-provided
        // and has no catalog install metadata.
        // Kotlin private companion-object vals get compiled as private static
        // fields on the enclosing class, not on $Companion. Reflect there.
        val fontPresetsField =
            PremiumOnboardingPanel::class.java.getDeclaredField("FONT_PRESETS")
        fontPresetsField.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val presets = fontPresetsField.get(null) as List<FontPreset>

        assertTrue(presets.isNotEmpty(), "FONT_PRESETS must not be empty")
        assertTrue(
            presets.all { it.isCurated },
            "FONT_PRESETS must contain only curated presets, got: " +
                presets.filter { !it.isCurated },
        )
        assertFalse(
            presets.contains(FontPreset.CUSTOM),
            "FONT_PRESETS must not include CUSTOM (onboarding cards only support installable curated presets)",
        )
    }
}
