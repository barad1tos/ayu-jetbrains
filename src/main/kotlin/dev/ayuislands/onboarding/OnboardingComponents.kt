@file:Suppress("MatchingDeclarationName")

package dev.ayuislands.onboarding

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

// Shared visual primitives for onboarding wizard panels (free and premium).
// Card painting stays panel-local because the free wizard's variant cards
// and the premium wizard's preset cards share almost no paint code.

/**
 * Tracks components whose sizes, fonts, and visibility adapt to the panel's
 * content scale factor. Components register during [loadContent] and get
 * rescaled on every [apply] call triggered by the resize handler.
 */
internal class ContentScaler {
    private data class CardEntry(
        val component: JComponent,
        val baseWidth: Int,
        val baseHeight: Int,
    )

    private data class LabelEntry(
        val label: JBLabel,
        val baseFontPx: Int,
        val fontStyle: Int,
    )

    private data class GapEntry(
        val strut: Component,
        val basePx: Int,
        val horizontal: Boolean,
    )

    private data class HideableEntry(
        val component: JComponent,
        val hideBelow: Float,
    )

    var currentScale: Float = 1.0f
        private set

    private val cards = mutableListOf<CardEntry>()
    private val labels = mutableListOf<LabelEntry>()
    private val gaps = mutableListOf<GapEntry>()
    private val hideables = mutableListOf<HideableEntry>()

    fun registerCard(
        component: JComponent,
        baseWidth: Int,
        baseHeight: Int,
    ) {
        cards += CardEntry(component, baseWidth, baseHeight)
    }

    fun registerLabel(
        label: JBLabel,
        baseFontPx: Int,
        fontStyle: Int,
    ) {
        labels += LabelEntry(label, baseFontPx, fontStyle)
    }

    fun registerGap(
        strut: Component,
        basePx: Int,
        horizontal: Boolean = false,
    ) {
        gaps += GapEntry(strut, basePx, horizontal)
    }

    fun registerHideable(
        component: JComponent,
        hideBelow: Float,
    ) {
        hideables += HideableEntry(component, hideBelow)
    }

    fun apply(scale: Float) {
        currentScale = scale
        for (entry in cards) {
            val w = JBUI.scale((entry.baseWidth * scale).toInt())
            val h = JBUI.scale((entry.baseHeight * scale).toInt())
            val dim = Dimension(w, h)
            entry.component.preferredSize = dim
            entry.component.minimumSize = dim
            entry.component.maximumSize = dim
        }
        for (entry in labels) {
            val px = (entry.baseFontPx * scale).toInt().coerceAtLeast(MIN_FONT_PX)
            entry.label.font = entry.label.font.deriveFont(entry.fontStyle, JBUI.scale(px).toFloat())
        }
        for (entry in gaps) {
            val px = JBUI.scale((entry.basePx * scale).toInt())
            if (entry.horizontal) {
                val dim = Dimension(px, 0)
                entry.strut.preferredSize = dim
                entry.strut.minimumSize = dim
                entry.strut.maximumSize = Dimension(px, Short.MAX_VALUE.toInt())
            } else {
                val dim = Dimension(0, px)
                entry.strut.preferredSize = dim
                entry.strut.minimumSize = dim
                entry.strut.maximumSize = Dimension(Short.MAX_VALUE.toInt(), px)
            }
        }
        for (entry in hideables) {
            entry.component.isVisible = scale >= entry.hideBelow
        }
    }

    fun clear() {
        cards.clear()
        labels.clear()
        gaps.clear()
        hideables.clear()
    }

    companion object {
        private const val MIN_FONT_PX = 6
    }
}

