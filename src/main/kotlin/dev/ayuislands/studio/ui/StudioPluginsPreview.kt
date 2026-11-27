package dev.ayuislands.studio.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.indent.IndentPalette
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.settings.PreviewChromePainter
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioPluginsToggle
import dev.ayuislands.studio.session.StudioPluginsSettings
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.JPanel

internal data class StudioPluginCodeLine(
    val text: String,
    val color: Color,
    val fontStyle: Int = Font.PLAIN,
)

/** Detached observations of the effective accent and .ignore sample colors. */
internal data class StudioPluginsPreviewFacts(
    val accent: AccentHex?,
    val variant: AyuVariant?,
    val nativeIgnore: StudioList<StudioPluginCodeLine>?,
    val ayuIgnore: StudioList<StudioPluginCodeLine>?,
)

internal data class StudioPluginsPreviewView(
    val ignore: StudioList<StudioPluginCodeLine>?,
    val minimapEnabled: Boolean,
    val minimapAccent: Color?,
    val indentEnabled: Boolean,
    val indentColors: StudioList<Color>,
    val indentErrorColor: Color?,
)

/** Prepare comparison pixels from shared settings and supplied observations. */
internal object StudioPluginsPreview {
    fun prepare(
        settings: StudioPluginsSettings,
        facts: StudioPluginsPreviewFacts,
    ): StudioPluginsPreviewView {
        val indentEnabled = settings.isEnabled(StudioPluginsToggle.INDENT_RAINBOW_INTEGRATION)
        val minimapEnabled = settings.isEnabled(StudioPluginsToggle.CODE_GLANCE_INTEGRATION)
        val highlightErrors = settings.isEnabled(StudioPluginsToggle.INDENT_ERRORS)
        val indentPalette = if (indentEnabled) indentColors(settings, facts, highlightErrors) else emptyList()
        val ignore =
            if (settings.isEnabled(StudioPluginsToggle.IGNORE_SYNTAX_COLORS)) facts.ayuIgnore else facts.nativeIgnore
        return StudioPluginsPreviewView(
            ignore = ignore,
            minimapEnabled = minimapEnabled,
            minimapAccent = facts.accent?.takeIf { minimapEnabled }?.toColor(),
            indentEnabled = indentEnabled,
            // The runtime palette reserves its first slot for malformed indentation.
            indentColors = StudioList(indentPalette.drop(1)),
            indentErrorColor = indentPalette.firstOrNull().takeIf { highlightErrors },
        )
    }

    private fun indentColors(
        settings: StudioPluginsSettings,
        facts: StudioPluginsPreviewFacts,
        highlightErrors: Boolean,
    ): List<Color> {
        val accent = facts.accent ?: return emptyList()
        val palette =
            facts.variant?.let { IndentPalette.forAccent(accent.value, it) }
                ?: IndentPalette.forExternalAccent(accent.value)
        val alpha = IndentPreset.effectiveAlpha(settings.indentPreset, settings.indentAlpha)
        return palette.toColorStrings(alpha, highlightErrors).map { encoded ->
            Color(encoded.toUInt(HEX_RADIX).toInt(), true)
        }
    }

    private const val HEX_RADIX = 16
}

internal enum class StudioPluginSampleKind(
    val title: String,
    val accessibleName: String,
) {
    IGNORE(".ignore colors", ".ignore color sample"),
    MINIMAP("CodeGlance Pro viewport", "CodeGlance Pro viewport sample"),
    INDENT("Indent Rainbow guides", "Indent Rainbow guide sample"),
}

