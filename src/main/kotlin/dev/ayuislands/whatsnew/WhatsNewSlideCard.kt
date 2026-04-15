package dev.ayuislands.whatsnew

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.ayuislands.onboarding.paintCardChrome
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Builds one slide card: title heading + body paragraph (HTML-capable JBLabel) +
 * optional screenshot. Wrapped in a card chrome panel that reuses
 * [paintCardChrome] for visual consistency with the onboarding wizard.
 *
 * Image is loaded best-effort: a missing or broken file renders the slide
 * without an image and logs a single WARN. Other slides remain unaffected.
 */
internal object WhatsNewSlideCard {
    private val LOG = logger<WhatsNewSlideCard>()

    private const val TITLE_FONT_SIZE = 18
    private const val BODY_FONT_SIZE = 13
    private const val PADDING = 24
    private const val GAP_TITLE_BODY = 12
    private const val GAP_BODY_IMAGE = 16
    private const val IMAGE_MAX_HEIGHT = 360
    private const val PLACEHOLDER_RADIUS = 8

    // Aspect math for the placeholder rectangle shown when a slide's image is
    // missing. 16:9 horizontally, one-third of IMAGE_MAX_HEIGHT vertically —
    // close enough to a "screenshot frame" shape that the slot reads as
    // intentional negative space rather than a broken layout.
    private const val PLACEHOLDER_ASPECT_W = 16
    private const val PLACEHOLDER_ASPECT_H = 9
    private const val PLACEHOLDER_HEIGHT_DIVISOR = 3

    // Channel values used to compose the translucent JBColor fill below.
    // Pulled out as named constants so detekt's MagicNumber check stays happy
    // without needing an @Suppress — and so a future theme tweak has a single
    // place to land. Alpha differs by mode: darker ink on light mode needs more
    // opacity to read against the card chrome; brighter ink on dark mode can
    // sit quieter.
    private const val RGB_MIN = 0
    private const val RGB_MAX = 255
    private const val PLACEHOLDER_LIGHT_ALPHA = 32
    private const val PLACEHOLDER_DARK_ALPHA = 16

    private val PLACEHOLDER_FILL =
        JBColor(
            Color(RGB_MIN, RGB_MIN, RGB_MIN, PLACEHOLDER_LIGHT_ALPHA),
            Color(RGB_MAX, RGB_MAX, RGB_MAX, PLACEHOLDER_DARK_ALPHA),
        )

    /**
     * @param resourceDir manifest resource dir prefix (e.g. `/whatsnew/v2.5.0/`)
     *   used to resolve [WhatsNewSlide.image] relative paths
     * @param accentTint accent color for card border / hover state
     */
    fun build(
        slide: WhatsNewSlide,
        resourceDir: String,
        accentTint: Color,
    ): JPanel {
        val card =
            object : JPanel(BorderLayout()) {
                override fun paintComponent(graphics: Graphics) {
                    val g2 = graphics as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    paintCardChrome(g2, width, height, hovered = false, tintColor = accentTint)
                }
            }
        card.isOpaque = false
        card.border = JBUI.Borders.empty(PADDING)

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        val titleLabel = JBLabel(slide.title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, JBUI.scale(TITLE_FONT_SIZE).toFloat())
        titleLabel.foreground = JBColor.foreground()
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        content.add(titleLabel)

        content.add(Box.createVerticalStrut(JBUI.scale(GAP_TITLE_BODY)))

        // Body uses HTML so manifest authors can include <b>, <i>, line breaks
        // without a markdown parser. JBLabel renders Swing's HTML subset natively.
        val bodyText = "<html><body style='width:600px'>${slide.body}</body></html>"
        val bodyLabel = JBLabel(bodyText)
        bodyLabel.font = bodyLabel.font.deriveFont(JBUI.scale(BODY_FONT_SIZE).toFloat())
        bodyLabel.foreground = JBColor.foreground()
        bodyLabel.alignmentX = Component.LEFT_ALIGNMENT
        content.add(bodyLabel)

        if (slide.image != null) {
            content.add(Box.createVerticalStrut(JBUI.scale(GAP_BODY_IMAGE)))
            val imageComponent = loadImageLabel(resourceDir + slide.image)
            imageComponent.alignmentX = Component.LEFT_ALIGNMENT
            content.add(imageComponent)
        }

        card.add(content, BorderLayout.CENTER)
        return card
    }

    private fun loadImageLabel(resourcePath: String): JComponent {
        val icon =
            try {
                IconLoader.findIcon(resourcePath, WhatsNewSlideCard::class.java)
            } catch (exception: RuntimeException) {
                LOG.warn("What's New: failed to load slide image $resourcePath", exception)
                null
            }
        if (icon == null) {
            LOG.warn("What's New: slide image not found at $resourcePath")
            return placeholderImage()
        }
        val label = JLabel(icon)
        label.horizontalAlignment = SwingConstants.LEFT
        return label
    }

    /**
     * Renders a soft-gray rectangle when an image is missing or fails to load.
     * Slide still shows title + body; user sees there *should* have been a
     * screenshot here. Single WARN already logged; rendering doesn't crash.
     */
    private fun placeholderImage(): JPanel {
        val placeholder =
            object : JPanel() {
                override fun paintComponent(graphics: Graphics) {
                    val g2 = graphics as Graphics2D
                    g2.color = PLACEHOLDER_FILL
                    val radius = JBUI.scale(PLACEHOLDER_RADIUS)
                    g2.fillRoundRect(0, 0, width, height, radius, radius)
                }
            }
        placeholder.isOpaque = false
        placeholder.border = BorderFactory.createEmptyBorder()
        val placeholderWidth = JBUI.scale(IMAGE_MAX_HEIGHT * PLACEHOLDER_ASPECT_W / PLACEHOLDER_ASPECT_H)
        val placeholderHeight = JBUI.scale(IMAGE_MAX_HEIGHT / PLACEHOLDER_HEIGHT_DIVISOR)
        val size = Dimension(placeholderWidth, placeholderHeight)
        placeholder.preferredSize = size
        placeholder.minimumSize = size
        return placeholder
    }
}