@Suppress("MagicNumber")
internal object OnboardingCardChrome {
    const val CARD_ARC = 14
    const val SHADOW_LAYERS = 4
    const val SHADOW_OFFSET_Y = 2
    const val SHADOW_BASE_ALPHA = 12
    const val HIGHLIGHT_HEIGHT = 3
    const val CARD_HOVER_TOP_ALPHA = 40
    const val CARD_HOVER_BOTTOM_ALPHA = 10
    const val CARD_BORDER_HOVER_ALPHA = 100
    val CARD_BG_COLOR: Color = Color(0x17, 0x1B, 0x24)
    val CARD_BORDER_COLOR: Color = Color(0x2A, 0x2F, 0x3A)
    val HIGHLIGHT_TOP_COLOR: Color = Color(255, 255, 255, 8)
    val HIGHLIGHT_BOTTOM_COLOR: Color = Color(255, 255, 255, 0)
}

/**
 * Shared card chrome — drop shadow, fill (hovered gradient or base color),
 * glass highlight clip, and border — used by both wizard panels for their
 * variant cards, preset cards, and footer rail cards.
 */
@Suppress("LongParameterList")
internal fun paintCardChrome(
    g2: Graphics2D,
    width: Int,
    height: Int,
    hovered: Boolean,
    tintColor: Color,
    baseFill: Color = OnboardingCardChrome.CARD_BG_COLOR,
    contentScale: Float = 1.0f,
) {
    val arc = JBUI.scale((OnboardingCardChrome.CARD_ARC * contentScale).toInt().coerceAtLeast(1))

    // Drop shadow
    val shadowOffset = JBUI.scale((OnboardingCardChrome.SHADOW_OFFSET_Y * contentScale).toInt().coerceAtLeast(1))
    for (i in OnboardingCardChrome.SHADOW_LAYERS downTo 1) {
        g2.color = Color(0, 0, 0, OnboardingCardChrome.SHADOW_BASE_ALPHA * i)
        g2.fillRoundRect(
            i,
            i + shadowOffset,
            width - 2 * i,
            height - 2 * i,
            arc,
            arc,
        )
    }

    // Fill
    if (hovered) {
        val topColor =
            Color(tintColor.red, tintColor.green, tintColor.blue, OnboardingCardChrome.CARD_HOVER_TOP_ALPHA)
        val bottomColor =
            Color(tintColor.red, tintColor.green, tintColor.blue, OnboardingCardChrome.CARD_HOVER_BOTTOM_ALPHA)
        g2.paint = GradientPaint(0f, 0f, topColor, 0f, height.toFloat(), bottomColor)
    } else {
        g2.color = baseFill
    }
    g2.fillRoundRect(0, 0, width, height, arc, arc)

    // Glass highlight
    val highlightHeight = JBUI.scale((OnboardingCardChrome.HIGHLIGHT_HEIGHT * contentScale).toInt().coerceAtLeast(1))
    val clip =
        RoundRectangle2D.Float(
            1f,
            1f,
            (width - 2).toFloat(),
            (height - 2).toFloat(),
            arc.toFloat(),
            arc.toFloat(),
        )
    val oldClip = g2.clip
    g2.clip(clip)
    g2.paint =
        GradientPaint(
            0f,
            1f,
            OnboardingCardChrome.HIGHLIGHT_TOP_COLOR,
            0f,
            highlightHeight.toFloat(),
            OnboardingCardChrome.HIGHLIGHT_BOTTOM_COLOR,
        )
    g2.fillRect(0, 0, width, highlightHeight)
    g2.clip = oldClip

    // Border
    g2.color =
        if (hovered) {
            Color(
                tintColor.red,
                tintColor.green,
                tintColor.blue,
                OnboardingCardChrome.CARD_BORDER_HOVER_ALPHA,
            )
        } else {
            OnboardingCardChrome.CARD_BORDER_COLOR
        }
    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
}

/**
 * Lock a JLabel to its intrinsic preferred size on both axes.
 *
 * Without this, BoxLayout.Y_AXIS lets JLabel stretch horizontally (pushing
 * text to the left) and BoxLayout.X_AXIS with glue+label+glue can't center
 * it reliably. Limiting the max size makes glue absorb the slack and the
 * label sit at its natural size.
 */
internal fun clampLabelToPreferred(label: JBLabel) {
    val pref = label.preferredSize
    label.maximumSize = Dimension(pref.width, pref.height)
    label.minimumSize = Dimension(pref.width, pref.height)
}

