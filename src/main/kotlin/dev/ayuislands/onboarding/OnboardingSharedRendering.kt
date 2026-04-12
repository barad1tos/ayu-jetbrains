package dev.ayuislands.onboarding

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.ImageLoader
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

private val LOG = logger<OnboardingSharedRenderingLog>()

// Shared hero background, scrim, and footer rail helpers extracted from
// FreeOnboardingPanel and PremiumOnboardingPanel to eliminate duplication.

internal class OnboardingSharedRenderingLog

/**
 * Load and scale an SVG hero image from plugin resources.
 *
 * @param resourceAnchor the class whose classloader resolves the resource path —
 *   pass the panel's own class so the resource lookup stays correct across both
 *   [FreeOnboardingPanel] and [PremiumOnboardingPanel]
 */
internal fun loadScaledHero(
    path: String,
    targetWidth: Int,
    targetHeight: Int,
    resourceAnchor: Class<*>,
): Image? =
    try {
        val raw = ImageLoader.loadFromResource(path, resourceAnchor)
        raw?.let { ImageLoader.scaleImage(it, targetWidth, targetHeight) }
    } catch (exception: IOException) {
        LOG.warn("Failed to load hero SVG $path", exception)
        null
    } catch (exception: IllegalArgumentException) {
        LOG.warn("Failed to load hero SVG $path", exception)
        null
    }

/**
 * Draw [image] centered on a [panelWidth]x[panelHeight] surface at the
 * pre-computed cover dimensions.
 */
internal fun drawCoveredImage(
    g2: Graphics2D,
    image: Image,
    panelWidth: Int,
    panelHeight: Int,
    coverSize: Pair<Int, Int>,
) {
    val (scaledW, scaledH) = coverSize
    val drawX = (panelWidth - scaledW) / 2
    val drawY = (panelHeight - scaledH) / 2
    g2.drawImage(image, drawX, drawY, scaledW, scaledH, null)
}

/**
 * Compute the pixel dimensions needed for a "cover"-scaled SVG given the panel
 * size and SVG viewBox. Returns (scaledWidth, scaledHeight).
 */
internal fun computeCoverDimensions(
    panelWidth: Int,
    panelHeight: Int,
    viewBoxWidth: Double,
    viewBoxHeight: Double,
): Pair<Int, Int> {
    val scale = maxOf(panelWidth / viewBoxWidth, panelHeight / viewBoxHeight)
    return Pair((viewBoxWidth * scale).toInt(), (viewBoxHeight * scale).toInt())
}

/** Scrim gradient configuration for the hero SVG overlay. */
internal data class ScrimConfig(
    val fraction: Double,
    val topColor: Color,
    val bottomColor: Color,
)

/**
 * Paint a bottom gradient scrim for text readability over the hero SVG.
 * Both wizard panels use identical scrim parameters.
 */
internal fun paintScrim(
    g2: Graphics2D,
    panelWidth: Int,
    panelHeight: Int,
    config: ScrimConfig,
) {
    val scrimHeight = (panelHeight * config.fraction).toInt()
    g2.paint =
        GradientPaint(
            0f,
            (panelHeight - scrimHeight).toFloat(),
            config.topColor,
            0f,
            panelHeight.toFloat(),
            config.bottomColor,
        )
    g2.fillRect(0, panelHeight - scrimHeight, panelWidth, scrimHeight)
}

/**
 * Create the thin vertical divider used between rail card groups in both wizards.
 */
internal fun createRailDivider(
    dividerHeightPx: Int,
    cardHeightPx: Int,
    borderColor: Color = OnboardingCardChrome.CARD_BORDER_COLOR,
): JComponent {
    val divider =
        object : JPanel() {
            override fun paintComponent(graphics: Graphics) {
                val g2 = graphics as Graphics2D
                g2.color = borderColor
                val margin = (height - JBUI.scale(dividerHeightPx)) / 2
                g2.fillRect(0, margin, 1, JBUI.scale(dividerHeightPx))
            }
        }
    divider.isOpaque = false
    val size = Dimension(1, JBUI.scale(cardHeightPx))
    divider.preferredSize = size
    divider.minimumSize = size
    divider.maximumSize = size
    return divider
}

/**
 * Text and behavior spec for a footer rail card. Both wizard panels use the same
 * visual structure -- the [onClick] callback captures panel-specific context.
 */
internal data class RailCardSpec(
    val title: String,
    val subtitle: String,
    val tooltip: String,
    val hoverColor: Color,
    val cornerIcon: Icon?,
    val subtitleColor: Color? = null,
    val onClick: () -> Unit,
)

/** Pixel dimensions and styling parameters for a rail card. */
internal data class RailCardLayout(
    val paddingPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val dotMarginPx: Int,
    val labelStyle: CardLabelStyle,
)

/**
 * Create a footer rail card from a [RailCardSpec]. Used by both wizard panels
 * for feature teaser and community link cards in the bottom rail.
 */
@Suppress("LongMethod")
internal fun createRailCard(
    spec: RailCardSpec,
    layout: RailCardLayout,
    scaler: ContentScaler,
): JPanel {
    val cardPanel =
        object : JPanel() {
            private var hovered = false

            init {
                configureCardPanel(this, layout.paddingPx, layout.widthPx, layout.heightPx, scaler)
                toolTipText = spec.tooltip

                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseEntered(event: MouseEvent) {
                            hovered = true
                            repaint()
                        }

                        override fun mouseExited(event: MouseEvent) {
                            hovered = false
                            repaint()
                        }

                        override fun mouseClicked(event: MouseEvent) {
                            spec.onClick()
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
                paintCardChrome(
                    g2,
                    width,
                    height,
                    hovered,
                    spec.hoverColor,
                    contentScale = scaler.currentScale,
                )

                spec.cornerIcon?.let { icon ->
                    val iconMargin = JBUI.scale(layout.dotMarginPx)
                    icon.paintIcon(this, g2, width - iconMargin - icon.iconWidth, iconMargin)
                }

                super.paintComponent(graphics)
            }
        }
    cardPanel.isOpaque = false

    attachCardLabels(
        card = cardPanel,
        content =
            CardLabelContent(
                title = spec.title,
                subtitle = spec.subtitle,
                subtitleColor = spec.subtitleColor,
            ),
        style = layout.labelStyle,
        scaler = scaler,
    )

    return cardPanel
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
    row.alignmentX = java.awt.Component.CENTER_ALIGNMENT

    row.add(Box.createHorizontalGlue())
    row.add(createStyledButton("Keep defaults", isAccent = false, onClick = onKeepDefaults, scaler = scaler))
    val gap = Box.createHorizontalStrut(JBUI.scale(gapPx))
    scaler?.registerGap(gap, gapPx, horizontal = true)
    row.add(gap)
    row.add(createStyledButton("Open Settings", isAccent = true, onClick = onOpenSettings, scaler = scaler))
    row.add(Box.createHorizontalGlue())
    return row
}
