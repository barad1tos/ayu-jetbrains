package dev.ayuislands.settings

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.vcs.VcsColorBlender
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPalette
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsIntensity
import dev.ayuislands.vcs.VcsWriteMode
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VcsColorPreviewComponentTest {
    @Test
    fun `preview exposes compact minimum width and still paints core regions when narrowed`() {
        val component = VcsColorPreviewComponent(AyuVariant.DARK)
        val minimumSize = component.minimumSize

        assertTrue(
            minimumSize.width < component.preferredSize.width,
            "Preview minimum width must be below its natural width so Settings can shrink the tab content",
        )
        assertTrue(
            minimumSize.width <= component.preferredSize.width * MAX_MINIMUM_WIDTH_RATIO_NUMERATOR /
                MAX_MINIMUM_WIDTH_RATIO_DENOMINATOR,
            "Preview minimum width must stay compact enough to relieve horizontal pressure in narrow Settings windows",
        )

        val image = render(component, minimumSize)
        assertColorEquals(
            component.colorForTest(VcsColorCategory.CONFLICT_MARKERS, "DIFF_CONFLICT", VcsWriteMode.COLOR_KEY),
            image.colorAt(DIFF_STRIPE_X, CONFLICT_ROW_Y),
            "narrow conflict marker stripe",
        )
        assertColorEquals(
            component.colorForTest(VcsColorCategory.BLAME_GUTTER, "VCS_ANNOTATIONS_COLOR_3", VcsWriteMode.COLOR_KEY),
            image.colorAt(NARROW_BLAME_BACKGROUND_X, CONFLICT_ROW_Y),
            "narrow blame gutter stays separated from code text",
        )
    }

    @Test
    fun `paintComponent renders every preview region and clamps blame gutter`() {
        val component = VcsColorPreviewComponent(AyuVariant.DARK)
        val image = render(component)
        val editorSurface = image.colorAt(DIFF_BODY_X, UNCHANGED_ROW_Y)

        assertColorEquals(
            component.colorForTest(
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
                "FILESTATUS_MODIFIED",
                VcsWriteMode.COLOR_KEY,
            ),
            image.colorAt(PROJECT_DOT_X, PROJECT_DOT_Y),
            "project status dot",
        )
        assertColorEquals(
            component.colorForTest(VcsColorCategory.DIFF_VIEWER, "DIFF_MODIFIED", VcsWriteMode.COLOR_KEY),
            image.colorAt(DIFF_STRIPE_X, FIRST_CODE_ROW_Y),
            "diff stripe",
        )
        assertColorEquals(
            component.colorForTest(VcsColorCategory.EDITOR_GUTTER, "MODIFIED_LINES_COLOR", VcsWriteMode.COLOR_KEY),
            image.colorAt(GUTTER_MARKER_X, FIRST_CODE_ROW_Y),
            "editor gutter marker",
        )
        assertColorEquals(
            component.colorForTest(VcsColorCategory.CONFLICT_MARKERS, "DIFF_CONFLICT", VcsWriteMode.COLOR_KEY),
            image.colorAt(DIFF_STRIPE_X, CONFLICT_ROW_Y),
            "conflict marker stripe",
        )
        for (rowBodyCase in ROW_BODY_CASES) {
            assertColorEquals(
                compositeOverSurface(
                    component.colorForTest(rowBodyCase.category, rowBodyCase.keyName, VcsWriteMode.TEXT_ATTR_BG),
                    editorSurface,
                ),
                image.colorAt(DIFF_BODY_X, rowBodyCase.y),
                "${rowBodyCase.keyName} row body",
            )
        }
        assertColorEquals(
            component.colorForTest(VcsColorCategory.BLAME_GUTTER, "VCS_ANNOTATIONS_COLOR_1", VcsWriteMode.COLOR_KEY),
            image.colorAt(BLAME_BACKGROUND_X, FIRST_BLAME_ROW_Y),
            "blame age-ramp background",
        )

        assertNotEquals(
            component.colorForTest(VcsColorCategory.BLAME_GUTTER, "ANNOTATIONS_COLOR", VcsWriteMode.COLOR_KEY).rgb,
            image.colorAt(BLAME_BACKGROUND_X, LAST_BLAME_ROW_Y).rgb,
            "ANNOTATIONS_COLOR is text color, not a blame background band",
        )
        assertColorEquals(
            image.colorAt(OUTER_SURFACE_X, BOTTOM_PADDING_Y),
            image.colorAt(BLAME_BACKGROUND_X, BOTTOM_PADDING_Y),
            "blame gutter bottom padding",
        )
    }

    @Test
    fun `paintComponent follows updatePreview intensity changes`() {
        val component =
            VcsColorPreviewComponent(
                AyuVariant.DARK,
                VcsPreviewIntensities(diffViewer = VcsColorPreset.WHISPER_SLIDER),
            )
        val before = render(component).colorAt(DIFF_STRIPE_X, FIRST_CODE_ROW_Y)

        component.updatePreview(
            AyuVariant.DARK,
            VcsPreviewIntensities(diffViewer = VcsColorPreset.CYBERPUNK_SLIDER),
        )

        val after = render(component).colorAt(DIFF_STRIPE_X, FIRST_CODE_ROW_Y)
        assertNotEquals(before.rgb, after.rgb, "Painted Diff stripe must follow preview intensity changes")
        assertColorEquals(
            component.colorForTest(VcsColorCategory.DIFF_VIEWER, "DIFF_MODIFIED", VcsWriteMode.COLOR_KEY),
            after,
            "updated Diff stripe",
        )
    }

    @Test
    fun `blame preview keeps age ramp backgrounds separate from annotation text colors`() {
        val component = VcsColorPreviewComponent(AyuVariant.DARK)

        assertEquals(
            listOf(
                "VCS_ANNOTATIONS_COLOR_1" to "ANNOTATIONS_LAST_COMMIT_COLOR",
                "VCS_ANNOTATIONS_COLOR_2" to "ANNOTATIONS_COLOR",
                "VCS_ANNOTATIONS_COLOR_3" to "ANNOTATIONS_COLOR",
                "VCS_ANNOTATIONS_COLOR_4" to "ANNOTATIONS_COLOR",
                "VCS_ANNOTATIONS_COLOR_5" to "ANNOTATIONS_COLOR",
                null to "ANNOTATIONS_COLOR",
            ),
            component.blameRowKeysForTest(),
        )
    }

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

    private fun compositeOverSurface(
        color: Color,
        surface: Color,
    ): Color {
        val alpha = color.alpha / CHANNEL_MAX.toDouble()
        val inverseAlpha = 1.0 - alpha
        val red = blendChannel(color.red, surface.red, alpha, inverseAlpha)
        val green = blendChannel(color.green, surface.green, alpha, inverseAlpha)
        val blue = blendChannel(color.blue, surface.blue, alpha, inverseAlpha)
        return Color(red, green, blue, CHANNEL_MAX)
    }

    private fun blendChannel(
        foreground: Int,
        background: Int,
        alpha: Double,
        inverseAlpha: Double,
    ): Int = (foreground * alpha + background * inverseAlpha).roundToInt().coerceIn(0, CHANNEL_MAX)

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

    private fun render(
        component: VcsColorPreviewComponent,
        size: Dimension = component.preferredSize,
    ): BufferedImage {
        component.setSize(size)
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun BufferedImage.colorAt(
        x: Int,
        y: Int,
    ): Color = Color(getRGB(x, y), true)

    private data class PreviewCase(
        val category: VcsColorCategory,
        val keyName: String,
        val mode: VcsWriteMode,
    )

    private data class RowBodyCase(
        val category: VcsColorCategory,
        val keyName: String,
        val y: Int,
    )

    private companion object {
        private const val CHANNEL_MAX = 255
        private const val PROJECT_DOT_X = 24
        private const val MAX_MINIMUM_WIDTH_RATIO_NUMERATOR = 2
        private const val MAX_MINIMUM_WIDTH_RATIO_DENOMINATOR = 3
        private const val PROJECT_DOT_Y = 33
        private const val DIFF_STRIPE_X = 210
        private const val DIFF_BODY_X = 430
        private const val GUTTER_MARKER_X = 202
        private const val FIRST_CODE_ROW_Y = 30
        private const val INSERTED_ROW_Y = 50
        private const val CONFLICT_ROW_Y = 70
        private const val DELETED_ROW_Y = 90
        private const val UNCHANGED_ROW_Y = 110
        private const val NARROW_BLAME_BACKGROUND_X = 268
        private const val BLAME_BACKGROUND_X = 458
        private const val FIRST_BLAME_ROW_Y = 30
        private const val LAST_BLAME_ROW_Y = 128
        private const val OUTER_SURFACE_X = 20
        private const val BOTTOM_PADDING_Y = 150
        private val DIFF_KEYS = listOf("DIFF_MODIFIED", "DIFF_INSERTED", "DIFF_DELETED")
        private val ROW_BODY_CASES =
            listOf(
                RowBodyCase(VcsColorCategory.DIFF_VIEWER, "DIFF_MODIFIED", FIRST_CODE_ROW_Y),
                RowBodyCase(VcsColorCategory.DIFF_VIEWER, "DIFF_INSERTED", INSERTED_ROW_Y),
                RowBodyCase(VcsColorCategory.CONFLICT_MARKERS, "DIFF_CONFLICT", CONFLICT_ROW_Y),
                RowBodyCase(VcsColorCategory.DIFF_VIEWER, "DIFF_DELETED", DELETED_ROW_Y),
            )
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