@Suppress("MagicNumber")
internal object OnboardingColors {
    // Accent (Ayu Mirage palette)
    val ACCENT: Color = Color(0xFF, 0xCC, 0x66)
    val ACCENT_HOVER: Color = Color(0xFF, 0xD8, 0x80)
    val ACCENT_TEXT: Color = Color(0x0B, 0x0E, 0x14)

    // Accent button extras
    val ACCENT_BORDER: Color = Color(0xC0, 0x96, 0x30)
    val ACCENT_HIGHLIGHT: Color = Color(0xFF, 0xE0, 0x99, 60)
    val ACCENT_PRESSED: Color = Color(0xD9, 0xAD, 0x50)

    // Secondary button
    val SECONDARY_BG: Color = Color(0x18, 0x1C, 0x25)
    val SECONDARY_HOVER: Color = Color(0x1E, 0x23, 0x2E)
    val SECONDARY_PRESSED: Color = Color(0x14, 0x18, 0x20)
    val SECONDARY_BORDER: Color = Color(0x3A, 0x40, 0x4C)
    val SECONDARY_HIGHLIGHT: Color = Color(0xFF, 0xFF, 0xFF, 10)
    val SECONDARY_TEXT: Color = Color(0xB0, 0xB8, 0xC4)
}

private const val BTN_ARC = 10
private const val BTN_PADDING_V = 8
private const val BTN_PADDING_H = 20
private const val BTN_WIDTH = 160
private const val BTN_HEIGHT = 36
private const val BTN_FONT_SIZE = 13
private const val HIGHLIGHT_INSET = 1

/** Visual palette for one styled-button variant — drives fill, border, highlight colors. */
private data class StyledButtonPalette(
    val fillDefault: Color,
    val fillHover: Color,
    val fillPressed: Color,
    val border: Color,
    val highlight: Color,
    val textColor: Color,
    val textWeight: Int,
)

private val ACCENT_PALETTE =
    StyledButtonPalette(
        fillDefault = OnboardingColors.ACCENT,
        fillHover = OnboardingColors.ACCENT_HOVER,
        fillPressed = OnboardingColors.ACCENT_PRESSED,
        border = OnboardingColors.ACCENT_BORDER,
        highlight = OnboardingColors.ACCENT_HIGHLIGHT,
        textColor = OnboardingColors.ACCENT_TEXT,
        textWeight = Font.BOLD,
    )

private val SECONDARY_PALETTE =
    StyledButtonPalette(
        fillDefault = OnboardingColors.SECONDARY_BG,
        fillHover = OnboardingColors.SECONDARY_HOVER,
        fillPressed = OnboardingColors.SECONDARY_PRESSED,
        border = OnboardingColors.SECONDARY_BORDER,
        highlight = OnboardingColors.SECONDARY_HIGHLIGHT,
        textColor = OnboardingColors.SECONDARY_TEXT,
        textWeight = Font.PLAIN,
    )

/**
 * Builds a styled wizard button with accent or secondary visual variant.
 * Used by both free and premium onboarding panels.
 */
