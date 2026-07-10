package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.Row
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JPanel

/** A settings surface introduced in the current release cycle. */
internal data class SettingsBadgeAnchor(
    val id: String,
    val title: String,
    val tabTitle: String,
)

/**
 * Wayfinding for newly shipped settings: a header index on the root settings
 * page, an accent dot on tabs that contain pending anchors, and small "New"
 * pills on the anchor rows themselves.
 *
 * Decay model — seeing is acknowledging: selecting a tab acknowledges every
 * anchor on it (the dot clears live; pills and group-title suffixes stay for
 * the rest of the dialog session and are absent on the next open). A
 * `BADGE_LIFETIME_MS` cap auto-acknowledges everything so no badge lives
 * forever, and fresh installs are seeded fully acknowledged — a brand-new
 * user sees zero badges (onboarding and What's New own first-run).
 *
 * The registry is the only place newness is declared; ids reuse
 * `docs/features.yml` feature ids verbatim. Curate 3-5 anchors per release —
 * headline settings only — and delete entries older than about two minor
 * releases; `pruneStaleIds` keeps the persisted set bounded by the registry.
 */
internal object SettingsBadges {
    internal const val BADGE_LIFETIME_MS: Long = 45L * 24 * 60 * 60 * 1000

    val registry: List<SettingsBadgeAnchor> =
        listOf(
            SettingsBadgeAnchor(
                id = "chrome-tint-external-themes",
                title = "Chrome tint on external themes",
                tabTitle = "Plugins",
            ),
            SettingsBadgeAnchor(
                id = "glow-placement",
                title = "Glow placement",
                tabTitle = "Glow",
            ),
            SettingsBadgeAnchor(
                id = "accent-from-project-icon",
                title = "Accent from project icon",
                tabTitle = "Accent",
            ),
        )

    fun pendingAnchors(state: AyuIslandsState): List<SettingsBadgeAnchor> {
        // Defensive fresh-install check: UpdateNotifier seeds the set on first
        // run, but Settings may open before the startup activity gets there.
        if (state.lastSeenVersion == null) return emptyList()
        return registry.filter { it.id !in state.acknowledgedSettingsBadges }
    }

    fun isPending(
        state: AyuIslandsState,
        anchorId: String,
    ): Boolean = pendingAnchors(state).any { it.id == anchorId }

    /** Seeing is acknowledging: a visited tab retires every anchor on it. */
    fun acknowledgeTab(
        state: AyuIslandsState,
        tabTitle: String,
    ) {
        for (anchor in registry) {
            if (anchor.tabTitle == tabTitle) state.acknowledgedSettingsBadges.add(anchor.id)
        }
    }

    /** Fresh install: everything is new to this user, so nothing is badged. */
    fun seedAllAcknowledged(state: AyuIslandsState) {
        for (anchor in registry) state.acknowledgedSettingsBadges.add(anchor.id)
    }

    /** The registry bounds the persisted set — drop ids of removed entries. */
    fun pruneStaleIds(state: AyuIslandsState) {
        val known = registry.map { it.id }.toSet()
        state.acknowledgedSettingsBadges.retainAll(known)
    }

    /** Called on update: (re)start the lifetime window while anchors are pending. */
    fun armExpiry(
        state: AyuIslandsState,
        nowMs: Long,
    ) {
        if (pendingAnchors(state).isNotEmpty()) {
            state.settingsBadgesExpireAtMs = nowMs + BADGE_LIFETIME_MS
        }
    }

    /** Called on startup: past the cap, everything pending auto-acknowledges. */
    fun expireIfDue(
        state: AyuIslandsState,
        nowMs: Long,
    ) {
        val expireAt = state.settingsBadgesExpireAtMs
        if (expireAt == 0L || nowMs < expireAt) return
        seedAllAcknowledged(state)
        state.settingsBadgesExpireAtMs = 0L
    }
}

/** Live handles the root settings page uses to render and retire badges. */
internal class SettingsBadgeController(
    val headerText: String,
    val headerVisible: AtomicBooleanProperty,
    val jumpToFirstPending: () -> Unit,
)

/**
 * Wires badge dots onto [tabs] and returns the header-row controller, or null
 * when nothing is pending (no header row, no dots, no listeners). Selecting a
 * tab acknowledges its anchors and refreshes every level live.
 */
internal fun installSettingsBadges(
    tabs: JBTabbedPane,
    tabTitles: List<String>,
    accent: Color,
): SettingsBadgeController? {
    val state = AyuIslandsSettings.getInstance().state
    val initialPending = SettingsBadges.pendingAnchors(state)
    if (initialPending.isEmpty()) return null

    val headerVisible = AtomicBooleanProperty(true)
    // JBTabbedPane installs its own tab components; capture them so removing
    // a dot restores the platform component (and its selected-tab styling)
    // instead of nulling it out.
    val defaultTabComponents = tabTitles.indices.map { tabs.getTabComponentAt(it) }

    fun refresh() {
        val pendingTabs = SettingsBadges.pendingAnchors(state).map { it.tabTitle }.toSet()
        for ((index, title) in tabTitles.withIndex()) {
            val default = defaultTabComponents[index]
            tabs.setTabComponentAt(
                index,
                if (title in pendingTabs) badgeTabComponent(default, title, accent) else default,
            )
        }
        headerVisible.set(pendingTabs.isNotEmpty())
    }

    fun acknowledgeSelected() {
        val index = tabs.selectedIndex
        if (index in tabTitles.indices) {
            SettingsBadges.acknowledgeTab(state, tabTitles[index])
            refresh()
        }
    }

    refresh()
    // The initially selected tab is on screen right now — that counts as seen.
    acknowledgeSelected()
    tabs.addChangeListener { acknowledgeSelected() }

    return SettingsBadgeController(
        headerText = "New in this release: " + initialPending.joinToString(", ") { it.title },
        headerVisible = headerVisible,
        jumpToFirstPending = {
            val target = SettingsBadges.pendingAnchors(state).firstOrNull()?.tabTitle
            val index = target?.let(tabTitles::indexOf) ?: -1
            if (index >= 0) tabs.selectedIndex = index
        },
    )
}

/** Adds a small "New" pill after the row content while [anchorId] is pending. */
internal fun Row.newFeatureBadge(anchorId: String) {
    val state = AyuIslandsSettings.getInstance().state
    if (!SettingsBadges.isPending(state, anchorId)) return
    label("New").applyToComponent {
        font = JBUI.Fonts.smallFont()
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        accessibleContext.accessibleDescription = "New in this release"
    }
}

private fun badgeTabComponent(
    defaultComponent: Component?,
    title: String,
    accent: Color,
): JComponent {
    val panel = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(BADGE_GAP), 0))
    panel.isOpaque = false
    // Reuse the platform's own tab label when it exists — it carries the
    // selected/unselected foreground styling a hand-made label would lose.
    panel.add(defaultComponent ?: JBLabel(title).apply { isOpaque = false })
    panel.add(BadgeDot(accent))
    panel.accessibleContext.accessibleName = "$title, contains new settings"
    return panel
}

private const val BADGE_GAP = 4
private const val BADGE_DOT_SIZE = 6

private class BadgeDot(
    private val color: Color,
) : JComponent() {
    init {
        val size = JBUI.size(BADGE_DOT_SIZE, BADGE_DOT_SIZE)
        preferredSize = size
        minimumSize = size
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(
                0,
                (height - JBUI.scale(BADGE_DOT_SIZE)) / 2,
                JBUI.scale(BADGE_DOT_SIZE),
                JBUI.scale(BADGE_DOT_SIZE),
            )
        } finally {
            g2.dispose()
        }
    }
}
