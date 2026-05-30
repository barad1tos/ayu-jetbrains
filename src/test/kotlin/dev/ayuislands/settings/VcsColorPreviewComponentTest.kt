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

class VcsColorPreviewComponentTest {
    @Test
    fun `preview uses blended palette colors for every visible VCS category`() {
        val intensities =
            VcsPreviewIntensities(
                diffViewer = VcsColorPreset.CYBERPUNK_SLIDER,
                projectView = VcsColorPreset.WHISPER_SLIDER,
                editorGutter = VcsColorPreset.NEON_SLIDER,
                conflictMarkers = 42,
                blameGutter = VcsColorPreset.AMBIENT_SLIDER,
            )
        val component = VcsColorPreviewComponent(AyuVariant.DARK, intensities)

        for (previewCase in PREVIEW_CASES) {
            assertColorEquals(
                blendedColor(previewCase, intensities.valueFor(previewCase.category)),
                component.colorForTest(previewCase.category, previewCase.keyName, previewCase.mode),
                previewCase.keyName,
            )
        }
    }

    @Test
    fun `row backgrounds use text attribute entries instead of color key swatches`() {
        val component =
            VcsColorPreviewComponent(
                AyuVariant.DARK,
                VcsPreviewIntensities(diffViewer = VcsColorPreset.CYBERPUNK_SLIDER),
            )

        for (keyName in DIFF_KEYS) {
            val actual = component.colorForTest(VcsColorCategory.DIFF_VIEWER, keyName, VcsWriteMode.TEXT_ATTR_BG)
            val expectedBackground =
                blendedColor(
                    PreviewCase(VcsColorCategory.DIFF_VIEWER, keyName, VcsWriteMode.TEXT_ATTR_BG),
                    VcsColorPreset.CYBERPUNK_SLIDER,
                )
            val colorKeySwatch =
                blendedColor(
                    PreviewCase(VcsColorCategory.DIFF_VIEWER, keyName, VcsWriteMode.COLOR_KEY),
                    VcsColorPreset.CYBERPUNK_SLIDER,
                )

            assertColorEquals(expectedBackground, actual, "$keyName preview row background")
            assertNotEquals(
                colorKeySwatch.rgb,
                actual.rgb,
                "$keyName preview row background must not reuse the ColorKey swatch color",
            )
        }
    }

    @Test
    fun `updatePreview refreshes non Diff category intensities`() {
        val component =
            VcsColorPreviewComponent(
                AyuVariant.DARK,
                VcsPreviewIntensities(conflictMarkers = VcsColorPreset.WHISPER_SLIDER),
            )
        val before = component.colorForTest(VcsColorCategory.CONFLICT_MARKERS, "DIFF_CONFLICT", VcsWriteMode.COLOR_KEY)

        component.updatePreview(
            AyuVariant.DARK,
            VcsPreviewIntensities(conflictMarkers = VcsColorPreset.CYBERPUNK_SLIDER),
        )

        val after = component.colorForTest(VcsColorCategory.CONFLICT_MARKERS, "DIFF_CONFLICT", VcsWriteMode.COLOR_KEY)
        assertEquals(VcsColorPreset.CYBERPUNK_SLIDER, component.intensityForTest(VcsColorCategory.CONFLICT_MARKERS))
        assertNotEquals(before.rgb, after.rgb, "Conflict preview color should follow Merge preset changes")
    }

    private fun blendedColor(
        previewCase: PreviewCase,
        intensity: Int,
    ): Color {
        val entry =
            VcsColorPalette.entriesFor(previewCase.category).first {
                it.keyName == previewCase.keyName && it.mode == previewCase.mode
            }
        val (base, target) = VcsColorPalette.endpoints(entry, AyuVariant.DARK)
        return VcsColorBlender.blend(base, target, VcsIntensity.of(intensity))
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

    private data class PreviewCase(
        val category: VcsColorCategory,
        val keyName: String,
        val mode: VcsWriteMode,
    )

    private companion object {
        private val DIFF_KEYS = listOf("DIFF_MODIFIED", "DIFF_INSERTED", "DIFF_DELETED")
        private val PREVIEW_CASES =
            listOf(
                PreviewCase(VcsColorCategory.DIFF_VIEWER, "DIFF_MODIFIED", VcsWriteMode.TEXT_ATTR_BG),
                PreviewCase(VcsColorCategory.PROJECT_VIEW_FILE_STATUS, "FILESTATUS_ADDED", VcsWriteMode.COLOR_KEY),
                PreviewCase(VcsColorCategory.EDITOR_GUTTER, "MODIFIED_LINES_COLOR", VcsWriteMode.COLOR_KEY),
                PreviewCase(VcsColorCategory.CONFLICT_MARKERS, "DIFF_CONFLICT", VcsWriteMode.TEXT_ATTR_BG),
                PreviewCase(VcsColorCategory.BLAME_GUTTER, "ANNOTATIONS_LAST_COMMIT_COLOR", VcsWriteMode.COLOR_KEY),
            )
    }
}