internal fun createStyledButton(
    text: String,
    isAccent: Boolean,
    onClick: () -> Unit,
    scaler: ContentScaler? = null,
): JPanel {
    val palette = if (isAccent) ACCENT_PALETTE else SECONDARY_PALETTE

    val button =
        object : JPanel() {
            private var hovered = false
            private var pressed = false

            init {
                layout = BorderLayout()
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(BTN_PADDING_V, BTN_PADDING_H)
                val btnSize = Dimension(JBUI.scale(BTN_WIDTH), JBUI.scale(BTN_HEIGHT))
                preferredSize = btnSize
                maximumSize = btnSize
                minimumSize = btnSize
                val label = JBLabel(text)
                label.horizontalAlignment = JBLabel.CENTER
                label.foreground = palette.textColor
                label.font =
                    label.font.deriveFont(
                        palette.textWeight,
                        JBUI.scale(BTN_FONT_SIZE).toFloat(),
                    )
                add(label, BorderLayout.CENTER)
                scaler?.registerCard(this, BTN_WIDTH, BTN_HEIGHT)
                scaler?.registerLabel(label, BTN_FONT_SIZE, palette.textWeight)

                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseEntered(event: MouseEvent) {
                            hovered = true
                            repaint()
                        }

                        override fun mouseExited(event: MouseEvent) {
                            hovered = false
                            pressed = false
                            repaint()
                        }

                        override fun mousePressed(event: MouseEvent) {
                            pressed = true
                            repaint()
                        }

                        override fun mouseReleased(event: MouseEvent) {
                            pressed = false
                            repaint()
                        }

                        override fun mouseClicked(event: MouseEvent) {
                            onClick()
                        }
                    },
                )
            }

            override fun paintComponent(graphics: Graphics) {
                val g2 = graphics as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                val arc = JBUI.scale(BTN_ARC)
                val fill =
                    when {
                        pressed -> palette.fillPressed
                        hovered -> palette.fillHover
                        else -> palette.fillDefault
                    }

                g2.color = fill
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                g2.color = palette.border
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

                val oldClip = g2.clip
                g2.clipRect(1, 1, width - 2, arc)
                g2.color = palette.highlight
                g2.drawRoundRect(
                    HIGHLIGHT_INSET,
                    HIGHLIGHT_INSET,
                    width - HIGHLIGHT_INSET * 2 - 1,
                    height - HIGHLIGHT_INSET * 2 - 1,
                    arc - HIGHLIGHT_INSET,
                    arc - HIGHLIGHT_INSET,
                )
                g2.clip = oldClip
            }
        }
    return button
}

// --------------------------------------------------------------------------
// Wizard layout scaffolding — shared between free and premium onboarding panels.
// --------------------------------------------------------------------------

/** Handle returned by [installWizardContent] — holds references needed by updateDynamicLayout. */
internal data class WizardContentHandle(
    val topStrut: Component,
    val wrapper: JPanel,
)

/**
 * Wraps [content] in the standard wizard layout scaffolding:
 *  - A centered row (glue + content + glue) so the column sits in the middle horizontally.
 *  - A wrapper column: dynamic top strut + centered row + vertical glue + fixed bottom strut.
 *  - Installs the wrapper at [BorderLayout.CENTER] of [host].
 *
 * Returns a [WizardContentHandle] whose `topStrut` should be resized on component resize to
 * track the SVG tagline position, and whose `wrapper` needs `revalidate()` after strut updates.
 */
internal fun installWizardContent(
    host: Container,
    content: JPanel,
    bottomMarginPx: Int,
): WizardContentHandle {
    val centeredRow = JPanel()
    centeredRow.layout = BoxLayout(centeredRow, BoxLayout.X_AXIS)
    centeredRow.isOpaque = false
    centeredRow.add(Box.createHorizontalGlue())
    centeredRow.add(content)
    centeredRow.add(Box.createHorizontalGlue())
    centeredRow.alignmentX = Component.CENTER_ALIGNMENT

    val wrapper = JPanel()
    wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
    wrapper.isOpaque = false
    val strut = Box.createVerticalStrut(0)
    wrapper.add(strut)
    wrapper.add(centeredRow)
    wrapper.add(Box.createVerticalGlue())
    wrapper.add(Box.createVerticalStrut(JBUI.scale(bottomMarginPx)))

    host.add(wrapper, BorderLayout.CENTER)
    return WizardContentHandle(strut, wrapper)
}

/**
 * Static SVG geometry and trial-font constraints — single source of truth per wizard panel
 * so the dynamic-layout math has one data-class parameter instead of eight individual ones.
 */
internal data class WizardSvgGeometry(
    val viewBoxWidth: Double,
    val viewBoxHeight: Double,
    val taglineBottomY: Double,
    val taglineFontPx: Double,
    val topGapPx: Int,
    val trialFontMin: Int,
    val trialFontMax: Int,
)

