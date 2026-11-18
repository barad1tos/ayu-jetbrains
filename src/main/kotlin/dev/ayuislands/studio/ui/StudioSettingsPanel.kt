package dev.ayuislands.studio.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.toolbar.popup.AccentStripe
import dev.ayuislands.settings.createScrollableTabContent
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.model.StudioSettingsView
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** An already-created native section; its preview binding consumes explicit local view inputs. */
internal class StudioSectionContent(
    val section: StudioSettingsSection,
    val component: JComponent,
    val preview: (PreviewMode) -> Unit,
)

internal enum class StudioCommunityLink { SHARE_SETUP, REQUEST_FEATURE }

/** Keeps section and disclosure instances stable across renders. Section values and effects are host-owned. */
internal class StudioSettingsPanel(
    sections: List<StudioSectionContent>,
    version: String,
    selectProfile: (StudioProfileChoice) -> Unit,
    selectPreview: (PreviewMode) -> Unit,
    selectSection: (StudioSettingsSection) -> Unit,
    openCommunityLink: (StudioCommunityLink) -> Unit,
    profileActions: (() -> Unit)? = null,
) : JPanel() {
    private val content = orderedContent(sections)
    private val profiles = StudioProfileControls(selectProfile, selectPreview, profileActions)
    private val tabs = JBTabbedPane().apply { minimumSize = Dimension(0, 0) }
    private val status = JLabel()
    private val sharedCaptions = mutableMapOf<StudioSettingsSection, JLabel>()
    private var acceptedAccent: AccentHex? = null
    private var appliedPreview: PreviewMode? = null
    private var isRendering = false
    private val stripe = AccentStripe { checkNotNull(acceptedAccent).value }.apply { isVisible = false }

    init {
        val spacing = StudioUiTokens.spacing()
        layout = BorderLayout(0, spacing.groupGap)
        border = BorderFactory.createEmptyBorder(spacing.groupGap, spacing.groupGap, spacing.groupGap, spacing.groupGap)
        val identity = brandHeader(version, spacing)
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
            tabs.addTab(entry.section.title, createScrollableTabContent(page))
        }
        tabs.addChangeListener {
            val section = content[tabs.selectedIndex].section
            showSharedCaption(section)
            if (!isRendering && acceptedAccent != null) selectSection(section)
        }
        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        add(communityFooter(status, spacing, openCommunityLink), BorderLayout.SOUTH)
    }

    fun render(view: StudioSettingsView) {
        isRendering = true
        try {
            val mode = view.profiles.preview
            if (appliedPreview != mode) {
                // A partial refresh invalidates the prior mode as well as the attempted one.
                appliedPreview = null
                content.forEach { it.preview(mode) }
                appliedPreview = mode
            }
            profiles.render(view.profiles)
            profiles.renderBadges(view.pendingBadgeIds.toSet())
            tabs.selectedIndex = view.section.ordinal
            showSharedCaption(view.section)
            status.text = statusText(view)
            acceptedAccent = view.acceptedAccent
            stripe.isVisible = true
            stripe.repaint()
        } finally {
            isRendering = false
        }
    }

    private fun showSharedCaption(section: StudioSettingsSection) {
        sharedCaptions.forEach { (id, caption) -> caption.isVisible = id == section }
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

private fun brandHeader(
    version: String,
    spacing: StudioSpacing,
): JComponent {
    val title =
        JLabel("Ayu Islands").apply {
            font = font.deriveFont(Font.BOLD)
            icon = settingsLogo()
            iconTextGap = spacing.fieldGap
        }
    return JPanel(BorderLayout(spacing.fieldGap, 0)).apply {
        add(title, BorderLayout.CENTER)
        add(JLabel("v$version").apply { font = JBUI.Fonts.smallFont() }, BorderLayout.EAST)
    }
}

private fun communityFooter(
    status: JLabel,
    spacing: StudioSpacing,
    openCommunityLink: (StudioCommunityLink) -> Unit,
): JComponent {
    val links =
        JPanel(FlowLayout(FlowLayout.LEADING, spacing.fieldGap, 0)).apply {
            add(ActionLink("Share Your Setup") { openCommunityLink(StudioCommunityLink.SHARE_SETUP) })
            add(ActionLink("Request a Feature") { openCommunityLink(StudioCommunityLink.REQUEST_FEATURE) })
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