/** A compact, illustrative sample. Runtime integrations are never attached to it. */
internal class StudioPluginSample(
    private val kind: StudioPluginSampleKind,
) : JPanel() {
    private var view: StudioPluginsPreviewView? = null

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(SAMPLE_WIDTH), JBUI.scale(SAMPLE_HEIGHT))
        minimumSize = Dimension(JBUI.scale(MIN_WIDTH), JBUI.scale(SAMPLE_HEIGHT))
        getAccessibleContext().accessibleName = kind.accessibleName
    }

    fun render(preview: StudioPluginsPreviewView?) {
        view = preview
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val sample = view ?: return
        val canvas = graphics.create(0, 0, width, height) as Graphics2D
        try {
            PreviewChromePainter.paintPanelFrame(
                canvas,
                Rectangle(0, 0, width, height),
                background ?: JBColor.background(),
            )
            canvas.font = JBUI.Fonts.smallFont()
            canvas.color = foreground ?: JBColor.foreground()
            canvas.drawString("${kind.title} · sample", JBUI.scale(PADDING), JBUI.scale(TITLE_BASELINE))
            when (kind) {
                StudioPluginSampleKind.IGNORE -> paintIgnore(canvas, sample.ignore)
                StudioPluginSampleKind.MINIMAP -> paintMinimap(canvas, sample)
                StudioPluginSampleKind.INDENT -> paintIndent(canvas, sample)
            }
        } finally {
            canvas.dispose()
        }
    }

    private fun paintIgnore(
        canvas: Graphics2D,
        lines: List<StudioPluginCodeLine>?,
    ) {
        if (lines.isNullOrEmpty()) {
            message(canvas, "Color observations unavailable")
            return
        }
        lines.take(MAX_CODE_LINES).forEachIndexed { index, line ->
            canvas.color = line.color
            canvas.font = JBUI.Fonts.smallFont().deriveFont(line.fontStyle)
            val baseline = CONTENT_BASELINE + index * LINE_GAP
            canvas.drawString(line.text, JBUI.scale(PADDING), JBUI.scale(baseline))
        }
    }

    private fun paintMinimap(
        canvas: Graphics2D,
        sample: StudioPluginsPreviewView,
    ) {
        val accent = sample.minimapAccent
        if (accent == null) {
            message(canvas, if (sample.minimapEnabled) "Accent observation unavailable" else "Viewport sync off")
            return
        }
        val padding = JBUI.scale(PADDING)
        val contentWidth = (width - padding * 2).coerceAtLeast(1)
        canvas.color = JBColor.GRAY
        repeat(MINIMAP_LINES) { line ->
            val length = contentWidth / 2 + (line % LINE_VARIANTS) * JBUI.scale(LINE_STEP)
            val y = JBUI.scale(CONTENT_BASELINE + line * MINIMAP_LINE_GAP)
            canvas.fillRect(padding, y, length, JBUI.scale(LINE_THICKNESS))
        }
        val viewportTop = JBUI.scale(VIEWPORT_TOP)
        val viewportHeight = JBUI.scale(VIEWPORT_HEIGHT)
        canvas.color = ColorUtil.toAlpha(accent, VIEWPORT_FILL_ALPHA)
        canvas.fillRect(padding, viewportTop, contentWidth, viewportHeight)
        canvas.color = accent
        canvas.stroke = BasicStroke(JBUI.scale(VIEWPORT_STROKE).toFloat())
        canvas.drawRect(padding, viewportTop, contentWidth, viewportHeight)
    }

    private fun paintIndent(
        canvas: Graphics2D,
        sample: StudioPluginsPreviewView,
    ) {
        if (sample.indentColors.isEmpty()) {
            message(canvas, if (sample.indentEnabled) "Accent observation unavailable" else "Guide sync off")
            return
        }
        val padding = JBUI.scale(PADDING)
        val gap = JBUI.scale(INDENT_GAP)
        val bandWidth = ((width - padding * 2) / (INDENT_BANDS + 1)).coerceAtLeast(gap + 1)
        sample.indentColors.take(INDENT_BANDS).forEachIndexed { index, color ->
            canvas.color = color
            canvas.fillRect(
                padding + index * bandWidth,
                JBUI.scale(INDENT_TOP),
                bandWidth - gap,
                JBUI.scale(INDENT_HEIGHT),
            )
        }
        sample.indentErrorColor?.let { errorColor ->
            canvas.color = errorColor
            canvas.fillRect(
                padding + INDENT_BANDS * bandWidth,
                JBUI.scale(INDENT_TOP + INDENT_HEIGHT - INDENT_ERROR_HEIGHT),
                bandWidth - gap,
                JBUI.scale(INDENT_ERROR_HEIGHT),
            )
        }
    }

    private fun message(
        canvas: Graphics2D,
        text: String,
    ) {
        canvas.drawString(text, JBUI.scale(PADDING), JBUI.scale(CONTENT_BASELINE))
    }

    private companion object {
        const val SAMPLE_WIDTH = 260
        const val MIN_WIDTH = 150
        const val SAMPLE_HEIGHT = 90
        const val PADDING = 10
        const val TITLE_BASELINE = 18
        const val CONTENT_BASELINE = 38
        const val MAX_CODE_LINES = 3
        const val LINE_GAP = 15
        const val MINIMAP_LINES = 6
        const val MINIMAP_LINE_GAP = 6
        const val LINE_VARIANTS = 3
        const val LINE_STEP = 12
        const val LINE_THICKNESS = 2
        const val VIEWPORT_TOP = 45
        const val VIEWPORT_HEIGHT = 20
        const val VIEWPORT_FILL_ALPHA = 38
        const val VIEWPORT_STROKE = 2
        const val INDENT_BANDS = 7
        const val INDENT_GAP = 3
        const val INDENT_TOP = 32
        const val INDENT_HEIGHT = 40
        const val INDENT_ERROR_HEIGHT = 12
    }
}
