package dev.ayuislands.settings

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.indent.IndentPalette
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.util.Collections
import javax.swing.JPanel

/** Detached native observations belong to preview preparation, never to shared settings or edit rules. */
internal data class IgnorePreviewInput(
    val code: String,
    val request: SyntaxIntensityApplicator.Request,
    val originalStyles: Map<TextAttributesKey, TextAttributes>,
)

internal data class PluginsPreviewInput(
    val settings: PluginsSettings,
    val accentHex: String?,
    val variant: AyuVariant?,
    val ignore: IgnorePreviewInput?,
)

internal data class IgnorePreview(
    val code: String,
    val styles: Map<TextAttributesKey, TextAttributes>,
)

internal data class PluginsPreviewView(
    val settings: PluginsSettings,
    val accent: Color?,
    val indentColors: List<Color>,
    val ignore: IgnorePreview?,
)

/** Prepare once from explicit observations; painting only consumes the resulting detached values. */
internal object PluginsPreview {
    fun prepare(
        input: PluginsPreviewInput,
        syntaxColors: (SyntaxIntensityApplicator.Request) -> Map<TextAttributesKey, TextAttributes> =
            SyntaxIntensityApplicator::compute,
    ): PluginsPreviewView {
        val accent = input.accentHex?.let(AccentHex::of)
        val ignore =
            input.ignore?.let { observed ->
                val styles =
                    if (input.settings.integrations.ignoreColorsEnabled) {
                        syntaxColors(observed.request)
                    } else {
                        observed.originalStyles
                    }
                IgnorePreview(observed.code, Collections.unmodifiableMap(styles.mapValues { it.value.clone() }))
            }
        return PluginsPreviewView(
            input.settings,
            accent?.let { ColorUtil.fromHex(it.value) },
            indentColors(input, accent),
            ignore,
        )
    }

    private fun indentColors(
        input: PluginsPreviewInput,
        accent: AccentHex?,
    ): List<Color> {
        if (accent == null) return emptyList()
        val requested = input.settings.integrations
        val alpha = IndentPreset.resolveAlpha(requested.indentPreset, requested.indentAlpha)
        val palette =
            input.variant?.let { IndentPalette.forAccent(accent.value, it) }
                ?: IndentPalette.forExternalAccent(accent.value)
        return palette
            .toColorStrings(
                alpha,
                requested.highlightErrors,
            ).map { encoded ->
                ColorUtil.toAlpha(
                    ColorUtil.fromHex(encoded.drop(ALPHA_DIGITS)),
                    encoded.take(ALPHA_DIGITS).toInt(HEX_RADIX),
                )
            }
    }

    private const val HEX_RADIX = 16
    private const val ALPHA_DIGITS = 2
}

internal enum class PluginSampleKind(
    val title: String,
    val tooltip: String,
) {
    IGNORE("Ignore colors", "Ignore colors sample"),
    CODE_GLANCE("CodeGlance Pro viewport", "CodeGlance Pro sample"),
    INDENT("Indent Rainbow guides", "Indent Rainbow sample"),
}

