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
 * Builds one slide card: colorized title heading, wrapped body paragraph,
 * and optional screenshot, wrapped in a card chrome panel that reuses
 * [paintCardChrome] for visual consistency with the onboarding wizard.
 *
 * Body text honors HTML markup (`<b>`, `<i>`) embedded in the manifest and
 * word-wraps within [BODY_WRAP_WIDTH_PX] so paragraphs flow naturally on
 * card resize.
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
    private const val GAP_TITLE_IMAGE = 16

    // Body word-wrap budget in HTML CSS px (logical, NOT device px). The HTML
    // renderer inside JLabel interprets `width:NNNpx` as logical pixels and
    // applies its own HiDPI scaling on top, so this value must NOT be passed
    // through `JBUI.scale()` — that would double-scale the wrap width and the
    // text would collapse to a single column on Retina. Derived from
    // [WhatsNewImagePanel.DEFAULT_IMAGE_WIDTH] minus an inset so prose visually
    // aligns inside the screenshot column rather than overhanging it.
    private const val BODY_WRAP_INSET_PX = 80
    private const val BODY_WRAP_WIDTH_PX = WhatsNewImagePanel.DEFAULT_IMAGE_WIDTH - BODY_WRAP_INSET_PX
    private const val IMAGE_MAX_HEIGHT = 360
    private const val PLACEHOLDER_RADIUS = 8

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
     * @param slide manifest entry; `title`, `body` (HTML-aware, word-wrapped),
     *   and `image` are all rendered. Empty body skips the prose row.
     * @param resourceDir manifest resource dir prefix (e.g. `/whatsnew/v2.5.0/`)
     *   used to resolve [WhatsNewSlide.image] relative paths
     * @param accentTint accent color for card border / hover state
     * @param titleColor color applied to the slide title; caller passes a
     *   per-slide palette entry (lavender/gold/cyan) so the scroll column
     *   reads as a color sequence rather than a uniform block.
     * @param scaler when non-null, the title label and its surrounding gap
     *   are registered so the outer [WhatsNewPanel] resizes them
     *   proportionally on IDE-window resize. The image panel intentionally
     *   owns its own sizing via [WhatsNewImagePanel.getPreferredSize] and is
     *   NOT registered (would double-scale). Passing null keeps everything
     *   at its natural baseline.
     */
    fun build(
        slide: WhatsNewSlide,
        resourceDir: String,
        accentTint: Color,
        titleColor: Color,
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
        titleLabel.foreground = titleColor
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        content.add(titleLabel)
        scaler?.registerLabel(titleLabel, TITLE_FONT_SIZE, Font.BOLD)

        // Body paragraph — wrapped in <html><body style="width:NNN"> so swing's
        // JLabel-as-renderer engages its built-in word-wrapper; raw text would
        // render single-line and clip on the card edge. The width budget is
        // hand-tuned just under [WhatsNewImagePanel.DEFAULT_IMAGE_WIDTH] (800)
        // so prose visually aligns with the screenshot below it.
        if (slide.body.isNotBlank()) {
            val bodyGap = Box.createVerticalStrut(JBUI.scale(GAP_TITLE_BODY))
            content.add(bodyGap)
            scaler?.registerGap(bodyGap, GAP_TITLE_BODY)

            val bodyLabel =
                JBLabel("<html><body style='width:${BODY_WRAP_WIDTH_PX}px'>${slide.body}</body></html>")
            // Force PLAIN style explicitly — JBLabel's default font on Linux
            // can carry the BOLD bit from the system theme, which would
            // visually compete with the bolded title above. macOS defaults
            // to PLAIN, but cross-platform parity matters for tests + UX.
            bodyLabel.font = bodyLabel.font.deriveFont(Font.PLAIN, JBUI.scale(BODY_FONT_SIZE).toFloat())
            bodyLabel.alignmentX = Component.LEFT_ALIGNMENT
            content.add(bodyLabel)
            scaler?.registerLabel(bodyLabel, BODY_FONT_SIZE, Font.PLAIN)
        }

        if (slide.image != null) {
            val gapPx = if (slide.body.isNotBlank()) GAP_BODY_IMAGE else GAP_TITLE_IMAGE
            val imageGap = Box.createVerticalStrut(JBUI.scale(gapPx))
            content.add(imageGap)
            scaler?.registerGap(imageGap, gapPx)

            val effectiveScale = slide.imageScale ?: DEFAULT_IMAGE_FACTOR
            val imageComponent = loadImageComponent(resourceDir + slide.image, effectiveScale)

            // Image panel owns its sizing via dynamic getPreferredSize() that
            // reads the parent column width — no ContentScaler registration
            // needed (would double-scale). LEFT_ALIGNMENT keeps the wrapper
            // flush with the title above; horizontal glue inside still
            // centers the image within the wrapper's actual rendered width.
            content.add(centerInRow(imageComponent, Component.LEFT_ALIGNMENT))
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
        // is in trouble at that point. Each branch returns the placeholder
        // directly so a single failure produces exactly one WARN line.
        val image =
            try {
                ImageLoader.loadFromResource(resourcePath, WhatsNewSlideCard::class.java)
            } catch (exception: IOException) {
                LOG.warn("What's New: failed to load slide image $resourcePath", exception)
                return placeholderImage()
            } catch (exception: RuntimeException) {
                LOG.warn("What's New: failed to decode slide image $resourcePath", exception)
                return placeholderImage()
            }
        if (image == null) {
            LOG.warn("What's New: slide image not found at $resourcePath")
            return placeholderImage()
        }
        // Defensive — a broken AsyncImage or a partially-loaded Toolkit image
        // can report negative dimensions. Render a placeholder rather than
        // a 1×1 pixel speck where the screenshot should have been.
        val w = image.getWidth(null)
        val h = image.getHeight(null)
        if (w <= 0 || h <= 0) {
            LOG.warn("What's New: slide image $resourcePath has invalid dimensions ${w}x$h")
            return placeholderImage()
        }
        return WhatsNewImagePanel(image, widthFactor)
    }

    /**
     * Renders a soft-gray rectangle when an image is missing or fails to load.
     * Slide still shows its colorized title; user sees there *should* have
     * been a screenshot here. Single WARN already logged; rendering doesn't
     * crash.
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
