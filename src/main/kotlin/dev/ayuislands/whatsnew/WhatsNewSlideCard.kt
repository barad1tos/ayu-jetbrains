package dev.ayuislands.whatsnew

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ImageLoader
import com.intellij.util.ui.JBUI
import dev.ayuislands.onboarding.ContentScaler
import dev.ayuislands.onboarding.paintCardChrome
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.IOException
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

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
    private const val BODY_WRAP_WIDTH = 600

    // Default widthFactor for slides that don't specify imageScale in the
    // manifest. 1.0 renders at WhatsNewImagePanel.DEFAULT_IMAGE_WIDTH (800 px
    // logical) before ContentScaler applies its own tab-width multiplier.
    private const val DEFAULT_IMAGE_FACTOR = 1.0f

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
     * @param scaler when non-null, text labels and vertical gaps are registered
     *   so the outer [WhatsNewPanel] resizes them proportionally on IDE-window
     *   resize. The image panel intentionally owns its own sizing via
     *   [WhatsNewImagePanel.getPreferredSize] and is NOT registered (would
     *   double-scale). Passing null keeps everything at its natural baseline.
     */
    fun build(
        slide: WhatsNewSlide,
        resourceDir: String,
        accentTint: Color,
        scaler: ContentScaler? = null,
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
        scaler?.registerLabel(titleLabel, TITLE_FONT_SIZE, Font.BOLD)

        val titleGap = Box.createVerticalStrut(JBUI.scale(GAP_TITLE_BODY))
        content.add(titleGap)
        scaler?.registerGap(titleGap, GAP_TITLE_BODY)

        // Body uses HTML so manifest authors can include <b>, <i>, line breaks
        // without a markdown parser. JBLabel renders Swing's HTML subset natively.
        // Width is JBUI-scaled so the wrap point matches HiDPI device pixels.
        val bodyWrapPx = JBUI.scale(BODY_WRAP_WIDTH)
        val bodyText = "<html><body style='width:${bodyWrapPx}px'>${slide.body}</body></html>"
        val bodyLabel = JBLabel(bodyText)
        bodyLabel.font = bodyLabel.font.deriveFont(JBUI.scale(BODY_FONT_SIZE).toFloat())
        bodyLabel.foreground = JBColor.foreground()
        bodyLabel.alignmentX = Component.LEFT_ALIGNMENT
        content.add(bodyLabel)
        scaler?.registerLabel(bodyLabel, BODY_FONT_SIZE, Font.PLAIN)

        if (slide.image != null) {
            val imageGap = Box.createVerticalStrut(JBUI.scale(GAP_BODY_IMAGE))
            content.add(imageGap)
            scaler?.registerGap(imageGap, GAP_BODY_IMAGE)

            val effectiveScale = slide.imageScale ?: DEFAULT_IMAGE_FACTOR
            val imageComponent = loadImageComponent(resourceDir + slide.image, effectiveScale)

            // Image panel owns its sizing via dynamic getPreferredSize() that
            // reads the parent column width — no ContentScaler registration
            // needed (would double-scale). Centering done via BoxLayout.X_AXIS
            // glue wrapper to match editorial-tab conventions.
            val imageRow = JPanel()
            imageRow.layout = BoxLayout(imageRow, BoxLayout.X_AXIS)
            imageRow.isOpaque = false
            imageRow.alignmentX = Component.LEFT_ALIGNMENT
            imageRow.add(Box.createHorizontalGlue())
            imageRow.add(imageComponent)
            imageRow.add(Box.createHorizontalGlue())
            content.add(imageRow)
        }

        card.add(content, BorderLayout.CENTER)
        return card
    }

    private fun loadImageComponent(
        resourcePath: String,
        widthFactor: Float,
    ): JComponent {
        // Catch broadly: ImageLoader can throw IOException for missing/broken
        // resources, but also IllegalArgumentException for malformed paths and
        // ClassCastException when the resource bytes don't decode as an Image.
        // Any of these should degrade to the placeholder; an Error (OOM,
        // VirtualMachineError) intentionally propagates because the IDE itself
        // is in trouble at that point.
        val image =
            try {
                ImageLoader.loadFromResource(resourcePath, WhatsNewSlideCard::class.java)
            } catch (exception: IOException) {
                LOG.warn("What's New: failed to load slide image $resourcePath", exception)
                null
            } catch (exception: RuntimeException) {
                LOG.warn("What's New: failed to decode slide image $resourcePath", exception)
                null
            }
        if (image == null) {
            LOG.warn("What's New: slide image not found at $resourcePath")
            return placeholderImage()
        }
        return WhatsNewImagePanel(image, widthFactor)
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