/**
 * Shared dynamic layout math for the free and premium wizards.
 *
 * Computes the top strut padding so content sits just below the SVG hero tagline,
 * and derives a scale-matched font size for the trial headline from the same cover
 * scale factor the hero SVG uses. Calls [onTrialFontChange] with the final font size
 * so the caller can push it into its trial headline label.
 */
internal fun updateWizardDynamicLayout(
    panelWidth: Int,
    panelHeight: Int,
    handle: WizardContentHandle,
    geometry: WizardSvgGeometry,
    onTrialFontChange: (Float) -> Unit,
) {
    val w = panelWidth.toDouble()
    val h = panelHeight.toDouble()
    if (w <= 0 || h <= 0) return

    val scale = maxOf(w / geometry.viewBoxWidth, h / geometry.viewBoxHeight)
    val svgHeightOnScreen = geometry.viewBoxHeight * scale
    val svgTopY = (h - svgHeightOnScreen) / 2
    val taglineBottomScreenY = svgTopY + geometry.taglineBottomY * scale
    val topPadding =
        (taglineBottomScreenY + JBUI.scale(geometry.topGapPx)).toInt().coerceAtLeast(0)

    val size = Dimension(0, topPadding)
    handle.topStrut.preferredSize = size
    handle.topStrut.maximumSize = Dimension(Int.MAX_VALUE, topPadding)
    handle.topStrut.minimumSize = size

    val trialFontPx =
        (geometry.taglineFontPx * scale)
            .toInt()
            .coerceIn(geometry.trialFontMin, geometry.trialFontMax)
    onTrialFontChange(trialFontPx.toFloat())

    handle.wrapper.revalidate()
}

/**
 * Apply the standard wizard-card panel configuration: Y-axis BoxLayout, empty padding
 * border, hand cursor, and a locked pixel size (JBUI.scale applied to both dimensions).
 */
internal fun configureCardPanel(
    panel: JComponent,
    paddingPx: Int,
    widthPx: Int,
    heightPx: Int,
    scaler: ContentScaler? = null,
) {
    panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.border = JBUI.Borders.empty(paddingPx)
    panel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    val size = Dimension(JBUI.scale(widthPx), JBUI.scale(heightPx))
    panel.preferredSize = size
    panel.minimumSize = size
    panel.maximumSize = size
    scaler?.registerCard(panel, widthPx, heightPx)
}

/**
 * Wrap [component] in a horizontally-centered BoxLayout row (glue + component + glue).
 * Used for single-element centered rows in both wizard panels (trial headline, etc.).
 */
internal fun centeredHorizontalRow(component: JComponent): JPanel {
    val row = JPanel()
    row.layout = BoxLayout(row, BoxLayout.X_AXIS)
    row.isOpaque = false
    row.alignmentX = Component.CENTER_ALIGNMENT
    row.add(Box.createHorizontalGlue())
    row.add(component)
    row.add(Box.createHorizontalGlue())
    return row
}

/**
 * Text content for a card's label stack. [footnote] is optional — null means the
 * card has only title + subtitle. [subtitleColor] overrides the default description
 * color when non-null (used for amber trial cues on rail cards).
 */
internal data class CardLabelContent(
    val title: String,
    val subtitle: String,
    val footnote: String? = null,
    val subtitleColor: Color? = null,
)

/**
 * Typography and spacing for a card's label stack. [subtitleFootnoteGapPx] is only
 * consulted when the corresponding content has a non-null footnote.
 */
internal data class CardLabelStyle(
    val titleSizePx: Int,
    val descSizePx: Int,
    val descColor: Color,
    val titleSubtitleGapPx: Int,
    val subtitleFootnoteGapPx: Int = 0,
)

/**
 * Attach a title + subtitle (+ optional footnote) label stack to a wizard [card]:
 * bold white title, normal subtitle (in `content.subtitleColor` or `style.descColor`),
 * italic footnote in description color. Trailing vertical glue lets the card's
 * fixed height push extras to the top.
 */
