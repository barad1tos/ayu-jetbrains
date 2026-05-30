package dev.ayuislands.settings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.vcs.VcsColorBlender
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPalette
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsIntensity
import dev.ayuislands.vcs.VcsWriteMode
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import kotlin.math.roundToInt

internal data class VcsPreviewIntensities(
    val diffViewer: Int = VcsColorPreset.AMBIENT_SLIDER,
    val projectView: Int = VcsColorPreset.AMBIENT_SLIDER,
    val editorGutter: Int = VcsColorPreset.AMBIENT_SLIDER,
    val conflictMarkers: Int = VcsColorPreset.AMBIENT_SLIDER,
    val blameGutter: Int = VcsColorPreset.AMBIENT_SLIDER,
) {
    fun valueFor(category: VcsColorCategory): Int =
        when (category) {
            VcsColorCategory.DIFF_VIEWER -> diffViewer
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS -> projectView
            VcsColorCategory.EDITOR_GUTTER -> editorGutter
            VcsColorCategory.CONFLICT_MARKERS -> conflictMarkers
            VcsColorCategory.BLAME_GUTTER -> blameGutter
            else -> VcsColorPreset.AMBIENT_SLIDER
        }.coerceIn(VcsIntensity.MIN, VcsIntensity.MAX)
}

