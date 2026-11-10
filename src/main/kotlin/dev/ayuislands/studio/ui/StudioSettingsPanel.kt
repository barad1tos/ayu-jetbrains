package dev.ayuislands.studio.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.toolbar.popup.AccentStripe
import dev.ayuislands.onboarding.OnboardingUrls
import dev.ayuislands.settings.SettingsBadgeController
import dev.ayuislands.settings.SettingsBadgeState
import dev.ayuislands.settings.SettingsBadges
import dev.ayuislands.settings.installSettingsBadges
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.model.StudioSettingsView
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingConstants

/** An already-created native section; its preview binding consumes explicit local view inputs. */
internal class StudioSectionContent(
    val section: StudioSettingsSection,
    val component: JComponent,
    val preview: (PreviewMode) -> Unit,
)

/** Composes existing section views without owning their controls, disclosures, persistence, or host lifecycle. */
internal class StudioSettingsPanel(
    sections: List<StudioSectionContent>,
    selectProfile: (StudioProfileChoice) -> Unit,
    selectPreview: (PreviewMode) -> Unit,
    private val selectSection: (StudioSettingsSection) -> Unit,
    profileActions: (() -> Unit)? = null,
) : JPanel() {
    private val content = orderedContent(sections)
    private var badgeController: SettingsBadgeController? = null
    private val badgeReview =
        ActionLink("Review new settings") {
            badgeController?.jumpToFirstPending?.invoke()
        }.apply { isVisible = false }
    private val profiles = StudioProfileControls(selectProfile, selectPreview, profileActions)
    private val tabs = JBTabbedPane().apply { minimumSize = Dimension(0, 0) }
    private val status = JLabel()
    private val sharedCaptions = mutableMapOf<StudioSettingsSection, JLabel>()
    private var rendered: StudioSettingsView? = null
    private var isRendering = false
    private val stripe = AccentStripe { checkNotNull(rendered).acceptedAccentHex }.apply { isVisible = false }

    init {
        val spacing = StudioUiTokens.spacing()
        layout = BorderLayout(0, spacing.groupGap)
        border = BorderFactory.createEmptyBorder(spacing.groupGap, spacing.groupGap, spacing.groupGap, spacing.groupGap)
        val identity = brandHeader(spacing)
        val header =
            JPanel(BorderLayout(0, spacing.fieldGap)).apply {
                add(stripe, BorderLayout.NORTH)
                add(identity, BorderLayout.CENTER)
                add(profiles, BorderLayout.SOUTH)
            }
        for (entry in content) {
            val page = JPanel(BorderLayout(0, spacing.groupGap))
            if (entry.section.isShared) {
                val caption = JLabel("Shared across profiles").apply { isVisible = false }
                sharedCaptions[entry.section] = caption
                page.add(caption, BorderLayout.NORTH)
            }
            page.add(entry.component, BorderLayout.CENTER)
            tabs.addTab(entry.section.title, scrollPage(page))
        }
        tabs.addChangeListener {
            val section = content[tabs.selectedIndex].section
            showSharedCaption(section)
            if (!isRendering) selectSection(section)
        }
        add(
            JPanel(BorderLayout(0, spacing.fieldGap)).apply {
                add(header, BorderLayout.CENTER)
                add(badgeReview, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        add(tabs, BorderLayout.CENTER)
        add(communityFooter(status, spacing), BorderLayout.SOUTH)
    }

    fun render(view: StudioSettingsView) {
        // Validate through the same native color path as AccentStripe before changing the displayed view.
        ColorUtil.fromHex(view.acceptedAccentHex)
        val changedPreview = rendered?.profiles?.preview != view.profiles.preview
        isRendering = true
        try {
            rendered = view
            profiles.render(view.profiles)
            tabs.selectedIndex = view.section.ordinal
            showSharedCaption(view.section)
            status.text = statusText(view)
            if (changedPreview) content.forEach { it.preview(view.profiles.preview) }
            stripe.isVisible = true
            stripe.repaint()
        } finally {
            isRendering = false
        }
    }

    /** Reads only accepted metadata; publication triggers the existing host render path. */
    fun refreshBadges(
        readState: () -> SettingsBadgeState,
        acknowledge: (List<String>) -> Unit,
    ) {
        profiles.renderBadges(SettingsBadges.pendingAnchors(readState()).map { it.id }.toSet())
        val current =
            badgeController ?: installSettingsBadges(
                tabs,
                content.map { it.section.title },
                ColorUtil.fromHex(checkNotNull(rendered).acceptedAccentHex),
                readState,
                acknowledge = { ids, _ -> if (ids.isNotEmpty()) acknowledge(ids) },
            ).also { badgeController = it }
        current?.refresh?.invoke()
        badgeReview.toolTipText = current?.headerText
        badgeReview.accessibleContext.accessibleDescription = current?.headerText
        badgeReview.isVisible = current?.headerVisible?.get() == true
    }

    fun dispose() {
        badgeController?.dispose()
        badgeController = null
        badgeReview.isVisible = false
    }

    private fun showSharedCaption(section: StudioSettingsSection) {
        sharedCaptions.forEach { (id, caption) -> caption.isVisible = id == section }
    }
}

private fun scrollPage(content: JComponent): JScrollPane =
    JBScrollPane(SettingsPage(content)).apply {
        border = JBUI.Borders.empty()
        viewportBorder = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        minimumSize = Dimension(0, 0)
    }

private class SettingsPage(
    content: JComponent,
) : JPanel(BorderLayout()),
    Scrollable {
    init {
        isOpaque = false
        minimumSize = Dimension(0, 0)
        add(content, BorderLayout.CENTER)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = Dimension(0, preferredSize.height)

    override fun getScrollableUnitIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = JBUI.scale(SCROLL_UNIT_INCREMENT)

    override fun getScrollableBlockIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int =
        if (orientation == SwingConstants.VERTICAL) {
            (visibleRect.height - JBUI.scale(SCROLL_UNIT_INCREMENT)).coerceAtLeast(JBUI.scale(SCROLL_UNIT_INCREMENT))
        } else {
            (visibleRect.width - JBUI.scale(SCROLL_UNIT_INCREMENT)).coerceAtLeast(JBUI.scale(SCROLL_UNIT_INCREMENT))
        }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    private companion object {
        const val SCROLL_UNIT_INCREMENT = 16
    }
}

private fun orderedContent(sections: List<StudioSectionContent>): List<StudioSectionContent> {
    require(
        sections.size == StudioSettingsSection.entries.size &&
            sections.map { it.section }.toSet() == StudioSettingsSection.entries.toSet(),
    ) {
        "Studio settings require every section exactly once"
    }
    return StudioSettingsSection.entries.map { section -> sections.single { it.section == section } }
}

private fun statusText(view: StudioSettingsView): String =
    when (view.authority) {
        AuthorityStatus.READ_ONLY -> "Read-only settings"
        AuthorityStatus.RECOVERY -> "Recovery required"
        AuthorityStatus.READY -> if (view.profiles.hasPendingChanges) "Pending changes" else "No pending changes"
    }

private fun brandHeader(spacing: StudioSpacing): JComponent {
    val title =
        JLabel("Ayu Islands").apply {
            font = font.deriveFont(Font.BOLD)
            icon = settingsLogo()
            iconTextGap = spacing.fieldGap
        }
    val version = AyuPlugin.findLoadedPlugin(AyuPlugin.ID)?.version ?: "unknown"
    return JPanel(BorderLayout(spacing.fieldGap, 0)).apply {
        add(title, BorderLayout.CENTER)
        add(JLabel("v$version").apply { font = JBUI.Fonts.smallFont() }, BorderLayout.EAST)
    }
}

private fun communityFooter(
    status: JLabel,
    spacing: StudioSpacing,
): JComponent {
    val links =
        JPanel(FlowLayout(FlowLayout.LEADING, spacing.fieldGap, 0)).apply {
            add(ActionLink("Share Your Setup") { BrowserUtil.browse(OnboardingUrls.DISCUSSIONS_SHOW_SETUP) })
            add(ActionLink("Request a Feature") { BrowserUtil.browse(OnboardingUrls.DISCUSSIONS_FEATURE_REQUESTS) })
        }
    return JPanel(BorderLayout(0, spacing.fieldGap)).apply {
        add(status, BorderLayout.NORTH)
        add(links, BorderLayout.SOUTH)
    }
}

private fun settingsLogo(): Icon =
    IconUtil.scale(
        IconLoader.getIcon("/META-INF/pluginIcon.svg", StudioSettingsPanel::class.java),
        null,
        SETTINGS_LOGO_SCALE,
    )

// The bundled icon is 40 logical pixels; the header keeps it at 28.
private const val SETTINGS_LOGO_SCALE = 0.7f
