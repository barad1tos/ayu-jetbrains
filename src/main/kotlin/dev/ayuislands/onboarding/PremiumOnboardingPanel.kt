@file:Suppress("DialogTitleCapitalization", "TooManyFunctions")

package dev.ayuislands.onboarding

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPreset
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
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

/**
 * Onboarding wizard panel with full-tab SVG background, preset cards, and
 * action buttons on a frosted glass backdrop.
 *
 * Constructor is intentionally lightweight — content loads via an
 * AncestorListener attach hook that fires when the panel joins a showing
 * hierarchy, with an invokeLater to unwind any nested EDT pump from
 * [FileEditorManager.openFile]'s internal `waitBlockingAndPumpEdt`.
 */
internal class PremiumOnboardingPanel(
    private val project: Project,
    private val virtualFile: VirtualFile,
) : JPanel(BorderLayout()) {
    private var activeGlowColor: Color? = null
    private var activeGlowWidth: Int = 0
    private var activeGlowAlpha: Float = 0f

    /** Background SVG — loaded once, rescaled on resize. */
    private val heroIcon: Icon? =
        try {
            IconLoader.getIcon("/onboarding/welcome_board_dark.svg", PremiumOnboardingPanel::class.java)
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to load onboarding hero image", exception)
            null
        }

    private var cachedBackgroundIcon: Icon? = null
    private var cachedBackgroundSize: Dimension? = null

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

        val glowColor = activeGlowColor
        if (glowColor != null) {
            paintGlowBorder(g2, glowColor)
        }

        super.paintComponent(graphics)
    }

    /** Render SVG as a full-tab background with "cover" scaling. */
    private fun paintBackground(g2: Graphics2D) {
        val icon = heroIcon ?: return
        val currentSize = Dimension(width, height)

        if (cachedBackgroundIcon == null || cachedBackgroundSize != currentSize) {
            val scale =
                maxOf(
                    width.toDouble() / icon.iconWidth,
                    height.toDouble() / icon.iconHeight,
                )
            cachedBackgroundIcon = IconUtil.scale(icon, null, scale.toFloat())
            cachedBackgroundSize = currentSize
        }

        val scaled = cachedBackgroundIcon ?: return
        val drawX = (width - scaled.iconWidth) / 2
        val drawY = (height - scaled.iconHeight) / 2
        scaled.paintIcon(this, g2, drawX, drawY)
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

    private fun paintGlowBorder(
        g2: Graphics2D,
        color: Color,
    ) {
        val inset = JBUI.scale(GLOW_INSET)
        val arc = JBUI.scale(GLOW_ARC).toDouble()
        val scaledWidth = JBUI.scale(activeGlowWidth)
        val maxAlpha = activeGlowAlpha

        for (layer in scaledWidth downTo 1) {
            val alpha = (maxAlpha * layer.toFloat() / scaledWidth).toInt().coerceIn(0, MAX_ALPHA)
            g2.color = Color(color.red, color.green, color.blue, alpha)
            val offset = (scaledWidth - layer).toDouble()
            g2.draw(
                RoundRectangle2D.Double(
                    inset + offset,
                    inset + offset,
                    width - 2.0 * inset - 2 * offset,
                    height - 2.0 * inset - 2 * offset,
                    arc,
                    arc,
                ),
            )
        }
    }

    private fun setGlowPreview(preset: GlowPreset?) {
        if (preset == null) {
            activeGlowColor = null
            activeGlowWidth = 0
            activeGlowAlpha = 0f
        } else {
            val params = PRESET_GLOW_PARAMS[preset]
            activeGlowColor = PRESET_GLOW_COLORS[preset]
            activeGlowWidth = params?.first ?: GLOW_DEFAULT_WIDTH
            activeGlowAlpha = params?.second ?: GLOW_DEFAULT_ALPHA
        }
        repaint()
    }

    private fun loadContent() {
        // Section A: glow preset cards (content)
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        content.add(buildPresetCardsRow())

        // Section gap A -> B
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_SECTION_ABOVE_TRIAL)))

        // Section B: trial headline (standalone, centered)
        content.add(buildTrialMessage())

        // Section gap B -> C
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_SECTION)))

        // Section C: footer rail + action buttons
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

        // Wrapper with dynamic top strut that tracks the SVG tagline at any tab size.
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
     * Recompute the top strut so content sits just below the SVG tagline,
     * and rescale the trial headline font to match the tagline's on-screen size.
     * Mirrors FreeOnboardingPanel.updateDynamicLayout.
     */
    private fun updateDynamicLayout() {
        val strut = topStrut ?: return
        val wrapper = contentWrapper ?: return
        val w = width.toDouble()
        val h = height.toDouble()
        if (w <= 0 || h <= 0) return

        val scale = maxOf(w / SVG_VIEWBOX_WIDTH, h / SVG_VIEWBOX_HEIGHT)
        val svgHeightOnScreen = SVG_VIEWBOX_HEIGHT * scale
        val svgTopY = (h - svgHeightOnScreen) / 2
        val taglineBottomY = svgTopY + SVG_TAGLINE_BOTTOM_Y * scale
        val topPadding = (taglineBottomY + JBUI.scale(GAP_SMALL)).toInt().coerceAtLeast(0)

        val size = Dimension(0, topPadding)
        strut.preferredSize = size
        strut.maximumSize = Dimension(Int.MAX_VALUE, topPadding)
        strut.minimumSize = size

        val trialFontPx =
            (SVG_TAGLINE_FONT_PX * scale).toInt().coerceIn(TRIAL_FONT_MIN, TRIAL_FONT_MAX)
        updateTrialHeadline(trialFontPx.toFloat())

        wrapper.revalidate()
    }

    private fun updateTrialHeadline(fontPx: Float) {
        val label = trialHeadlineLabel ?: return
        label.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        label.minimumSize = Dimension(0, 0)
        label.font = label.font.deriveFont(Font.PLAIN, fontPx)
        label.text = TRIAL_HEADLINE_HTML
        clampLabelToPreferred(label)
    }

    private fun clampLabelToPreferred(label: JBLabel) {
        val pref = label.preferredSize
        label.maximumSize = Dimension(pref.width, pref.height)
        label.minimumSize = Dimension(pref.width, pref.height)
    }

    private fun buildTrialMessage(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT

        val headline = JBLabel(TRIAL_HEADLINE_HTML)
        headline.font = headline.font.deriveFont(Font.PLAIN, JBUI.scale(TRIAL_HEADLINE_SIZE).toFloat())
        clampLabelToPreferred(headline)
        trialHeadlineLabel = headline

        row.add(Box.createHorizontalGlue())
        row.add(headline)
        row.add(Box.createHorizontalGlue())
        return row
    }

    // -- Footer Rail --

    private fun buildFooterRail(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT

        row.add(Box.createHorizontalGlue())
        row.add(createRailCard(railAutoRotate()))
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(createRailCard(railPluginSync()))
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(createRailCard(railTabGlow()))
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(createRailCard(railWorkspace()))
        row.add(Box.createHorizontalStrut(JBUI.scale(RAIL_DIVIDER_GAP)))
        row.add(createRailDivider())
        row.add(Box.createHorizontalStrut(JBUI.scale(RAIL_DIVIDER_GAP)))
        row.add(createRailCard(railShareSetup()))
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(createRailCard(railFeedback()))
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

    private fun railAutoRotate(): RailCardSpec =
        RailCardSpec(
            title = "Auto-Rotate",
            subtitle = "Accent schedule",
            tooltip = "Rotate accent colors on a schedule",
            hoverColor = ROTATE_TINT,
            cornerIcon = null,
            subtitleColor = TRIAL_CUE_COLOR,
            onClick = { openSettings() },
        )

    private fun railPluginSync(): RailCardSpec =
        RailCardSpec(
            title = "Plugin Sync",
            subtitle = "CodeGlance + IR",
            tooltip = "Propagate accent to CodeGlance Pro and Indent Rainbow",
            hoverColor = PLUGINS_TINT,
            cornerIcon = null,
            subtitleColor = TRIAL_CUE_COLOR,
            onClick = { openSettings() },
        )

    private fun railTabGlow(): RailCardSpec =
        RailCardSpec(
            title = "Tab Glow",
            subtitle = "Underline style",
            tooltip = "Classic UI tab underline thickness and color",
            hoverColor = GLOW_TINT,
            cornerIcon = null,
            subtitleColor = TRIAL_CUE_COLOR,
            onClick = { openSettings() },
        )

    private fun railWorkspace(): RailCardSpec =
        RailCardSpec(
            title = "Workspace",
            subtitle = "Panel widths",
            tooltip = "Auto-fit project, commit, git panel widths",
            hoverColor = WORKSPACE_TINT,
            cornerIcon = null,
            subtitleColor = TRIAL_CUE_COLOR,
            onClick = { openSettings() },
        )

    private fun railShareSetup(): RailCardSpec =
        RailCardSpec(
            title = "Share setup",
            subtitle = "Community",
            tooltip = "Post your Ayu setup in GitHub Discussions",
            hoverColor = COMMUNITY_COLOR,
            cornerIcon = AllIcons.Actions.Share,
            onClick = { BrowserUtil.browse(OnboardingUrls.DISCUSSIONS_SHOW_SETUP) },
        )

    private fun railFeedback(): RailCardSpec =
        RailCardSpec(
            title = "Feedback",
            subtitle = "Feature ideas",
            tooltip = "Request features in GitHub Discussions",
            hoverColor = COMMUNITY_COLOR,
            cornerIcon = AllIcons.General.BalloonInformation,
            onClick = { BrowserUtil.browse(OnboardingUrls.DISCUSSIONS_FEATURE_REQUESTS) },
        )

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
                    paintRailCardChrome(g2, width, height, hovered, spec.hoverColor)

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
        subtitleLabel.font = subtitleLabel.font.deriveFont(JBUI.scale(CARD_DESC_SIZE).toFloat())
        subtitleLabel.foreground = spec.subtitleColor
        subtitleLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(subtitleLabel)

        cardPanel.add(Box.createVerticalGlue())
        return cardPanel
    }

    /** Shadow + fill + glass highlight + border for rail cards. */
    private fun paintRailCardChrome(
        g2: Graphics2D,
        width: Int,
        height: Int,
        hovered: Boolean,
        tintColor: Color,
    ) {
        val arc = JBUI.scale(CARD_ARC)

        // Drop shadow
        val shadowOffset = JBUI.scale(SHADOW_OFFSET_Y)
        for (i in SHADOW_LAYERS downTo 1) {
            g2.color = Color(0, 0, 0, SHADOW_BASE_ALPHA * i)
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
            val topColor = Color(tintColor.red, tintColor.green, tintColor.blue, CARD_HOVER_TOP_ALPHA)
            val bottomColor = Color(tintColor.red, tintColor.green, tintColor.blue, CARD_HOVER_BOTTOM_ALPHA)
            g2.paint = GradientPaint(0f, 0f, topColor, 0f, height.toFloat(), bottomColor)
        } else {
            g2.color = CARD_BG_COLOR
        }
        g2.fillRoundRect(0, 0, width, height, arc, arc)

        // Glass highlight
        val highlightHeight = JBUI.scale(HIGHLIGHT_HEIGHT)
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
                HIGHLIGHT_TOP_COLOR,
                0f,
                highlightHeight.toFloat(),
                HIGHLIGHT_BOTTOM_COLOR,
            )
        g2.fillRect(0, 0, width, highlightHeight)
        g2.clip = oldClip

        // Border
        g2.color =
            if (hovered) {
                Color(tintColor.red, tintColor.green, tintColor.blue, CARD_BORDER_HOVER_ALPHA)
            } else {
                CARD_BORDER_COLOR
            }
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ayu Islands")
        closeWizard()
    }

    private fun buildPresetCardsRow(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT

        row.add(Box.createHorizontalGlue())
        for ((index, card) in PRESETS.withIndex()) {
            row.add(createCardPanel(card))
            if (index < PRESETS.lastIndex) {
                row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
            }
        }
        row.add(Box.createHorizontalGlue())
        return row
    }

    @Suppress("LongMethod")
    private fun createCardPanel(card: PresetCard): JPanel {
        val glowColor = PRESET_GLOW_COLORS[card.glowPreset] ?: OnboardingColors.ACCENT
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
                                outerPanel.setGlowPreview(card.glowPreset)
                                repaint()
                            }

                            override fun mouseExited(event: MouseEvent) {
                                hovered = false
                                outerPanel.setGlowPreview(null)
                                repaint()
                            }

                            override fun mouseClicked(event: MouseEvent) {
                                applyPreset(card.glowPreset, card.fontPreset)
                                closeWizard()
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
                    val arc = JBUI.scale(CARD_ARC)

                    // Drop shadow
                    val shadowOffset = JBUI.scale(SHADOW_OFFSET_Y)
                    for (i in SHADOW_LAYERS downTo 1) {
                        g2.color = Color(0, 0, 0, SHADOW_BASE_ALPHA * i)
                        g2.fillRoundRect(
                            i,
                            i + shadowOffset,
                            width - 2 * i,
                            height - 2 * i,
                            arc,
                            arc,
                        )
                    }

                    // Card fill
                    if (hovered) {
                        val topColor =
                            Color(
                                glowColor.red,
                                glowColor.green,
                                glowColor.blue,
                                CARD_HOVER_TOP_ALPHA,
                            )
                        val bottomColor =
                            Color(
                                glowColor.red,
                                glowColor.green,
                                glowColor.blue,
                                CARD_HOVER_BOTTOM_ALPHA,
                            )
                        g2.paint =
                            GradientPaint(
                                0f,
                                0f,
                                topColor,
                                0f,
                                height.toFloat(),
                                bottomColor,
                            )
                    } else {
                        g2.color = CARD_BG_COLOR
                    }
                    g2.fillRoundRect(0, 0, width, height, arc, arc)

                    // Glass highlight
                    val highlightHeight = JBUI.scale(HIGHLIGHT_HEIGHT)
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
                            HIGHLIGHT_TOP_COLOR,
                            0f,
                            highlightHeight.toFloat(),
                            HIGHLIGHT_BOTTOM_COLOR,
                        )
                    g2.fillRect(0, 0, width, highlightHeight)
                    g2.clip = oldClip

                    // Border
                    g2.color =
                        if (hovered) {
                            Color(
                                glowColor.red,
                                glowColor.green,
                                glowColor.blue,
                                CARD_BORDER_HOVER_ALPHA,
                            )
                        } else {
                            CARD_BORDER_COLOR
                        }
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

                    // Color dot
                    g2.color = glowColor
                    val dotSize = JBUI.scale(CARD_DOT_SIZE)
                    val dotMargin = JBUI.scale(CARD_DOT_MARGIN)
                    g2.fillOval(width - dotMargin - dotSize, dotMargin, dotSize, dotSize)

                    super.paintComponent(graphics)
                }
            }
        cardPanel.isOpaque = false

        val nameLabel = JBLabel(card.name)
        nameLabel.font =
            nameLabel.font.deriveFont(
                Font.BOLD,
                JBUI.scale(CARD_NAME_SIZE).toFloat(),
            )
        nameLabel.foreground = Color.WHITE
        nameLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(nameLabel)

        cardPanel.add(Box.createVerticalStrut(JBUI.scale(GAP_TINY)))

        val glowLabel = JBLabel(card.glowDescription)
        glowLabel.font = glowLabel.font.deriveFont(JBUI.scale(CARD_DESC_SIZE).toFloat())
        glowLabel.foreground = CARD_DESC_COLOR
        glowLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(glowLabel)

        cardPanel.add(Box.createVerticalStrut(JBUI.scale(GAP_MICRO)))

        val fontLabel = JBLabel(card.fontName)
        fontLabel.font =
            fontLabel.font.deriveFont(
                Font.ITALIC,
                JBUI.scale(CARD_DESC_SIZE).toFloat(),
            )
        fontLabel.foreground = CARD_DESC_COLOR
        fontLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(fontLabel)

        cardPanel.add(Box.createVerticalGlue())

        return cardPanel
    }

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

    private fun logIncompletePreset(
        presetName: String,
        field: String,
    ) {
        LOG.warn("Preset $presetName missing $field, skipping apply")
    }

    private fun applyPreset(
        glowPreset: GlowPreset,
        fontPreset: FontPreset,
    ) {
        val presetName = glowPreset.name
        val style = glowPreset.style ?: return logIncompletePreset(presetName, "style")
        val intensity = glowPreset.intensity ?: return logIncompletePreset(presetName, "intensity")
        val width = glowPreset.width ?: return logIncompletePreset(presetName, "width")
        val animation = glowPreset.animation ?: return logIncompletePreset(presetName, "animation")
        val state = AyuIslandsSettings.getInstance().state

        state.glowEnabled = true
        state.glowStyle = style.name
        state.glowPreset = glowPreset.name
        state.setIntensityForStyle(style, intensity)
        state.setWidthForStyle(style, width)
        state.glowAnimation = animation.name

        state.fontPresetEnabled = true
        state.fontPresetName = fontPreset.name
        try {
            FontPresetApplicator.apply(
                FontSettings
                    .decode(null, fontPreset)
                    .copy(applyToConsole = state.fontApplyToConsole),
            )
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to apply font preset ${fontPreset.name}", exception)
        }

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                try {
                    GlowOverlayManager.getInstance(project).initialize()
                    GlowOverlayManager.syncGlowForAllProjects()
                } catch (exception: RuntimeException) {
                    LOG.warn("Failed to sync glow after onboarding preset", exception)
                }
            }
        }

        LOG.info("Onboarding preset applied: ${glowPreset.name}")
    }

    private data class PresetCard(
        val name: String,
        val glowDescription: String,
        val fontName: String,
        val glowPreset: GlowPreset,
        val fontPreset: FontPreset,
    )

    private data class RailCardSpec(
        val title: String,
        val subtitle: String,
        val tooltip: String,
        val hoverColor: Color,
        val cornerIcon: Icon?,
        val subtitleColor: Color = CARD_DESC_COLOR,
        val onClick: () -> Unit,
    )

    companion object {
        private val LOG = logger<PremiumOnboardingPanel>()

        // Typography
        private const val CARD_NAME_SIZE = 15
        private const val CARD_DESC_SIZE = 11
        private const val RAIL_TITLE_SIZE = 12

        // Cards
        private const val CARD_ARC = 14
        private const val CARD_PADDING = 16
        private const val CARD_WIDTH = 155
        private const val CARD_HEIGHT = 130
        private const val CARD_GAP = 10

        // Footer rail
        private const val RAIL_CARD_WIDTH = 112
        private const val RAIL_CARD_HEIGHT = 54
        private const val RAIL_CARD_PADDING = 10
        private const val RAIL_DIVIDER_HEIGHT = 40
        private const val RAIL_DIVIDER_GAP = 12

        // Card dot indicator
        private const val CARD_DOT_SIZE = 8
        private const val CARD_DOT_MARGIN = 12

        // Card shadow
        private const val SHADOW_LAYERS = 4
        private const val SHADOW_OFFSET_Y = 2
        private const val SHADOW_BASE_ALPHA = 12

        // Card glass highlight
        private const val HIGHLIGHT_HEIGHT = 3
        private val HIGHLIGHT_TOP_COLOR = Color(255, 255, 255, 8)
        private val HIGHLIGHT_BOTTOM_COLOR = Color(255, 255, 255, 0)

        // Button row
        private const val BTN_GAP = 16

        // Scrim
        private const val SCRIM_FRACTION = 0.65
        private val SCRIM_TOP_COLOR = Color(0x0B, 0x0E, 0x14, 0)
        private val SCRIM_BOTTOM_COLOR = Color(0x0B, 0x0E, 0x14, 220)

        // Gaps
        private const val BOTTOM_MARGIN = 26
        private const val GAP_SECTION = 36
        private const val GAP_SECTION_ABOVE_TRIAL = 26
        private const val GAP_MEDIUM = 14
        private const val GAP_SMALL = 10
        private const val GAP_TINY = 8
        private const val GAP_MICRO = 2

        // SVG geometry for dynamic tagline tracking (mirrors FreeOnboardingPanel)
        private const val SVG_VIEWBOX_WIDTH = 680.0
        private const val SVG_VIEWBOX_HEIGHT = 590.0
        private const val SVG_TAGLINE_BOTTOM_Y = 372.0
        private const val SVG_TAGLINE_FONT_PX = 13.0

        // Trial headline
        private const val TRIAL_HEADLINE_SIZE = 15
        private const val TRIAL_FONT_MIN = 14
        private const val TRIAL_FONT_MAX = 34
        private const val TRIAL_TEXT_COLOR = "#9fa9ba"
        private const val TRIAL_UNLOCKED_HEX = "#886428"
        private const val TRIAL_HEADLINE_HTML =
            "<html><body style='margin:0;padding:0;font-family:sans-serif;color:$TRIAL_TEXT_COLOR'>" +
                "All features <i><span style='color:$TRIAL_UNLOCKED_HEX'>unlocked</span></i> for 30 days" +
                "</body></html>"

        // Glow border
        private const val GLOW_INSET = 4
        private const val GLOW_ARC = 16
        private const val GLOW_DEFAULT_WIDTH = 6
        private const val GLOW_DEFAULT_ALPHA = 80f
        private const val MAX_ALPHA = 255

        // Per-preset glow params
        private val PRESET_GLOW_PARAMS =
            mapOf(
                GlowPreset.WHISPER to (4 to 50f),
                GlowPreset.AMBIENT to (7 to 70f),
                GlowPreset.NEON to (10 to 110f),
                GlowPreset.CYBERPUNK to (14 to 140f),
            )

        // Card colors (panel-local)
        private val CARD_BG_COLOR = Color(0x17, 0x1B, 0x24)
        private val CARD_BORDER_COLOR = Color(0x2A, 0x2F, 0x3A)
        private val CARD_DESC_COLOR = Color(0x70, 0x76, 0x80)

        // Card hover alpha
        private const val CARD_HOVER_TOP_ALPHA = 40
        private const val CARD_HOVER_BOTTOM_ALPHA = 10
        private const val CARD_BORDER_HOVER_ALPHA = 100

        // Rail tints
        private val TRIAL_CUE_COLOR = Color(0xFF, 0xCC, 0x66)
        private val ROTATE_TINT = Color(0xDF, 0xBF, 0xFF)
        private val PLUGINS_TINT = Color(0xD5, 0xFF, 0x80)
        private val GLOW_TINT = Color(0x73, 0xD0, 0xFF)
        private val WORKSPACE_TINT = Color(0x95, 0xE6, 0xCB)
        private val COMMUNITY_COLOR = Color(0x36, 0xA3, 0xD9)

        // Preset glow colors
        private val PRESET_GLOW_COLORS =
            mapOf(
                GlowPreset.WHISPER to Color(0xFF, 0xCC, 0x66),
                GlowPreset.AMBIENT to Color(0x95, 0xE6, 0xCB),
                GlowPreset.NEON to Color(0x36, 0xA3, 0xD9),
                GlowPreset.CYBERPUNK to Color(0xF0, 0x71, 0x78),
            )

        private val PRESETS =
            listOf(
                PresetCard("Whisper", "Soft glow", "Victor Mono", GlowPreset.WHISPER, FontPreset.WHISPER),
                PresetCard("Ambient", "Gradient + Breathe", "Maple Mono", GlowPreset.AMBIENT, FontPreset.AMBIENT),
                PresetCard("Neon", "Sharp Neon", "Monaspace Neon", GlowPreset.NEON, FontPreset.NEON),
                PresetCard("Cyberpunk", "Sharp + Pulse", "Monaspace Xenon", GlowPreset.CYBERPUNK, FontPreset.CYBERPUNK),
            )
    }
}
