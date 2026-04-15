package dev.ayuislands.whatsnew

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

/**
 * The What's New release showcase panel.
 *
 * Loads its content lazily once attached to the editor — the manifest read +
 * image decode happens on the first `ancestorAdded` callback, mirroring the
 * onboarding panels' pattern. This avoids work during the synchronous
 * `FileEditorManager.openFile` call (which blocks the EDT inside
 * `waitBlockingAndPumpEdt`) and avoids tripping IntelliJ's slow-operation
 * detector.
 *
 * Layout: hero header (title + tagline) on top, scrollable column of slide
 * cards in the middle, footer with action buttons at the bottom.
 */
internal class WhatsNewPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private var loaded = false

    init {
        background = JBColor.background()
        addAncestorListener(
            object : AncestorListener {
                override fun ancestorAdded(event: AncestorEvent) {
                    if (loaded) return
                    loaded = true
                    SwingUtilities.invokeLater { loadContent() }
                }

                override fun ancestorRemoved(event: AncestorEvent) = Unit

                override fun ancestorMoved(event: AncestorEvent) = Unit
            },
        )
    }

    private fun loadContent() {
        try {
            buildContent()
            revalidate()
            repaint()
        } catch (exception: RuntimeException) {
            LOG.error("What's New: failed to build panel content", exception)
        }
    }

    private fun buildContent() {
        val descriptor = pluginDescriptor()
        val version = descriptor?.version ?: ""
        val manifest = WhatsNewManifestLoader.load(version)
        if (manifest == null) {
            // Edge case: tab opened (e.g. via menu) but manifest disappeared
            // mid-flight or version mismatched. Render a graceful fallback.
            add(emptyState(), BorderLayout.CENTER)
            return
        }

        val accent = resolveAccentColor()
        add(buildHeader(manifest, accent), BorderLayout.NORTH)
        add(buildScrollableSlides(manifest, accent), BorderLayout.CENTER)
        add(buildFooter(manifest), BorderLayout.SOUTH)
    }

    private fun buildHeader(
        manifest: WhatsNewManifest,
        accent: Color,
    ): JPanel {
        val header = JPanel()
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        header.isOpaque = false
        header.border =
            JBUI.Borders.empty(
                HEADER_PADDING_TOP,
                HEADER_PADDING_X,
                HEADER_PADDING_BOTTOM,
                HEADER_PADDING_X,
            )

        val titleLabel = JBLabel(manifest.title)
        titleLabel.foreground = accent
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, JBUI.scale(TITLE_FONT_SIZE).toFloat())
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        header.add(titleLabel)

        if (manifest.tagline != null) {
            header.add(Box.createVerticalStrut(JBUI.scale(GAP_TITLE_TAGLINE)))
            val tagline = JBLabel(manifest.tagline)
            tagline.foreground = JBColor.foreground()
            tagline.font = tagline.font.deriveFont(JBUI.scale(TAGLINE_FONT_SIZE).toFloat())
            tagline.alignmentX = Component.LEFT_ALIGNMENT
            header.add(tagline)
        }
        return header
    }

    private fun buildScrollableSlides(
        manifest: WhatsNewManifest,
        accent: Color,
    ): JBScrollPane {
        val column = JPanel()
        column.layout = BoxLayout(column, BoxLayout.Y_AXIS)
        column.isOpaque = false
        column.border = JBUI.Borders.empty(0, HEADER_PADDING_X, HEADER_PADDING_BOTTOM, HEADER_PADDING_X)

        val resourceDir = pluginDescriptor()?.version?.let { WhatsNewManifestLoader.resourceDir(it) } ?: ""

        manifest.slides.forEachIndexed { index, slide ->
            if (index > 0) column.add(Box.createVerticalStrut(JBUI.scale(GAP_BETWEEN_SLIDES)))
            val card = WhatsNewSlideCard.build(slide, resourceDir, accent)
            card.alignmentX = Component.LEFT_ALIGNMENT
            column.add(card)
        }

        val scroll = JBScrollPane(column)
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.viewport.background = background
        scroll.verticalScrollBar.unitIncrement = JBUI.scale(SCROLL_UNIT)
        return scroll
    }

    private fun buildFooter(manifest: WhatsNewManifest): JPanel {
        val footer = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(BUTTON_GAP), JBUI.scale(BUTTON_PADDING_Y)))
        footer.isOpaque = false
        footer.border = JBUI.Borders.emptyTop(BUTTON_PADDING_Y)

        if (manifest.ctaOpenSettingsLabel != null && manifest.ctaOpenSettingsTargetId != null) {
            footer.add(
                buildButton(manifest.ctaOpenSettingsLabel, accent = true) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, manifest.ctaOpenSettingsTargetId)
                },
            )
        }
        footer.add(buildButton("Close", accent = false) { closeTab() })
        return footer
    }

    private fun buildButton(
        text: String,
        accent: Boolean,
        onClick: () -> Unit,
    ): JPanel {
        // We avoid the onboarding panel's createStyledButton because that variant
        // requires a ContentScaler and is sized for the wizard's hero overlay.
        // Here we want a plain editor-tab button matching the rest of the IDE.
        val tint =
            if (accent) {
                resolveAccentColor()
            } else {
                SECONDARY_BUTTON_TINT
            }
        val button = ShowWhatsNewButton(text, tint, accent, onClick)
        return button
    }

    private fun closeTab() {
        try {
            val manager =
                com.intellij.openapi.fileEditor.FileEditorManager
                    .getInstance(project)
            manager.openFiles
                .filterIsInstance<WhatsNewVirtualFile>()
                .forEach { manager.closeFile(it) }
        } catch (exception: RuntimeException) {
            LOG.warn("What's New: close tab failed", exception)
        }
    }

    private fun emptyState(): JPanel {
        val center = JPanel(BorderLayout())
        center.isOpaque = false
        val label = JBLabel("No release notes available for this version.", JBLabel.CENTER)
        label.foreground = JBColor.foreground()
        center.add(label, BorderLayout.CENTER)
        return center
    }

    private fun resolveAccentColor(): Color {
        val variant = AyuVariant.detect() ?: AyuVariant.MIRAGE
        val hex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        return runCatching { Color.decode(hex) }.getOrDefault(FALLBACK_ACCENT)
    }

    private fun pluginDescriptor() =
        com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId
                .getId("com.ayuislands.theme"),
        )

    @Suppress("MagicNumber")
    companion object {
        private val LOG = logger<WhatsNewPanel>()
        private const val HEADER_PADDING_TOP = 32
        private const val HEADER_PADDING_BOTTOM = 16
        private const val HEADER_PADDING_X = 32
        private const val TITLE_FONT_SIZE = 28
        private const val TAGLINE_FONT_SIZE = 14
        private const val GAP_TITLE_TAGLINE = 8
        private const val GAP_BETWEEN_SLIDES = 16
        private const val SCROLL_UNIT = 16
        private const val BUTTON_GAP = 8
        private const val BUTTON_PADDING_Y = 12

        // RGB channels for the secondary button tint. Fully saturated black on
        // light mode, fully bright white on dark mode. Alpha = 24/255 (~9%) for
        // a subtle hairline ghost fill that doesn't compete with the accent CTA.
        private const val RGB_MIN = 0
        private const val RGB_MAX = 255
        private const val SECONDARY_TINT_ALPHA = 24

        // Hex constant for the Ayu Mirage gold accent — used as the last-resort
        // fallback when the plugin's current accent color fails to decode.
        private const val FALLBACK_ACCENT_RGB = 0xFFCC66

        private val SECONDARY_BUTTON_TINT: JBColor =
            JBColor(
                Color(RGB_MIN, RGB_MIN, RGB_MIN, SECONDARY_TINT_ALPHA),
                Color(RGB_MAX, RGB_MAX, RGB_MAX, SECONDARY_TINT_ALPHA),
            )

        private val FALLBACK_ACCENT: JBColor =
            JBColor(Color(FALLBACK_ACCENT_RGB), Color(FALLBACK_ACCENT_RGB))
    }
}
