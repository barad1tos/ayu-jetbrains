package dev.ayuislands.whatsnew

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.onboarding.ContentScaler
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component.CENTER_ALIGNMENT
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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

    // Shared scaler instance — reused from the onboarding wizard so the What's
    // New tab responds to IDE-window resize with the same proportional logic
    // (cards shrink, labels shrink, gaps shrink, all in sync).
    private val scaler = ContentScaler()

    private val ancestorListener =
        object : AncestorListener {
            override fun ancestorAdded(event: AncestorEvent) {
                if (loaded) return
                loaded = true
                SwingUtilities.invokeLater { loadContent() }
            }

            override fun ancestorRemoved(event: AncestorEvent) = Unit

            override fun ancestorMoved(event: AncestorEvent) = Unit
        }

    private val componentListener =
        object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (!loaded) return
                applyResponsiveScale()
            }
        }

    private val disposed =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    init {
        background = JBColor.background()
        addAncestorListener(ancestorListener)
        addComponentListener(componentListener)
    }

    /**
     * Detaches listeners and drops [ContentScaler] component refs. Called from
     * [WhatsNewEditor.dispose] so that closing the tab does not leak labels,
     * gaps, or the panel itself via the listener chain.
     *
     * Idempotent: a double-dispose (provider bug, split-editor close race) is
     * a no-op rather than a crash. Logs WARN on the second call so the race
     * is visible in idea.log instead of silently masked.
     */
    fun dispose() {
        if (!disposed.compareAndSet(false, true)) {
            LOG.warn("What's New: dispose called more than once on the same panel")
            return
        }
        removeAncestorListener(ancestorListener)
        removeComponentListener(componentListener)
        scaler.clear()
    }

    /**
     * Recomputes the content scale based on the tab's current width and applies
     * it to every registered card/label/gap via [ContentScaler]. We use width
     * (not area) because the tab is vertically scrollable — users can always
     * scroll to see more vertical content, but horizontal overflow is ugly.
     */
    private fun applyResponsiveScale() {
        val panelWidth = width
        if (panelWidth <= 0) return
        val scale = computeScale(panelWidth, JBUI.scale(DESIGN_WIDTH))
        LOG.debug("What's New: applyResponsiveScale width=$panelWidth scale=$scale")
        scaler.apply(scale)
        revalidate()
        repaint()
    }

    private fun loadContent() {
        // loadContent is dispatched via SwingUtilities.invokeLater from
        // ancestorAdded. If the tab is closed in the same EDT pump, dispose()
        // fires before the queued loadContent runs. Without this guard the
        // panel would re-populate itself post-dispose and leak listeners.
        if (disposed.get()) {
            LOG.info("What's New: loadContent skipped — panel already disposed")
            return
        }
        try {
            scaler.clear()
            removeAll()
            buildContent()
            applyResponsiveScale()
            revalidate()
            repaint()
        } catch (exception: RuntimeException) {
            LOG.error("What's New: failed to build panel content", exception)
            // Don't leave the user staring at a blank pane — replace with an
            // emptyState so they at least see "no content" instead of nothing.
            // Also clear scaler refs that may have been registered before the
            // throw, so a partially-built header doesn't leak labels/gaps.
            removeAll()
            scaler.clear()
            add(emptyState(), BorderLayout.CENTER)
            revalidate()
            repaint()
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
        add(buildFooter(manifest, accent), BorderLayout.SOUTH)
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
        titleLabel.alignmentX = LEFT_ALIGNMENT
        header.add(titleLabel)
        scaler.registerLabel(titleLabel, TITLE_FONT_SIZE, Font.BOLD)

        if (manifest.tagline != null) {
            val titleTaglineGap = Box.createVerticalStrut(JBUI.scale(GAP_TITLE_TAGLINE))
            header.add(titleTaglineGap)
            scaler.registerGap(titleTaglineGap, GAP_TITLE_TAGLINE)

            val tagline = JBLabel(manifest.tagline)
            tagline.foreground = JBColor.foreground()
            tagline.font = tagline.font.deriveFont(JBUI.scale(TAGLINE_FONT_SIZE).toFloat())
            tagline.alignmentX = LEFT_ALIGNMENT
            header.add(tagline)
            scaler.registerLabel(tagline, TAGLINE_FONT_SIZE, Font.PLAIN)
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

        var addedCount = 0
        manifest.slides.forEachIndexed { index, slide ->
            val titleColor = TITLE_PALETTE[index % TITLE_PALETTE.size]
            val card =
                try {
                    WhatsNewSlideCard.build(slide, resourceDir, accent, titleColor, scaler)
                } catch (exception: RuntimeException) {
                    // One bad slide must not kill the others. Log and skip so
                    // the user still sees the rest of the release showcase.
                    LOG.warn("What's New: failed to build slide '${slide.title}' — skipped", exception)
                    return@forEachIndexed
                }
            if (addedCount > 0) {
                val interSlideGap = Box.createVerticalStrut(JBUI.scale(GAP_BETWEEN_SLIDES))
                column.add(interSlideGap)
                scaler.registerGap(interSlideGap, GAP_BETWEEN_SLIDES)
            }
            column.add(centerInRow(card, CENTER_ALIGNMENT))
            addedCount++
        }

        if (addedCount == 0) {
            // All slides threw — user would see a bare header + footer with an
            // empty middle. Wrap the fallback label in centerInRow so BoxLayout
            // Y_AXIS renders the BorderLayout emptyState at a predictable width
            // instead of stretching or collapsing it in the scroll viewport.
            LOG.warn("What's New: no slides rendered — falling back to emptyState")
            column.add(centerInRow(emptyState(), CENTER_ALIGNMENT))
        }

        val scroll = JBScrollPane(column)
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.viewport.background = background
        scroll.verticalScrollBar.unitIncrement = JBUI.scale(SCROLL_UNIT)
        return scroll
    }

    private fun buildFooter(
        manifest: WhatsNewManifest,
        accent: Color,
    ): JPanel {
        val footer = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(BUTTON_GAP), JBUI.scale(BUTTON_PADDING_Y)))
        footer.isOpaque = false
        footer.border = JBUI.Borders.emptyTop(BUTTON_PADDING_Y)

        if (manifest.ctaOpenSettingsLabel != null && manifest.ctaOpenSettingsTargetId != null) {
            footer.add(
                buildButton(manifest.ctaOpenSettingsLabel, tint = accent, isAccent = true) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, manifest.ctaOpenSettingsTargetId)
                },
            )
        }
        footer.add(buildButton("Close", tint = SECONDARY_BUTTON_TINT, isAccent = false) { closeTab() })
        return footer
    }

    private fun buildButton(
        text: String,
        tint: Color,
        isAccent: Boolean,
        onClick: () -> Unit,
    ): JPanel {
        // We avoid the onboarding panel's createStyledButton because that variant
        // requires a ContentScaler and is sized for the wizard's hero overlay.
        // Here we want a plain editor-tab button matching the rest of the IDE.
        return ShowWhatsNewButton(text, tint, isAccent, onClick)
    }

    private fun closeTab() {
        try {
            val manager =
                com.intellij.openapi.fileEditor.FileEditorManager
                    .getInstance(project)
            // Snapshot via toList() before calling closeFile — closeFile
            // mutates openFiles, and iterating the live view could otherwise
            // skip or double-visit entries if another listener reacts.
            val targets = manager.openFiles.filterIsInstance<WhatsNewVirtualFile>().toList()
            targets.forEach { manager.closeFile(it) }
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
        return runCatching { Color.decode(hex) }
            .onFailure { LOG.warn("What's New: invalid accent hex '$hex' for variant $variant", it) }
            .getOrDefault(FALLBACK_ACCENT)
    }

    private fun pluginDescriptor() =
        com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId
                .getId("com.ayuislands.theme"),
        )

    companion object {
        private val LOG = logger<WhatsNewPanel>()

        /**
         * Pure ratio: panel-width / design-width, clamped to [MIN_SCALE]..[MAX_SCALE].
         * Extracted so tests can exercise the boundary math without instantiating
         * a Swing component. Returns [MIN_SCALE] when [scaledDesignWidth] is
         * non-positive (defensive — the caller already guards on panelWidth > 0).
         */
        @JvmStatic
        internal fun computeScale(
            panelWidth: Int,
            scaledDesignWidth: Int,
        ): Float {
            if (scaledDesignWidth <= 0) return MIN_SCALE
            val raw = panelWidth.toFloat() / scaledDesignWidth.toFloat()
            return raw.coerceIn(MIN_SCALE, MAX_SCALE)
        }

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

        // Responsive-scale bounds applied on every IDE-window resize. Reference
        // design width (900 logical px) picks a "medium-column reading width"
        // target: if the tab is wider, content still reads cleanly (scale=1.0
        // capped); narrower, content scales down to MIN_SCALE so the hero +
        // slide cards stay visible rather than overflowing.
        private const val DESIGN_WIDTH = 900
        private const val MIN_SCALE = 0.6f
        private const val MAX_SCALE = 1.0f

        // RGB channels for the secondary button tint. Fully saturated black on
        // light mode, fully bright white on dark mode. Alpha = 24/255 (~9%) for
        // a subtle hairline ghost fill that doesn't compete with the accent CTA.
        private const val RGB_MIN = 0
        private const val RGB_MAX = 255
        private const val SECONDARY_TINT_ALPHA = 24

        // Hex constant for the Ayu Mirage gold accent — used as the last-resort
        // fallback when the plugin's current accent color fails to decode.
        private const val FALLBACK_ACCENT_RGB = 0xFFCC66

        // Per-slide title color palette cycling through lavender → gold → cyan.
        // Matches the accent spectrum the plugin ships (these three are the
        // canonical "secondary accent" tones used across the v2.5.0 overrides
        // feature). Rendering slide titles in the same sequence keeps the
        // column from reading as a uniform block and nudges the user to see
        // each slide as a distinct capability.
        //
        // Each entry ships BOTH a light-mode and a dark-mode variant: the
        // dark hex (e.g. C3A6FF for lavender) has great contrast on dark
        // backgrounds but drops to ~2:1 on white (fails WCAG AA). The light
        // variant is a deeper, more saturated form of the same hue so the
        // slide reads as the same color family but stays legible on the
        // Ayu Islands Light theme.
        private const val LAVENDER_LIGHT = 0x6F3EDC
        private const val LAVENDER_DARK = 0xC3A6FF
        private const val GOLD_LIGHT = 0xB8860B
        private const val GOLD_DARK = 0xFFCC66
        private const val CYAN_LIGHT = 0x0E7FB6
        private const val CYAN_DARK = 0x5CCFE6
        private val TITLE_PALETTE: List<JBColor> =
            listOf(
                JBColor(Color(LAVENDER_LIGHT), Color(LAVENDER_DARK)),
                JBColor(Color(GOLD_LIGHT), Color(GOLD_DARK)),
                JBColor(Color(CYAN_LIGHT), Color(CYAN_DARK)),
            )

        private val SECONDARY_BUTTON_TINT: JBColor =
            JBColor(
                Color(RGB_MIN, RGB_MIN, RGB_MIN, SECONDARY_TINT_ALPHA),
                Color(RGB_MAX, RGB_MAX, RGB_MAX, SECONDARY_TINT_ALPHA),
            )

        private val FALLBACK_ACCENT: JBColor =
            JBColor(Color(FALLBACK_ACCENT_RGB), Color(FALLBACK_ACCENT_RGB))
    }
}
