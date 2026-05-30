package dev.ayuislands.settings

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.vcs.VcsColorBlender
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPalette
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsIntensity
import dev.ayuislands.vcs.VcsWriteMode
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VcsDiffPreviewComponentTest {
    @Test
    fun `row backgrounds use text attribute entries instead of color key swatches`() {
        val component = VcsDiffPreviewComponent(AyuVariant.DARK, VcsColorPreset.CYBERPUNK_SLIDER)

        for (keyName in DIFF_KEYS) {
            val actual = component.rowBackgroundForTest(keyName)
            val expectedBackground = blendedColor(keyName, VcsWriteMode.TEXT_ATTR_BG)
            val colorKeySwatch = blendedColor(keyName, VcsWriteMode.COLOR_KEY)

            assertColorEquals(expectedBackground, actual, "$keyName preview row background")
            assertNotEquals(
                colorKeySwatch.rgb,
                actual.rgb,
                "$keyName preview row background must not reuse the ColorKey swatch color",
            )
        }
    }

    private fun blendedColor(
        keyName: String,
        mode: VcsWriteMode,
    ): Color {
        val entry =
            VcsColorPalette.entriesFor(VcsColorCategory.DIFF_VIEWER).first {
                it.keyName == keyName && it.mode == mode
            }
        val (base, target) = VcsColorPalette.endpoints(entry, AyuVariant.DARK)
        return VcsColorBlender.blend(base, target, VcsIntensity.of(VcsColorPreset.CYBERPUNK_SLIDER))
    }

    private fun assertColorEquals(
        expected: Color,
        actual: Color,
        context: String,
    ) {
        assertEquals(expected.red, actual.red, "$context red")
        assertEquals(expected.green, actual.green, "$context green")
        assertEquals(expected.blue, actual.blue, "$context blue")
        assertEquals(expected.alpha, actual.alpha, "$context alpha")
    }

    private companion object {
        private val DIFF_KEYS = listOf("DIFF_MODIFIED", "DIFF_INSERTED", "DIFF_DELETED")
    }
}