/** Illustrative feature-local sample, clipped to its own bounds and independent of native plugin state. */
internal class PluginsSample(
    private val kind: PluginSampleKind,
) : JPanel() {
    private var prepared: PluginsPreviewView? = null

    init {
        preferredSize = Dimension(JBUI.scale(SAMPLE_WIDTH), JBUI.scale(SAMPLE_HEIGHT))
        minimumSize = Dimension(JBUI.scale(MIN_WIDTH), JBUI.scale(SAMPLE_HEIGHT))
        toolTipText = kind.tooltip
        getAccessibleContext().accessibleName = kind.tooltip
    }

    fun render(view: PluginsPreviewView) {
        prepared = view
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val view = prepared ?: return
        val canvas = graphics.create(0, 0, width, height) as Graphics2D
        try {
            PreviewChromePainter.paintPanelFrame(
                canvas,
                Rectangle(0, 0, width, height),
                background ?: JBColor.background(),
            )
            canvas.font = JBUI.Fonts.smallFont()
            canvas.color = foreground ?: JBColor.foreground()
            canvas.drawString("${kind.title} · sample", JBUI.scale(PADDING), JBUI.scale(TITLE_Y))
            when (kind) {
                PluginSampleKind.IGNORE -> paintIgnore(canvas, view)
                PluginSampleKind.CODE_GLANCE -> paintMinimap(canvas, view)
                PluginSampleKind.INDENT -> paintIndent(canvas, view)
            }
        } finally {
            canvas.dispose()
        }
    }

    private fun paintIgnore(
        canvas: Graphics2D,
        view: PluginsPreviewView,
    ) {
        val sample = view.ignore
        if (sample == null || sample.styles.isEmpty()) {
            message(canvas, "Color observations unavailable")
            return
        }
        val styles = sample.styles.entries.associate { it.key.externalName to it.value }
        sample.code.lineSequence().take(MAX_LINES).forEachIndexed { index, line ->
            val key =
                when {
                    line.startsWith("#") -> "IGNORE.COMMENT"
                    line.startsWith("!") -> "IGNORE.NEGATION"
                    else -> "IGNORE.VALUE"
                }
            val attributes = styles[key]
            canvas.color = attributes?.foregroundColor ?: foreground ?: JBColor.foreground()
            canvas.font = JBUI.Fonts.smallFont().deriveFont(attributes?.fontType ?: java.awt.Font.PLAIN)
            canvas.drawString(line, JBUI.scale(PADDING), JBUI.scale(CONTENT_Y + index * LINE_HEIGHT))
        }
    }

    private fun paintMinimap(
        canvas: Graphics2D,
        view: PluginsPreviewView,
    ) {
        if (!view.settings.integrations.codeGlanceEnabled) {
            message(canvas, "Viewport sync off")
            return
        }
        val accent = view.accent
        if (accent == null) {
            message(canvas, "Accent observation unavailable")
            return
        }
        canvas.color = JBColor.GRAY
        repeat(MINIMAP_LINES) { line ->
            val length = width / 2 + (line % LINE_VARIANTS) * JBUI.scale(LINE_STEP)
            canvas.fillRect(
                JBUI.scale(PADDING),
                JBUI.scale(CONTENT_Y + line * MINIMAP_GAP),
                length,
                JBUI.scale(LINE_THICKNESS),
            )
        }
        canvas.color = ColorUtil.toAlpha(accent, VIEWPORT_ALPHA)
        canvas.fillRect(
            JBUI.scale(PADDING),
            JBUI.scale(VIEWPORT_Y),
            width - JBUI.scale(PADDING * 2),
            JBUI.scale(VIEWPORT_HEIGHT),
        )
        canvas.color = accent
        canvas.drawRect(
            JBUI.scale(PADDING),
            JBUI.scale(VIEWPORT_Y),
            width - JBUI.scale(PADDING * 2),
            JBUI.scale(VIEWPORT_HEIGHT),
        )
    }

    private fun paintIndent(
        canvas: Graphics2D,
        view: PluginsPreviewView,
    ) {
        val requested = view.settings.integrations
        if (!requested.indentEnabled) {
            message(canvas, "Guide sync off")
            return
        }
        if (view.indentColors.isEmpty()) {
            message(canvas, "Accent or alpha unavailable")
            return
        }
        view.indentColors.take(INDENT_BANDS).forEachIndexed { index, color ->
            canvas.color = color
            canvas.fillRect(
                JBUI.scale(PADDING + index * INDENT_STEP),
                JBUI.scale(CONTENT_Y),
                JBUI.scale(INDENT_WIDTH),
                JBUI.scale(INDENT_HEIGHT),
            )
        }
        canvas.color = foreground ?: JBColor.foreground()
        canvas.drawString(
            "${requested.indentPreset ?: "Not set"} · alpha ${view.indentColors.first().alpha}",
            JBUI.scale(PADDING),
            JBUI.scale(DETAIL_Y),
        )
    }

    private fun message(
        canvas: Graphics2D,
        text: String,
    ) {
        canvas.drawString(text, JBUI.scale(PADDING), JBUI.scale(CONTENT_Y))
    }

    private companion object {
        const val SAMPLE_WIDTH = 320
        const val MIN_WIDTH = 160
        const val SAMPLE_HEIGHT = 120
        const val PADDING = 10
        const val TITLE_Y = 18
        const val CONTENT_Y = 40
        const val DETAIL_Y = 108
        const val LINE_HEIGHT = 15
        const val MAX_LINES = 5
        const val MINIMAP_LINES = 10
        const val MINIMAP_GAP = 6
        const val LINE_VARIANTS = 3
        const val LINE_STEP = 18
        const val LINE_THICKNESS = 2
        const val VIEWPORT_Y = 48
        const val VIEWPORT_HEIGHT = 26
        const val VIEWPORT_ALPHA = 38
        const val INDENT_BANDS = 7
        const val INDENT_STEP = 26
        const val INDENT_WIDTH = 18
        const val INDENT_HEIGHT = 46
    }
}