internal fun attachCardLabels(
    card: JPanel,
    content: CardLabelContent,
    style: CardLabelStyle,
    scaler: ContentScaler? = null,
) {
    val titleLabel = JBLabel(content.title)
    titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, JBUI.scale(style.titleSizePx).toFloat())
    titleLabel.foreground = Color.WHITE
    titleLabel.alignmentX = Component.LEFT_ALIGNMENT
    card.add(titleLabel)
    scaler?.registerLabel(titleLabel, style.titleSizePx, Font.BOLD)

    val gap1 = Box.createVerticalStrut(JBUI.scale(style.titleSubtitleGapPx))
    card.add(gap1)
    scaler?.registerGap(gap1, style.titleSubtitleGapPx)

    val subtitleLabel = JBLabel(content.subtitle)
    subtitleLabel.font = subtitleLabel.font.deriveFont(JBUI.scale(style.descSizePx).toFloat())
    subtitleLabel.foreground = content.subtitleColor ?: style.descColor
    subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
    card.add(subtitleLabel)
    scaler?.registerLabel(subtitleLabel, style.descSizePx, Font.PLAIN)

    content.footnote?.let { footnote ->
        val gap2 = Box.createVerticalStrut(JBUI.scale(style.subtitleFootnoteGapPx))
        card.add(gap2)
        scaler?.registerGap(gap2, style.subtitleFootnoteGapPx)

        val footnoteLabel = JBLabel(footnote)
        footnoteLabel.font =
            footnoteLabel.font.deriveFont(Font.ITALIC, JBUI.scale(style.descSizePx).toFloat())
        footnoteLabel.foreground = style.descColor
        footnoteLabel.alignmentX = Component.LEFT_ALIGNMENT
        card.add(footnoteLabel)
        scaler?.registerLabel(footnoteLabel, style.descSizePx, Font.ITALIC)
    }

    card.add(Box.createVerticalGlue())
}

/**
 * One vertical entry in a wizard section content column. [gapBeforePx] is the
 * scaled strut inserted immediately before [component]; zero means no strut.
 */
internal data class SectionEntry(
    val component: JComponent,
    val gapBeforePx: Int,
)

/**
 * Build the shared section content column for a wizard panel. Every entry is
 * stacked vertically with an optional pre-strut, in order. The returned panel
 * uses BoxLayout.Y_AXIS and is center-aligned, ready to be wrapped by
 * [installWizardContent].
 */
internal fun buildWizardSection(
    entries: List<SectionEntry>,
    scaler: ContentScaler? = null,
): JPanel {
    val content = JPanel()
    content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
    content.isOpaque = false
    for (entry in entries) {
        if (entry.gapBeforePx > 0) {
            val strut = Box.createVerticalStrut(JBUI.scale(entry.gapBeforePx))
            scaler?.registerGap(strut, entry.gapBeforePx)
            content.add(strut)
        }
        content.add(entry.component)
    }
    content.alignmentX = Component.CENTER_ALIGNMENT
    return content
}

/**
 * Build the standard bottom button row for both wizards: centered pair of
 * "Keep defaults" (secondary) and "Open Settings" (accent) buttons with a gap.
 */
internal fun buildWizardBottomButtons(
    gapPx: Int,
    onKeepDefaults: () -> Unit,
    onOpenSettings: () -> Unit,
    scaler: ContentScaler? = null,
): JPanel {
    val row = JPanel()
    row.layout = BoxLayout(row, BoxLayout.X_AXIS)
    row.isOpaque = false
    row.alignmentX = Component.CENTER_ALIGNMENT

    row.add(Box.createHorizontalGlue())
    row.add(createStyledButton("Keep defaults", isAccent = false, onClick = onKeepDefaults, scaler = scaler))
    val gap = Box.createHorizontalStrut(JBUI.scale(gapPx))
    scaler?.registerGap(gap, gapPx, horizontal = true)
    row.add(gap)
    row.add(createStyledButton("Open Settings", isAccent = true, onClick = onOpenSettings, scaler = scaler))
    row.add(Box.createHorizontalGlue())
    return row
}
