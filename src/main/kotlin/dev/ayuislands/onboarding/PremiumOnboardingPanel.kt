@file:Suppress("DialogTitleCapitalization", "TooManyFunctions")

package dev.ayuislands.onboarding

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ImageLoader
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontInstaller
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

    /** Hero SVG classpath — resolved from current variant at init. */
    private val heroPath: String? = resolveHeroPath()

    private var heroCache: Pair<Triple<String?, Int, Int>?, java.awt.Image?> = null to null

    private var topStrut: Component? = null
    private var contentWrapper: JPanel? = null
    private var trialHeadlineLabel: JBLabel? = null
    private var fontRowContainer: JPanel? = null
    private val installingFonts: MutableSet<FontPreset> = mutableSetOf()
    private val scaler = ContentScaler()

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

    /** Render SVG as a full-tab background with "cover" scaling via [ImageLoader]. */
    private fun paintBackground(g2: Graphics2D) {
        val path = heroPath ?: return
        if (width <= 0 || height <= 0) return
        val coverSize = computeCoverDimensions(width, height, SVG_VIEW_BOX_WIDTH, SVG_VIEW_BOX_HEIGHT)
        val key = Triple(path, coverSize.first, coverSize.second)
        if (heroCache.first != key) {
            heroCache = key to loadScaledHero(path, coverSize.first, coverSize.second)
        }
        val image = heroCache.second ?: return
        drawCoveredImage(g2, image, width, height, coverSize)
    }

    private fun resolveHeroPath(): String? {
        val variant = AyuVariant.detect() ?: AyuVariant.MIRAGE
        val path =
            when (variant) {
                AyuVariant.MIRAGE -> "/onboarding/welcome_board_mirage.svg"
                AyuVariant.DARK -> "/onboarding/welcome_board_dark.svg"
                AyuVariant.LIGHT -> "/onboarding/welcome_board_light.svg"
            }
        return if (PremiumOnboardingPanel::class.java.getResource(path) != null) path else null
    }

    private fun loadScaledHero(
        path: String,
        targetW: Int,
        targetH: Int,
    ): java.awt.Image? =
        try {
            val raw = ImageLoader.loadFromResource(path, PremiumOnboardingPanel::class.java)
            raw?.let { ImageLoader.scaleImage(it, targetW, targetH) }
        } catch (exception: java.io.IOException) {
            LOG.warn("Failed to load hero SVG $path", exception)
            null
        }

    /** Bottom gradient scrim for text readability over the image. */
    private fun paintScrim(g2: Graphics2D) {
        paintScrim(g2, width, height, SCRIM_CONFIG)
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
        // Section A: glow preset cards + font preset cards
        // Section B: trial headline (standalone, centered)
        // Section C: footer rail + action buttons
        val fontRow = buildFontRow()
        fontRowContainer = fontRow
        val content =
            buildWizardSection(
                listOf(
                    SectionEntry(buildPresetCardsRow(), gapBeforePx = 0),
                    SectionEntry(fontRow, gapBeforePx = GAP_MEDIUM, hideBelow = COMPACT_THRESHOLD),
                    SectionEntry(buildTrialMessage(), gapBeforePx = GAP_SECTION_ABOVE_TRIAL),
                    SectionEntry(buildFooterRail(), gapBeforePx = GAP_SECTION, hideBelow = COMPACT_THRESHOLD),
                    SectionEntry(buildBottomButtons(), gapBeforePx = GAP_MEDIUM),
                ),
                scaler = scaler,
            )

        val handle = installWizardContent(this, content, BOTTOM_MARGIN)
        topStrut = handle.topStrut
        contentWrapper = handle.wrapper

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
        updateWizardDynamicLayout(
            panelWidth = width,
            panelHeight = height,
            handle = WizardContentHandle(strut, wrapper),
            geometry = SVG_GEOMETRY,
            onTrialFontChange = ::updateTrialHeadline,
        )
        updateContentScale()
    }

    private fun updateContentScale() {
        updateContentScale(width, height, topStrut?.preferredSize?.height ?: 0, SCALE_CONFIG, scaler)
    }

    private fun updateTrialHeadline(fontPx: Float) {
        val label = trialHeadlineLabel ?: return
        label.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        label.minimumSize = Dimension(0, 0)
        label.font = label.font.deriveFont(Font.PLAIN, fontPx)
        label.text = TRIAL_HEADLINE_HTML
        clampLabelToPreferred(label)
    }

    private fun buildTrialMessage(): JPanel {
        val headline = JBLabel(TRIAL_HEADLINE_HTML)
        headline.font = headline.font.deriveFont(Font.PLAIN, JBUI.scale(TRIAL_HEADLINE_SIZE).toFloat())
        clampLabelToPreferred(headline)
        trialHeadlineLabel = headline
        return centeredHorizontalRow(headline)
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

    private fun createRailDivider(): JComponent = createRailDivider(RAIL_DIVIDER_HEIGHT, RAIL_CARD_HEIGHT)

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

    private fun createRailCard(spec: RailCardSpec): JPanel = createRailCard(spec, RAIL_CARD_LAYOUT, scaler)

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
                    configureCardPanel(this, CARD_PADDING, CARD_WIDTH, CARD_HEIGHT, scaler)
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
                    paintCardChrome(g2, width, height, hovered, glowColor, contentScale = scaler.currentScale)

                    // Color dot
                    g2.color = glowColor
                    val dotSize = JBUI.scale(CARD_DOT_SIZE)
                    val dotMargin = JBUI.scale(CARD_DOT_MARGIN)
                    g2.fillOval(width - dotMargin - dotSize, dotMargin, dotSize, dotSize)

                    super.paintComponent(graphics)
                }
            }
        cardPanel.isOpaque = false

        attachCardLabels(
            card = cardPanel,
            content =
                CardLabelContent(
                    title = card.name,
                    subtitle = card.glowDescription,
                    footnote = card.fontName,
                ),
            style = PRESET_CARD_LABEL_STYLE,
            scaler = scaler,
        )

        return cardPanel
    }

    // -- Font row --

    private fun buildFontRow(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT
        populateFontRow(row)
        return row
    }

    private fun populateFontRow(row: JPanel) {
        row.removeAll()
        row.add(Box.createHorizontalGlue())
        for ((index, preset) in FONT_PRESETS.withIndex()) {
            row.add(createFontCard(preset))
            if (index < FONT_PRESETS.lastIndex) {
                row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
            }
        }
        row.add(Box.createHorizontalGlue())
    }

    private fun refreshFontRow() {
        val row = fontRowContainer ?: return
        populateFontRow(row)
        row.revalidate()
        row.repaint()
    }

    @Suppress("LongMethod")
    private fun createFontCard(preset: FontPreset): JPanel {
        val entry = FontCatalog.forPreset(preset)
        val state = AyuIslandsSettings.getInstance().state
        val installed = state.installedFonts.contains(entry.familyName)
        val installing = installingFonts.contains(preset)
        val tint = FONT_CARD_TINT

        val cardPanel =
            object : JPanel() {
                private var hovered = false

                init {
                    configureCardPanel(this, FONT_CARD_PADDING, FONT_CARD_WIDTH, FONT_CARD_HEIGHT, scaler)
                    if (installing) {
                        cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
                    }
                    toolTipText =
                        if (installed) {
                            "${entry.displayName} — installed. Click to apply."
                        } else {
                            "Download and install ${entry.displayName} (~${entry.approxSizeMb} MB)"
                        }
                    isEnabled = !installing

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
                                handleFontCardClick(preset)
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
                    paintCardChrome(g2, width, height, hovered && !installing, tint, contentScale = scaler.currentScale)
                    super.paintComponent(graphics)
                }
            }
        cardPanel.isOpaque = false

        val titleLabel = JBLabel(entry.displayName)
        val titleBaseFont =
            if (installed) {
                Font(entry.familyName, Font.PLAIN, JBUI.scale(FONT_TITLE_SIZE))
            } else {
                titleLabel.font.deriveFont(Font.BOLD, JBUI.scale(FONT_TITLE_SIZE).toFloat())
            }
        titleLabel.font = titleBaseFont
        titleLabel.foreground = if (installing) CARD_DESC_COLOR else Color.WHITE
        titleLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(titleLabel)

        cardPanel.add(Box.createVerticalStrut(JBUI.scale(GAP_TINY)))

        val subtitleText: String
        val subtitleColor: Color
        when {
            installing -> {
                subtitleText = "Installing…"
                subtitleColor = CARD_DESC_COLOR
            }
            installed -> {
                subtitleText = "✓ Installed"
                subtitleColor = TRIAL_CUE_COLOR
            }
            else -> {
                subtitleText = "~${entry.approxSizeMb} MB"
                subtitleColor = CARD_DESC_COLOR
            }
        }
        val subtitleLabel = JBLabel(subtitleText)
        subtitleLabel.font = subtitleLabel.font.deriveFont(JBUI.scale(CARD_DESC_SIZE).toFloat())
        subtitleLabel.foreground = subtitleColor
        subtitleLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(subtitleLabel)

        cardPanel.add(Box.createVerticalGlue())
        return cardPanel
    }

    private fun handleFontCardClick(preset: FontPreset) {
        if (preset in installingFonts) return
        val entry = FontCatalog.forPreset(preset)
        val state = AyuIslandsSettings.getInstance().state
        if (state.installedFonts.contains(entry.familyName)) {
            FontInstaller.applyOnly(preset, project)
            return
        }
        if (!confirmFontInstall(entry)) return
        installingFonts.add(preset)
        refreshFontRow()
        FontInstaller.install(preset, project) {
            installingFonts.remove(preset)
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) refreshFontRow()
            }
        }
    }

    private fun confirmFontInstall(entry: FontCatalog.Entry): Boolean {
        val message =
            "Ayu Islands will download ${entry.displayName} (~${entry.approxSizeMb} MB, " +
                "SIL Open Font License) from GitHub and install it to:\n\n" +
                "    ${platformFontDirLabel()}\n\n" +
                "This is a user-level install — no admin rights required.\n" +
                "You can remove it anytime from that folder."
        return MessageDialogBuilder
            .yesNo("Install ${entry.displayName}?", message)
            .yesText("Install")
            .noText("Cancel")
            .ask(project)
    }

    private fun platformFontDirLabel(): String =
        when {
            SystemInfo.isMac -> "~/Library/Fonts"
            SystemInfo.isWindows -> "%LOCALAPPDATA%\\Microsoft\\Windows\\Fonts"
            else -> "~/.local/share/fonts"
        }

    private fun buildBottomButtons(): JPanel =
        buildWizardBottomButtons(
            gapPx = BTN_GAP,
            onKeepDefaults = { closeWizard() },
            onOpenSettings = {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ayu Islands")
                closeWizard()
            },
            scaler = scaler,
        )

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

    companion object {
        private val LOG = logger<PremiumOnboardingPanel>()

        // Typography
        private const val CARD_NAME_SIZE = 15
        private const val CARD_DESC_SIZE = 11
        private const val RAIL_TITLE_SIZE = 12

        // Cards
        private const val CARD_PADDING = 12
        private const val CARD_WIDTH = 140
        private const val CARD_HEIGHT = 96
        private const val CARD_GAP = 10

        // Font cards (pinned to Free variant card dimensions)
        private const val FONT_CARD_WIDTH = 130
        private const val FONT_CARD_HEIGHT = 64
        private const val FONT_CARD_PADDING = 12
        private const val FONT_TITLE_SIZE = 11
        private val FONT_CARD_TINT = Color(0x95, 0xE6, 0xCB)
        private val FONT_PRESETS =
            listOf(FontPreset.WHISPER, FontPreset.AMBIENT, FontPreset.NEON, FontPreset.CYBERPUNK)

        // Footer rail
        private const val RAIL_CARD_WIDTH = 112
        private const val RAIL_CARD_HEIGHT = 54
        private const val RAIL_CARD_PADDING = 10
        private const val RAIL_DIVIDER_HEIGHT = 40
        private const val RAIL_DIVIDER_GAP = 12

        // Card dot indicator
        private const val CARD_DOT_SIZE = 8
        private const val CARD_DOT_MARGIN = 12

        // Button row
        private const val BTN_GAP = 16

        // Scrim
        private val SCRIM_CONFIG =
            ScrimConfig(
                fraction = 0.65,
                topColor = Color(0x0B, 0x0E, 0x14, 0),
                bottomColor = Color(0x0B, 0x0E, 0x14, 220),
            )

        // Gaps
        private const val BOTTOM_MARGIN = 26
        private const val GAP_SECTION = 36
        private const val GAP_SECTION_ABOVE_TRIAL = 26
        private const val GAP_MEDIUM = 14
        private const val GAP_SMALL = 10
        private const val GAP_TINY = 8
        private const val GAP_MICRO = 2

        // SVG geometry for dynamic tagline tracking (mirrors FreeOnboardingPanel)
        private const val SVG_VIEW_BOX_WIDTH = 1600.0
        private const val SVG_VIEW_BOX_HEIGHT = 1000.0
        private const val SVG_TAGLINE_BOTTOM_Y = 660.0
        private const val SVG_TAGLINE_FONT_PX = 26.0

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

        // Card description text color (panel-local)
        private val CARD_DESC_COLOR = Color(0x70, 0x76, 0x80)

        private val SVG_GEOMETRY =
            WizardSvgGeometry(
                viewBoxWidth = SVG_VIEW_BOX_WIDTH,
                viewBoxHeight = SVG_VIEW_BOX_HEIGHT,
                taglineBottomY = SVG_TAGLINE_BOTTOM_Y,
                taglineFontPx = SVG_TAGLINE_FONT_PX,
                topGapPx = GAP_SMALL,
                trialFontMin = TRIAL_FONT_MIN,
                trialFontMax = TRIAL_FONT_MAX,
            )

        private val PRESET_CARD_LABEL_STYLE =
            CardLabelStyle(
                titleSizePx = CARD_NAME_SIZE,
                descSizePx = CARD_DESC_SIZE,
                descColor = CARD_DESC_COLOR,
                titleSubtitleGapPx = GAP_TINY,
                subtitleFootnoteGapPx = GAP_MICRO,
            )

        private val RAIL_CARD_LABEL_STYLE =
            CardLabelStyle(
                titleSizePx = RAIL_TITLE_SIZE,
                descSizePx = CARD_DESC_SIZE,
                descColor = CARD_DESC_COLOR,
                titleSubtitleGapPx = GAP_MICRO,
            )

        private val RAIL_CARD_LAYOUT =
            RailCardLayout(
                paddingPx = RAIL_CARD_PADDING,
                widthPx = RAIL_CARD_WIDTH,
                heightPx = RAIL_CARD_HEIGHT,
                dotMarginPx = CARD_DOT_MARGIN,
                labelStyle = RAIL_CARD_LABEL_STYLE,
            )

        // Content scaling (compact hides rail + font row)
        private val SCALE_CONFIG =
            ContentScaleConfig(
                bottomMarginPx = BOTTOM_MARGIN,
                designWidth = 750,
                designContentHeight = 370,
                designContentHeightCompact = 192,
                compactThreshold = 0.75f,
                minScale = 0.5f,
                maxScale = 1.0f,
            )
        private const val COMPACT_THRESHOLD = 0.75f

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