/** Unified visual preview for every VCS color category exposed by the VCS settings tab. */
internal class VcsColorPreviewComponent(
    private var variant: AyuVariant,
    private var intensities: VcsPreviewIntensities = VcsPreviewIntensities(),
) : JComponent() {
    init {
        isOpaque = false
        toolTipText = "VCS color preview"
    }

    fun updatePreview(
        variant: AyuVariant,
        intensities: VcsPreviewIntensities,
    ) {
        this.variant = variant
        this.intensities = intensities
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(PREVIEW_WIDTH), JBUI.scale(PREVIEW_HEIGHT))

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val surface = editorSurface()
            val arc = JBUI.scale(ARC)
            g2.color = surface
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

            val padding = JBUI.scale(PADDING)
            val columnGap = JBUI.scale(COLUMN_GAP)
            val contentHeight = (height - padding * 2).coerceAtLeast(1)
            val projectWidth = JBUI.scale(PROJECT_WIDTH)
            val editorX = padding + projectWidth + columnGap
            val editorWidth = (width - editorX - padding).coerceAtLeast(1)

            paintProjectPanel(g2, padding, padding, projectWidth, contentHeight)
            paintEditorPanel(g2, editorX, padding, editorWidth, contentHeight)
        } finally {
            g2.dispose()
        }
    }

    private fun paintProjectPanel(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val arc = JBUI.scale(INNER_ARC)
        g2.color = panelSurface()
        g2.fillRoundRect(x, y, width, height, arc, arc)
        g2.color = JBColor.border()
        g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc)

        g2.font = JBUI.Fonts.smallFont()
        val rowHeight = JBUI.scale(PROJECT_ROW_HEIGHT)
        var rowY = y + JBUI.scale(PROJECT_TOP_PADDING)
        for (row in PROJECT_ROWS) {
            val baseline = rowY + (rowHeight - g2.fontMetrics.height) / 2 + g2.fontMetrics.ascent
            g2.color = previewColor(VcsColorCategory.PROJECT_VIEW_FILE_STATUS, row.keyName, VcsWriteMode.COLOR_KEY)
            g2.fillRect(
                x + JBUI.scale(FILE_DOT_X),
                rowY + JBUI.scale(FILE_DOT_Y),
                JBUI.scale(FILE_DOT),
                JBUI.scale(FILE_DOT),
            )
            g2.drawString(row.text, x + JBUI.scale(FILE_TEXT_X), baseline)
            rowY += rowHeight
        }
    }

    private fun paintEditorPanel(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val arc = JBUI.scale(INNER_ARC)
        g2.color = editorSurface()
        g2.fillRoundRect(x, y, width, height, arc, arc)
        g2.color = JBColor.border()
        g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc)

        val gutterWidth = JBUI.scale(GUTTER_WIDTH)
        val blameWidth = JBUI.scale(BLAME_WIDTH).coerceAtMost(width / BLAME_MAX_WIDTH_DIVISOR)
        val geometry =
            EditorGeometry(
                gutterX = x,
                codeX = x + gutterWidth,
                codeWidth = (width - gutterWidth - blameWidth).coerceAtLeast(1),
                blameX = x + width - blameWidth,
            )

        g2.color = panelSurface()
        g2.fillRect(x + 1, y + 1, gutterWidth - 1, height - 2)
        g2.font = JBUI.Fonts.smallFont()
        paintBlameGutter(g2, geometry.blameX, y + 1, blameWidth - 1, height - 2)

        val rowHeight = JBUI.scale(CODE_ROW_HEIGHT)
        var rowY = y + JBUI.scale(CODE_TOP_PADDING)
        for (row in CODE_ROWS) {
            paintCodeRow(g2, row, geometry, rowY, rowHeight)
            rowY += rowHeight
        }
    }

    private fun paintBlameGutter(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val rowHeight = JBUI.scale(CODE_ROW_HEIGHT)
        var rowY = y + JBUI.scale(CODE_TOP_PADDING) - 1
        for (row in BLAME_ROWS) {
            g2.color = previewColor(VcsColorCategory.BLAME_GUTTER, row.colorKey, VcsWriteMode.COLOR_KEY)
            g2.fillRect(x, rowY, width, rowHeight)
            g2.color =
                previewColor(
                    VcsColorCategory.BLAME_GUTTER,
                    "ANNOTATIONS_LAST_COMMIT_COLOR",
                    VcsWriteMode.COLOR_KEY,
                )
            val baseline = rowY + (rowHeight - g2.fontMetrics.height) / 2 + g2.fontMetrics.ascent
            g2.drawString(row.text, x + JBUI.scale(BLAME_TEXT_X), baseline)
            rowY += rowHeight
        }
        val paintedHeight = rowHeight * BLAME_ROWS.size
        if (paintedHeight < height) {
            g2.color = panelSurface()
            g2.fillRect(x, y + JBUI.scale(CODE_TOP_PADDING) + paintedHeight - 1, width, height - paintedHeight)
        }
    }

    private fun paintCodeRow(
        g2: Graphics2D,
        row: PreviewRow,
        geometry: EditorGeometry,
        y: Int,
        height: Int,
    ) {
        val stripeWidth = JBUI.scale(STRIPE_WIDTH)
        val stripeGap = JBUI.scale(STRIPE_GAP)
        val rowBackground =
            row.keyName?.let {
                compositeOverSurface(previewColor(row.category, it, VcsWriteMode.TEXT_ATTR_BG), editorSurface())
            }
                ?: editorSurface()

        g2.color = rowBackground
        g2.fillRect(geometry.codeX, y, geometry.codeWidth, height)
        if (row.keyName != null) {
            g2.color = previewColor(row.category, row.keyName, VcsWriteMode.COLOR_KEY)
            g2.fillRect(geometry.codeX, y, stripeWidth, height)
        }
        if (row.hasGutterMarker) {
            g2.color = previewColor(VcsColorCategory.EDITOR_GUTTER, "MODIFIED_LINES_COLOR", VcsWriteMode.COLOR_KEY)
            g2.fillRect(
                geometry.gutterX + JBUI.scale(GUTTER_MARKER_X),
                y + JBUI.scale(GUTTER_MARKER_Y),
                JBUI.scale(GUTTER_MARKER_WIDTH),
                height - JBUI.scale(GUTTER_MARKER_INSET),
            )
        }

        g2.color = UIUtil.getContextHelpForeground()
        val baseline = y + (height - g2.fontMetrics.height) / 2 + g2.fontMetrics.ascent
        g2.drawString(row.lineNumber, geometry.gutterX + JBUI.scale(LINE_NUMBER_X), baseline)
        g2.color = UIUtil.getLabelForeground()
        g2.drawString(row.text, geometry.codeX + stripeWidth + stripeGap, baseline)
        g2.color = JBColor.border()
        g2.drawLine(geometry.blameX, y, geometry.blameX, y + height)
    }

    private fun previewColor(
        category: VcsColorCategory,
        keyName: String,
        mode: VcsWriteMode,
    ): Color {
        val entry =
            VcsColorPalette.entriesFor(category).first {
                it.keyName == keyName && it.mode == mode
            }
        val (base, target) = VcsColorPalette.endpoints(entry, variant)
        return VcsColorBlender.blend(base, target, VcsIntensity.of(intensities.valueFor(category)))
    }

    private fun editorSurface(): Color =
        when (variant) {
            AyuVariant.DARK -> DARK_SURFACE
            AyuVariant.MIRAGE -> MIRAGE_SURFACE
            AyuVariant.LIGHT -> LIGHT_SURFACE
        }

    private fun panelSurface(): Color =
        when (variant) {
            AyuVariant.DARK -> DARK_PANEL_SURFACE
            AyuVariant.MIRAGE -> MIRAGE_PANEL_SURFACE
            AyuVariant.LIGHT -> LIGHT_PANEL_SURFACE
        }

    private fun compositeOverSurface(
        foreground: Color,
        surface: Color,
    ): Color {
        val alpha = foreground.alpha / CHANNEL_MAX.toDouble()
        val inverseAlpha = 1.0 - alpha
        return Color(
            blendChannel(foreground.red, surface.red, alpha, inverseAlpha),
            blendChannel(foreground.green, surface.green, alpha, inverseAlpha),
            blendChannel(foreground.blue, surface.blue, alpha, inverseAlpha),
        )
    }

    private fun blendChannel(
        foreground: Int,
        surface: Int,
        alpha: Double,
        inverseAlpha: Double,
    ): Int = (foreground * alpha + surface * inverseAlpha).roundToInt().coerceIn(0, CHANNEL_MAX)

    @TestOnly
    internal fun colorForTest(
        category: VcsColorCategory,
        keyName: String,
        mode: VcsWriteMode,
    ): Color = previewColor(category, keyName, mode)

    @TestOnly
    internal fun intensityForTest(category: VcsColorCategory): Int = intensities.valueFor(category)

    private data class PreviewRow(
        val lineNumber: String,
        val category: VcsColorCategory,
        val keyName: String?,
        val text: String,
        val hasGutterMarker: Boolean = false,
    )

    private data class EditorGeometry(
        val gutterX: Int,
        val codeX: Int,
        val codeWidth: Int,
        val blameX: Int,
    )

    private data class ProjectRow(
        val keyName: String,
        val text: String,
    )

    private data class BlameRow(
        val colorKey: String,
        val text: String,
    )

    private companion object {
        private const val PREVIEW_WIDTH = 560
        private const val PREVIEW_HEIGHT = 154
        private const val PADDING = 10
        private const val COLUMN_GAP = 10
        private const val PROJECT_WIDTH = 154
        private const val PROJECT_TOP_PADDING = 12
        private const val PROJECT_ROW_HEIGHT = 22
        private const val FILE_DOT_X = 11
        private const val FILE_DOT_Y = 8
        private const val FILE_DOT = 7
        private const val FILE_TEXT_X = 26
        private const val GUTTER_WIDTH = 34
        private const val BLAME_WIDTH = 94
        private const val BLAME_MAX_WIDTH_DIVISOR = 3
        private const val CODE_TOP_PADDING = 12
        private const val CODE_ROW_HEIGHT = 20
        private const val LINE_NUMBER_X = 9
        private const val BLAME_TEXT_X = 8
        private const val GUTTER_MARKER_X = 27
        private const val GUTTER_MARKER_Y = 3
        private const val GUTTER_MARKER_WIDTH = 3
        private const val GUTTER_MARKER_INSET = 6
        private const val STRIPE_WIDTH = 4
        private const val STRIPE_GAP = 8
        private const val ARC = 8
        private const val INNER_ARC = 6
        private const val CHANNEL_MAX = 255

        private val DARK_SURFACE = Color(0x0D1017)
        private val MIRAGE_SURFACE = Color(0x1F2430)
        private val LIGHT_SURFACE = Color(0xFAFAFA)
        private val DARK_PANEL_SURFACE = Color(0x141923)
        private val MIRAGE_PANEL_SURFACE = Color(0x252B38)
        private val LIGHT_PANEL_SURFACE = Color(0xEFF2F5)

        private val PROJECT_ROWS =
            listOf(
                ProjectRow("FILESTATUS_MODIFIED", "PresetPreview.kt"),
                ProjectRow("FILESTATUS_ADDED", "VcsColors.xml"),
                ProjectRow("FILESTATUS_DELETED", "old-palette.icls"),
                ProjectRow("FILESTATUS_IDEA_FILESTATUS_IGNORED", "build/"),
            )

        private val CODE_ROWS =
            listOf(
                PreviewRow("12", VcsColorCategory.DIFF_VIEWER, "DIFF_MODIFIED", "fun renderPreset() {", true),
                PreviewRow("13", VcsColorCategory.DIFF_VIEWER, "DIFF_INSERTED", "+ val selected = preset"),
                PreviewRow("14", VcsColorCategory.CONFLICT_MARKERS, "DIFF_CONFLICT", "current change", true),
                PreviewRow("15", VcsColorCategory.DIFF_VIEWER, "DIFF_DELETED", "- val stale = swatch"),
                PreviewRow("16", VcsColorCategory.DIFF_VIEWER, null, "  return selected"),
                PreviewRow("17", VcsColorCategory.DIFF_VIEWER, null, "}"),
            )

        private val BLAME_ROWS =
            listOf(
                BlameRow("VCS_ANNOTATIONS_COLOR_1", "a91f 1m"),
                BlameRow("VCS_ANNOTATIONS_COLOR_2", "8d42 2h"),
                BlameRow("VCS_ANNOTATIONS_COLOR_3", "54ca 1d"),
                BlameRow("VCS_ANNOTATIONS_COLOR_4", "21bf 4d"),
                BlameRow("VCS_ANNOTATIONS_COLOR_5", "main"),
                BlameRow("ANNOTATIONS_COLOR", "HEAD"),
            )
    }
}
