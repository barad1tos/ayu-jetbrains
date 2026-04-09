@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.onboarding

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.ScaleContext
import com.intellij.util.SVGLoader
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentColor
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.onboarding.OnboardingCardChrome.CARD_BORDER_COLOR
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

/**
 * Free onboarding wizard panel mirroring the premium wizard's visual language:
 * full-tab SVG hero background with frosted glass cards anchored at the bottom.
 *
 * Content slots differ from premium — three theme variants, an accent swatch
 * strip, and a footer rail with premium teasers + community links — but the
 * card grammar, button row, and glow border hover effect are shared.
 */
@Suppress("TooManyFunctions")
internal class FreeOnboardingPanel(
    private val project: Project,
    private val virtualFile: VirtualFile,
) : JPanel(BorderLayout()) {
    /** Variant committed via click. Falls back to the variant active when the wizard opened. */
    private var committedVariant: AyuVariant? = AyuVariant.detect()

    /** Accent chosen via swatch click, or null = use variant default accent for card dots. */
    private var selectedAccentHex: String? = null

    /** Variant cards kept in sync with [selectedAccentHex] and committed variant. */
    private val variantCards = mutableListOf<JComponent>()

    /** Hero SVG resource path per variant — loaded on demand via SVGLoader. */
    private val variantHeroPaths: Map<AyuVariant, String> =
        AyuVariant.entries
            .mapNotNull { variant -> variantHeroPath(variant)?.let { variant to it } }
            .toMap()

    /** Variant whose hero is currently displayed. Tracks hover/commit independently of LAF. */
    private var currentHeroVariant: AyuVariant = committedVariant ?: AyuVariant.MIRAGE

    private var cachedBackgroundImage: java.awt.Image? = null
    private var cachedBackgroundKey: Triple<AyuVariant, Int, Int>? = null

    private var topStrut: Component? = null
    private var contentWrapper: JPanel? = null
    private var trialHeadlineLabel: JBLabel? = null

    init {
        isOpaque = false
        addAncestorListener(
            object : AncestorListener {
                override fun ancestorAdded(event: AncestorEvent) {
                    SwingUtilities.invokeLater {
                        if (project.isDisposed) return@invokeLater
                        loadContent()
                        revalidate()
                        repaint()
                    }
                }

                override fun ancestorRemoved(event: AncestorEvent) = Unit

                override fun ancestorMoved(event: AncestorEvent) = Unit
            },
        )
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        paintBackground(g2)
        paintScrim(g2)

        super.paintComponent(graphics)
    }

    /** Resolve the hero SVG classpath for a variant, or null if the resource is missing. */
    private fun variantHeroPath(variant: AyuVariant): String? {
        val path =
            when (variant) {
                AyuVariant.MIRAGE -> "/onboarding/welcome_board_mirage.svg"
                AyuVariant.DARK -> "/onboarding/welcome_board_dark.svg"
                AyuVariant.LIGHT -> "/onboarding/welcome_board_light.svg"
            }
        if (FreeOnboardingPanel::class.java.getResource(path) == null) {
            LOG.info("Variant hero $path not present — variant will use fallback")
            return null
        }
        return path
    }

    /**
     * Render SVG as a full-tab background with "cover" scaling using [SVGLoader.load].
     *
     * We bypass [com.intellij.openapi.util.IconLoader] + `IconUtil.scale` here because
     * their scaling interacts with HiDPI in ways that under-size the rendered bitmap on
     * non-trivial aspect ratios, leaving visible gaps around the hero. `SVGLoader.load`
     * returns an [java.awt.Image] whose pixel dimensions match the requested scale
     * deterministically, and we draw with explicit destination dimensions so async image
     * observers cannot short-circuit the paint.
     */
    private fun paintBackground(g2: Graphics2D) {
        val path = variantHeroPaths[currentHeroVariant] ?: variantHeroPaths.values.firstOrNull() ?: return
        if (width <= 0 || height <= 0) return

        val scale = maxOf(width / SVG_VIEW_BOX_WIDTH, height / SVG_VIEW_BOX_HEIGHT)
        val scaledW = (SVG_VIEW_BOX_WIDTH * scale).toInt()
        val scaledH = (SVG_VIEW_BOX_HEIGHT * scale).toInt()

        val key = Triple(currentHeroVariant, scaledW, scaledH)
        if (cachedBackgroundKey != key) {
            cachedBackgroundImage = loadScaledHero(path, scaledW, scaledH)
            cachedBackgroundKey = key
        }

        val image = cachedBackgroundImage ?: return
        val drawX = (width - scaledW) / 2
        val drawY = (height - scaledH) / 2
        g2.drawImage(image, drawX, drawY, scaledW, scaledH, null)
    }

    @Suppress("UnstableApiUsage")
    private fun loadScaledHero(
        path: String,
        targetW: Int,
        targetH: Int,
    ): java.awt.Image? {
        val url = FreeOnboardingPanel::class.java.getResource(path) ?: return null
        val stream = FreeOnboardingPanel::class.java.getResourceAsStream(path) ?: return null
        return try {
            stream.use {
                SVGLoader.load(
                    url,
                    it,
                    ScaleContext.create(this),
                    targetW.toDouble(),
                    targetH.toDouble(),
                )
            }
        } catch (exception: java.io.IOException) {
            LOG.info("Failed to load hero SVG $path", exception)
            null
        }
    }

    /** Bottom gradient scrim for text readability over the image. */
    private fun paintScrim(g2: Graphics2D) {
        val scrimHeight = (height * SCRIM_FRACTION).toInt()
        g2.paint =
            GradientPaint(
                0f,
                (height - scrimHeight).toFloat(),
                SCRIM_TOP_COLOR,
                0f,
                height.toFloat(),
                SCRIM_BOTTOM_COLOR,
            )
        g2.fillRect(0, height - scrimHeight, width, scrimHeight)
    }

    private fun loadContent() {
        // Section A: variant picker + accent swatches (tight cluster)
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        content.add(buildVariantCardsRow())
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_SMALL)))
        content.add(buildAccentStrip())

        // Section gap A -> B. Compensates for font-metric leading at the top of the
        // trial label so the visible gap above matches the visible gap below.
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_SECTION_ABOVE_TRIAL)))

        // Section B: trial headline (standalone)
        content.add(buildTrialMessage())

        // Section gap B -> C
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_SECTION)))

        // Section C: feature rail + action buttons
        content.add(buildFooterRail())
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_MEDIUM)))
        content.add(buildBottomButtons())

        content.alignmentX = CENTER_ALIGNMENT

        val centeredRow = JPanel()
        centeredRow.layout = BoxLayout(centeredRow, BoxLayout.X_AXIS)
        centeredRow.isOpaque = false
        centeredRow.add(Box.createHorizontalGlue())
        centeredRow.add(content)
        centeredRow.add(Box.createHorizontalGlue())
        centeredRow.alignmentX = CENTER_ALIGNMENT

        // Wrapper: dynamic top strut (recomputed on resize, pins content to just below
        // the SVG tagline), content sticks to top, single glue absorbs any excess slack,
        // fixed BOTTOM_MARGIN gives an exact gap to the tab bottom edge.
        val wrapper = JPanel()
        wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
        wrapper.isOpaque = false
        val strut = Box.createVerticalStrut(0)
        topStrut = strut
        contentWrapper = wrapper
        wrapper.add(strut)
        wrapper.add(centeredRow)
        wrapper.add(Box.createVerticalGlue())
        wrapper.add(Box.createVerticalStrut(JBUI.scale(BOTTOM_MARGIN)))
        add(wrapper, BorderLayout.CENTER)

        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    updateDynamicLayout()
                }
            },
        )
        updateDynamicLayout()
    }

    /**
     * Recompute dynamic layout values that depend on the current tab size:
     * - Top strut height so content sits just below the SVG tagline.
     * - Trial headline font size so it visually matches the SVG tagline on any tab size.
     *
     * The SVG uses "cover" scaling (see [paintBackground]): scale = `max(w/680, h/590)`.
     * The tagline "Unified aesthetic engine..." lives at [SVG_TAGLINE_BOTTOM_Y] in
     * viewBox coordinates and is rendered at 13 SVG units, so screen size and screen-y
     * of the tagline are both derivable from the cover scale factor.
     */
    private fun updateDynamicLayout() {
        val strut = topStrut ?: return
        val wrapper = contentWrapper ?: return
        val w = width.toDouble()
        val h = height.toDouble()
        if (w <= 0 || h <= 0) return

        val scale = maxOf(w / SVG_VIEW_BOX_WIDTH, h / SVG_VIEW_BOX_HEIGHT)
        val svgHeightOnScreen = SVG_VIEW_BOX_HEIGHT * scale
        val svgTopY = (h - svgHeightOnScreen) / 2
        val taglineBottomY = svgTopY + SVG_TAGLINE_BOTTOM_Y * scale
        val topPadding = (taglineBottomY + JBUI.scale(GAP_SMALL)).toInt().coerceAtLeast(0)

        val size = Dimension(0, topPadding)
        strut.preferredSize = size
        strut.maximumSize = Dimension(Int.MAX_VALUE, topPadding)
        strut.minimumSize = size

        // Match trial headline font size to the SVG tagline's render at the current scale.
        val trialFontPx =
            (SVG_TAGLINE_FONT_PX * scale).toInt().coerceIn(TRIAL_FONT_MIN, TRIAL_FONT_MAX)
        updateTrialHeadline(trialFontPx.toFloat())

        wrapper.revalidate()
    }

    /** Rebuild the trial headline HTML with the current accent color and font size. */
    private fun updateTrialHeadline(fontPx: Float) {
        val label = trialHeadlineLabel ?: return
        // Clear size constraints BEFORE resizing so JLabel recomputes preferredSize.
        label.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        label.minimumSize = Dimension(0, 0)
        label.font = label.font.deriveFont(Font.PLAIN, fontPx)
        val accentHex = resolveCurrentAccentHex()
        label.text = buildTrialHeadlineHtml(accentHex)
        clampLabelToPreferred(label)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildTrialHeadlineHtml(accentHex: String): String =
        "<html><body style='margin:0;padding:0;font-family:sans-serif;color:$TRIAL_TEXT_BASE'>" +
            "All features <i><span style='color:$TRIAL_UNLOCKED_HEX'>unlocked</span></i> for 30 days" +
            "</body></html>"

    /** Current accent color as a CSS hex string — uses selected swatch if set, else variant default. */
    private fun resolveCurrentAccentHex(): String {
        selectedAccentHex?.let { return it }
        val variant = committedVariant ?: AyuVariant.MIRAGE
        return variant.defaultAccent
    }

    // -- Row A: Variant Cards --

    private fun buildVariantCardsRow(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT

        row.add(Box.createHorizontalGlue())
        for ((index, spec) in VARIANT_CARDS.withIndex()) {
            row.add(createVariantCard(spec))
            if (index < VARIANT_CARDS.lastIndex) {
                row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
            }
        }
        row.add(Box.createHorizontalGlue())
        return row
    }

    @Suppress("LongMethod")
    private fun createVariantCard(spec: VariantCardSpec): JPanel {
        val outerPanel = this

        val cardPanel =
            object : JPanel() {
                private var hovered = false

                init {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(CARD_PADDING)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    val size = Dimension(JBUI.scale(CARD_WIDTH), JBUI.scale(CARD_HEIGHT))
                    preferredSize = size
                    minimumSize = size
                    maximumSize = size

                    addMouseListener(
                        object : MouseAdapter() {
                            override fun mouseEntered(event: MouseEvent) {
                                hovered = true
                                outerPanel.previewVariant(spec.variant)
                                repaint()
                            }

                            override fun mouseExited(event: MouseEvent) {
                                hovered = false
                                outerPanel.revertVariantPreview()
                                repaint()
                            }

                            override fun mouseClicked(event: MouseEvent) {
                                outerPanel.commitVariant(spec.variant)
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
                        spec.tintColor,
                        baseFill = spec.baseFill,
                    )

                    // Color dot indicator — follows selected accent or variant default
                    val dotColor = outerPanel.resolveDotColor(spec.variant)
                    g2.color = dotColor
                    val dotSize = JBUI.scale(CARD_DOT_SIZE)
                    val dotMargin = JBUI.scale(CARD_DOT_MARGIN)
                    g2.fillOval(width - dotMargin - dotSize, dotMargin, dotSize, dotSize)

                    super.paintComponent(graphics)
                }
            }
        cardPanel.isOpaque = false

        val titleLabel = JBLabel(spec.title)
        titleLabel.font =
            titleLabel.font.deriveFont(Font.BOLD, JBUI.scale(CARD_NAME_SIZE).toFloat())
        titleLabel.foreground = Color.WHITE
        titleLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(titleLabel)

        cardPanel.add(Box.createVerticalStrut(JBUI.scale(GAP_TINY)))

        val subtitleLabel = JBLabel(spec.subtitle)
        subtitleLabel.font = subtitleLabel.font.deriveFont(JBUI.scale(CARD_DESC_SIZE).toFloat())
        subtitleLabel.foreground = CARD_DESC_COLOR
        subtitleLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(subtitleLabel)

        cardPanel.add(Box.createVerticalStrut(JBUI.scale(GAP_MICRO)))

        val footnoteLabel = JBLabel(spec.footnote)
        footnoteLabel.font =
            footnoteLabel.font.deriveFont(Font.ITALIC, JBUI.scale(CARD_DESC_SIZE).toFloat())
        footnoteLabel.foreground = CARD_DESC_COLOR
        footnoteLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(footnoteLabel)

        cardPanel.add(Box.createVerticalGlue())

        variantCards.add(cardPanel)
        return cardPanel
    }

    /** Hero-only preview — swaps the wizard background SVG but never touches IDE theme state. */
    private fun previewVariant(variant: AyuVariant) {
        currentHeroVariant = variant
        refreshTrialHeadlineForVariant()
        repaint()
    }

    /** Revert the hero preview to the committed variant. Does not touch IDE theme state. */
    private fun revertVariantPreview() {
        currentHeroVariant = committedVariant ?: AyuVariant.MIRAGE
        refreshTrialHeadlineForVariant()
        repaint()
    }

    /** Click path — actually applies the LAF and marks it as committed. */
    private fun commitVariant(variant: AyuVariant) {
        committedVariant = variant
        currentHeroVariant = variant
        refreshTrialHeadlineForVariant()
        repaint()
        applyVariantLaf(variant)
    }

    /** Rebuild the trial headline HTML so it picks up the per-variant text color. */
    private fun refreshTrialHeadlineForVariant() {
        val label = trialHeadlineLabel ?: return
        updateTrialHeadline(label.font.size2D)
    }

    @Suppress("UnstableApiUsage")
    private fun applyVariantLaf(variant: AyuVariant) {
        val lafManager = LafManager.getInstance()
        val targetName =
            variant.themeNames.firstOrNull { it.contains("Islands UI") }
                ?: variant.themeNames.first()
        val target = lafManager.installedThemes.firstOrNull { it.name == targetName } ?: return
        SwingUtilities.invokeLater {
            lafManager.setCurrentLookAndFeel(target, true)
            lafManager.updateUI()
        }
    }

    /** Color shown as the variant card's accent dot. Selected swatch takes priority. */
    private fun resolveDotColor(variant: AyuVariant): Color {
        val hex = selectedAccentHex ?: variant.defaultAccent
        return try {
            Color.decode(hex)
        } catch (_: NumberFormatException) {
            Color.decode(variant.defaultAccent)
        }
    }

    // -- Row B: Accent Strip --

    private fun buildAccentStrip(): JPanel {
        val strip = JPanel()
        strip.layout = BoxLayout(strip, BoxLayout.X_AXIS)
        strip.isOpaque = false
        strip.alignmentX = CENTER_ALIGNMENT

        for ((index, preset) in AYU_ACCENT_PRESETS.withIndex()) {
            strip.add(AccentSwatch(preset))
            if (index < AYU_ACCENT_PRESETS.lastIndex) {
                strip.add(Box.createHorizontalStrut(JBUI.scale(SWATCH_GAP)))
            }
        }

        return strip
    }

    private inner class AccentSwatch(
        private val preset: AccentColor,
    ) : JPanel() {
        private var hovered = false
        private val swatchColor: Color = Color.decode(preset.hex)

        init {
            isOpaque = false
            val diameter = JBUI.scale(SWATCH_DIAMETER) + JBUI.scale(SWATCH_HOVER_LIFT) * 2
            val size = Dimension(diameter, diameter)
            preferredSize = size
            minimumSize = size
            maximumSize = size
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = preset.name

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
                        applyAccentPreset(preset)
                    }
                },
            )
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val diameter = JBUI.scale(SWATCH_DIAMETER)
            val lift = if (hovered) JBUI.scale(SWATCH_HOVER_LIFT) else 0
            val cx = width / 2
            val cy = height / 2 - lift

            // Outer hover ring
            if (hovered) {
                val ringAlpha = SWATCH_RING_ALPHA
                val ringExpand = JBUI.scale(SWATCH_RING_EXPAND)
                g2.color =
                    Color(swatchColor.red, swatchColor.green, swatchColor.blue, ringAlpha)
                g2.fillOval(
                    cx - diameter / 2 - ringExpand,
                    cy - diameter / 2 - ringExpand,
                    diameter + ringExpand * 2,
                    diameter + ringExpand * 2,
                )
            }

            // Swatch fill
            g2.color = swatchColor
            g2.fillOval(cx - diameter / 2, cy - diameter / 2, diameter, diameter)

            // Border
            g2.color = CARD_BORDER_COLOR
            g2.drawOval(cx - diameter / 2, cy - diameter / 2, diameter - 1, diameter - 1)

            super.paintComponent(graphics)
        }
    }

    private fun applyAccentPreset(preset: AccentColor) {
        val settings = AyuIslandsSettings.getInstance()
        val variant = AyuVariant.detect() ?: return
        when (variant) {
            AyuVariant.MIRAGE -> settings.state.mirageAccent = preset.hex
            AyuVariant.DARK -> settings.state.darkAccent = preset.hex
            AyuVariant.LIGHT -> settings.state.lightAccent = preset.hex
        }
        selectedAccentHex = preset.hex
        variantCards.forEach { it.repaint() }
        val currentLabel = trialHeadlineLabel
        if (currentLabel != null) {
            val fontPx = currentLabel.font.size2D
            updateTrialHeadline(fontPx)
        }
        ApplicationManager.getApplication().invokeLater {
            AccentApplicator.apply(preset.hex)
        }
    }

    // -- Row C: Footer Rail --

    private fun buildFooterRail(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT

        row.add(Box.createHorizontalGlue())
        row.add(createRailCard(RAIL_GLOW_TEASER))
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(createRailCard(RAIL_FONT_TEASER))
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(createRailCard(RAIL_ROTATE_TEASER))
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(createRailCard(RAIL_PLUGINS_TEASER))
        row.add(Box.createHorizontalStrut(JBUI.scale(RAIL_DIVIDER_GAP)))
        row.add(createRailDivider())
        row.add(Box.createHorizontalStrut(JBUI.scale(RAIL_DIVIDER_GAP)))
        row.add(createRailCard(RAIL_SHARE_LINK))
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(createRailCard(RAIL_FEATURE_LINK))
        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun createRailDivider(): JComponent {
        val divider =
            object : JPanel() {
                override fun paintComponent(graphics: Graphics) {
                    val g2 = graphics as Graphics2D
                    g2.color = CARD_BORDER_COLOR
                    val margin = (height - JBUI.scale(RAIL_DIVIDER_HEIGHT)) / 2
                    g2.fillRect(0, margin, 1, JBUI.scale(RAIL_DIVIDER_HEIGHT))
                }
            }
        divider.isOpaque = false
        val size = Dimension(1, JBUI.scale(RAIL_CARD_HEIGHT))
        divider.preferredSize = size
        divider.minimumSize = size
        divider.maximumSize = size
        return divider
    }

    @Suppress("LongMethod")
    private fun createRailCard(spec: RailCardSpec): JPanel {
        val cardPanel =
            object : JPanel() {
                private var hovered = false

                init {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(RAIL_CARD_PADDING)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    val size = Dimension(JBUI.scale(RAIL_CARD_WIDTH), JBUI.scale(RAIL_CARD_HEIGHT))
                    preferredSize = size
                    minimumSize = size
                    maximumSize = size
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
                                spec.onClick(this@FreeOnboardingPanel)
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
                    )

                    // Corner icon (optional — null for trial-cue cards)
                    spec.cornerIcon?.let { icon ->
                        val iconMargin = JBUI.scale(CARD_DOT_MARGIN)
                        icon.paintIcon(this, g2, width - iconMargin - icon.iconWidth, iconMargin)
                    }

                    super.paintComponent(graphics)
                }
            }
        cardPanel.isOpaque = false

        val titleLabel = JBLabel(spec.title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, JBUI.scale(RAIL_TITLE_SIZE).toFloat())
        titleLabel.foreground = Color.WHITE
        titleLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(titleLabel)

        cardPanel.add(Box.createVerticalStrut(JBUI.scale(GAP_MICRO)))

        val subtitleLabel = JBLabel(spec.subtitle)
        subtitleLabel.font =
            subtitleLabel.font.deriveFont(JBUI.scale(CARD_DESC_SIZE).toFloat())
        subtitleLabel.foreground = spec.subtitleColor
        subtitleLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(subtitleLabel)

        cardPanel.add(Box.createVerticalGlue())

        return cardPanel
    }

    // -- Trial messaging --

    private fun buildTrialMessage(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT

        val headline = JBLabel(buildTrialHeadlineHtml(resolveCurrentAccentHex()))
        headline.font = headline.font.deriveFont(Font.PLAIN, JBUI.scale(TRIAL_HEADLINE_SIZE).toFloat())
        clampLabelToPreferred(headline)
        trialHeadlineLabel = headline

        row.add(Box.createHorizontalGlue())
        row.add(headline)
        row.add(Box.createHorizontalGlue())

        return row
    }

    // -- Row D: Bottom buttons --

    private fun buildBottomButtons(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT

        row.add(Box.createHorizontalGlue())
        row.add(createStyledButton("Keep defaults", isAccent = false) { closeWizard() })
        row.add(Box.createHorizontalStrut(JBUI.scale(BTN_GAP)))
        row.add(
            createStyledButton("Open Settings", isAccent = true) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ayu Islands")
                closeWizard()
            },
        )
        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun closeWizard() {
        if (project.isDisposed) return
        FileEditorManager.getInstance(project).closeFile(virtualFile)
    }

    private data class VariantCardSpec(
        val variant: AyuVariant,
        val title: String,
        val subtitle: String,
        val footnote: String,
        val tintColor: Color,
        val baseFill: Color,
    )

    private data class RailCardSpec(
        val title: String,
        val subtitle: String,
        val tooltip: String,
        val hoverColor: Color,
        val cornerIcon: Icon?,
        val subtitleColor: Color = CARD_DESC_COLOR,
        val onClick: (FreeOnboardingPanel) -> Unit,
    )

    companion object {
        private val LOG = logger<FreeOnboardingPanel>()

        // Typography
        private const val CARD_NAME_SIZE = 15
        private const val CARD_DESC_SIZE = 11
        private const val RAIL_TITLE_SIZE = 12

        // Variant cards (reused from premium)
        private const val CARD_PADDING = 12
        private const val CARD_WIDTH = 140
        private const val CARD_HEIGHT = 96
        private const val CARD_GAP = 10
        private const val CARD_DOT_SIZE = 8
        private const val CARD_DOT_MARGIN = 12

        // Card description text color (kept per-panel — font/text styling)
        private val CARD_DESC_COLOR = Color(0x70, 0x76, 0x80)

        // Button row (reused)
        private const val BTN_GAP = 16

        // Scrim (reused)
        private const val SCRIM_FRACTION = 0.65
        private val SCRIM_TOP_COLOR = Color(0x0B, 0x0E, 0x14, 0)
        private val SCRIM_BOTTOM_COLOR = Color(0x0B, 0x0E, 0x14, 220)

        // Gaps (reused)
        private const val BOTTOM_MARGIN = 26
        private const val GAP_SECTION = 36

        // Slightly tighter gap above the trial headline to compensate for the
        // ~10px font-metric leading that JLabel includes at the top of HTML text.
        // Visually this makes the space above/below the trial text equal.
        private const val GAP_SECTION_ABOVE_TRIAL = 26
        private const val GAP_MEDIUM = 14
        private const val GAP_SMALL = 10
        private const val GAP_TINY = 8
        private const val GAP_MICRO = 2

        // SVG geometry — used to position content below the tagline at any tab size.
        // The welcome_board SVGs share a viewBox of 680x590. The tagline
        // "Unified aesthetic engine..." baseline is at y=650 with a ~10px descender
        // in the SVG's font, so y=660 is the safe visual bottom.
        private const val SVG_VIEW_BOX_WIDTH = 1600.0
        private const val SVG_VIEW_BOX_HEIGHT = 1000.0
        private const val SVG_TAGLINE_BOTTOM_Y = 660.0

        // SVG tagline "Unified aesthetic engine..." font-size in viewBox units.
        // Multiplied by cover scale to match the on-screen rendered size.
        private const val SVG_TAGLINE_FONT_PX = 26.0

        // Fallback font size used at construction time before updateDynamicLayout runs.
        private const val TRIAL_HEADLINE_SIZE = 15

        // Trial headline dynamic font clamp to avoid extremes at very small/large tabs.
        private const val TRIAL_FONT_MIN = 14
        private const val TRIAL_FONT_MAX = 34

        // Trial headline: single muted slate base color readable on every hero SVG,
        // plus the shared warm amber "unlocked" highlight used across all variants.
        private const val TRIAL_TEXT_BASE = "#9fa9ba"
        private const val TRIAL_UNLOCKED_HEX = "#886428"
        private const val TRIAL_DAYS_LEFT_LABEL = "30 days left"

        // Accent swatch strip (new)
        private const val SWATCH_DIAMETER = 28
        private const val SWATCH_GAP = 10
        private const val SWATCH_HOVER_LIFT = 2
        private const val SWATCH_RING_EXPAND = 3
        private const val SWATCH_RING_ALPHA = 160

        // Footer rail (new)
        private const val RAIL_CARD_WIDTH = 112
        private const val RAIL_CARD_HEIGHT = 54
        private const val RAIL_CARD_PADDING = 10
        private const val RAIL_DIVIDER_HEIGHT = 40
        private const val RAIL_DIVIDER_GAP = 12

        // Trial cue color for Glow/Fonts subtitle — warm amber (Ayu)
        private val TRIAL_CUE_COLOR = Color(0xFF, 0xCC, 0x66)

        // Variant card base fills — each variant gets its own tonal background
        private val MIRAGE_BASE_FILL = Color(0x1F, 0x24, 0x30)
        private val DARK_BASE_FILL = Color(0x0F, 0x13, 0x1A)
        private val LIGHT_BASE_FILL = Color(0x26, 0x1E, 0x13)

        // Variant hover tint colors (follow Ayu default accents)
        private val MIRAGE_TINT = Color(0xFF, 0xCC, 0x66)
        private val DARK_TINT = Color(0xE6, 0xB4, 0x50)
        private val LIGHT_TINT = Color(0xF2, 0x97, 0x18)

        // Teaser and community hover tints
        private val GLOW_TEASER_TINT = Color(0x73, 0xD0, 0xFF)
        private val FONT_TEASER_TINT = Color(0x95, 0xE6, 0xCB)
        private val ROTATE_TEASER_TINT = Color(0xDF, 0xBF, 0xFF)
        private val PLUGINS_TEASER_TINT = Color(0xD5, 0xFF, 0x80)
        private val COMMUNITY_COLOR = Color(0x36, 0xA3, 0xD9)

        // Discussions URLs
        private const val DISCUSSIONS_SHOW_SETUP =
            "https://github.com/barad1tos/ayu-jetbrains/discussions/categories/show-your-setup"
        private const val DISCUSSIONS_FEATURE_REQUESTS =
            "https://github.com/barad1tos/ayu-jetbrains/discussions/categories/feature-requests"

        private val VARIANT_CARDS =
            listOf(
                VariantCardSpec(
                    AyuVariant.MIRAGE,
                    "Mirage",
                    "Warm blue twilight",
                    "Balanced contrast",
                    MIRAGE_TINT,
                    MIRAGE_BASE_FILL,
                ),
                VariantCardSpec(
                    AyuVariant.DARK,
                    "Dark",
                    "Deep midnight",
                    "Maximum focus",
                    DARK_TINT,
                    DARK_BASE_FILL,
                ),
                VariantCardSpec(
                    AyuVariant.LIGHT,
                    "Light",
                    "Warm daylight",
                    "Low eye strain",
                    LIGHT_TINT,
                    LIGHT_BASE_FILL,
                ),
            )

        private val RAIL_GLOW_TEASER =
            RailCardSpec(
                title = "Glow",
                subtitle = TRIAL_DAYS_LEFT_LABEL,
                tooltip = "Included in your 30-day trial",
                hoverColor = GLOW_TEASER_TINT,
                cornerIcon = null,
                subtitleColor = TRIAL_CUE_COLOR,
                onClick = { panel -> panel.openSettings() },
            )

        private val RAIL_FONT_TEASER =
            RailCardSpec(
                title = "Fonts",
                subtitle = TRIAL_DAYS_LEFT_LABEL,
                tooltip = "Included in your 30-day trial",
                hoverColor = FONT_TEASER_TINT,
                cornerIcon = null,
                subtitleColor = TRIAL_CUE_COLOR,
                onClick = { panel -> panel.openSettings() },
            )

        private val RAIL_ROTATE_TEASER =
            RailCardSpec(
                title = "Auto-Rotate",
                subtitle = TRIAL_DAYS_LEFT_LABEL,
                tooltip = "Rotate accent colors on a schedule — included in your 30-day trial",
                hoverColor = ROTATE_TEASER_TINT,
                cornerIcon = null,
                subtitleColor = TRIAL_CUE_COLOR,
                onClick = { panel -> panel.openSettings() },
            )

        private val RAIL_PLUGINS_TEASER =
            RailCardSpec(
                title = "Plugin Sync",
                subtitle = TRIAL_DAYS_LEFT_LABEL,
                tooltip = "Propagate accent to CodeGlance Pro and Indent Rainbow — included in your 30-day trial",
                hoverColor = PLUGINS_TEASER_TINT,
                cornerIcon = null,
                subtitleColor = TRIAL_CUE_COLOR,
                onClick = { panel -> panel.openSettings() },
            )

        private val RAIL_SHARE_LINK =
            RailCardSpec(
                title = "Share setup",
                subtitle = "Discussions",
                tooltip = "Share your Ayu Islands setup on GitHub Discussions",
                hoverColor = COMMUNITY_COLOR,
                cornerIcon = AllIcons.Actions.Share,
                onClick = { BrowserUtil.browse(DISCUSSIONS_SHOW_SETUP) },
            )

        private val RAIL_FEATURE_LINK =
            RailCardSpec(
                title = "Feedback",
                subtitle = "Discussions",
                tooltip = "Suggest a feature on GitHub Discussions",
                hoverColor = COMMUNITY_COLOR,
                cornerIcon = AllIcons.General.BalloonInformation,
                onClick = { BrowserUtil.browse(DISCUSSIONS_FEATURE_REQUESTS) },
            )
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ayu Islands")
        closeWizard()
    }
}
