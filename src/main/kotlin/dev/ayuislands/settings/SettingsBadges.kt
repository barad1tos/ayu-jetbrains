package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.Row
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.Border
import javax.swing.border.CompoundBorder

/**
 * A settings surface introduced in the current release cycle.
 *
 * [collapsibleGroupTitle] names the collapsible group hiding the anchor, or
 * null when the anchor is visible as soon as its tab opens. Grouped anchors
 * get an accent dot on the group title and retire on expansion instead of on
 * tab visit — a visit never reveals what a collapsed spoiler hides.
 */
internal data class SettingsBadgeAnchor(
    val id: String,
    val title: String,
    val tabTitle: String,
    val collapsibleGroupTitle: String? = null,
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
                collapsibleGroupTitle = "Targets",
            ),
            SettingsBadgeAnchor(
                id = "accent-from-project-icon",
                title = "Accent from project icon",
                tabTitle = "Accent",
                collapsibleGroupTitle = "Overrides",
            ),
        )

    // Session-scoped wiring, refreshed on every Settings build: panels report
    // whether their collapsible group is currently expanded, and the installed
    // badge view registers one refresh hook so panel-side acknowledgements
    // clear tab dots and group dots live. Rebuilding Settings overwrites both;
    // stale entries from a closed dialog are harmless no-ops.
    private val groupExpanded = mutableMapOf<String, () -> Boolean>()
    internal var onBadgesChanged: (() -> Unit)? = null

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

    /**
     * Seeing is acknowledging — for what a visit can actually show: anchors
     * behind a collapsed collapsible group survive the visit and retire via
     * [acknowledgeAnchor] when the group expands.
     */
    fun acknowledgeTab(
        state: AyuIslandsState,
        tabTitle: String,
    ) {
        for (anchor in registry) {
            if (anchor.tabTitle != tabTitle) continue
            if (isVisibleOnTabVisit(anchor)) state.acknowledgedSettingsBadges.add(anchor.id)
        }
    }

    /** Direct retirement (group expanded, deep link followed) with a live badge refresh. */
    fun acknowledgeAnchor(
        state: AyuIslandsState,
        anchorId: String,
    ) {
        state.acknowledgedSettingsBadges.add(anchorId)
        onBadgesChanged?.invoke()
    }

    /** Panels report their collapsible group's live expanded state per anchor. */
    fun registerGroupExpanded(
        anchorId: String,
        isExpanded: () -> Boolean,
    ) {
        groupExpanded[anchorId] = isExpanded
    }

    private fun isVisibleOnTabVisit(anchor: SettingsBadgeAnchor): Boolean =
        anchor.collapsibleGroupTitle == null || groupExpanded[anchor.id]?.invoke() == true

    @TestOnly
    fun resetSessionWiring() {
        groupExpanded.clear()
        onBadgesChanged = null
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
 * Wires badge dots onto [tabs] and collapsible group titles, and returns the
 * header-row controller, or null when nothing is pending (no header row, no
 * dots, no listeners). Selecting a tab acknowledges its visible anchors and
 * refreshes every level live; grouped anchors refresh through
 * [SettingsBadges.onBadgesChanged] when their spoiler expands.
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
        refreshGroupTitleDots(tabs, tabTitles, accent, state)
        headerVisible.set(pendingTabs.isNotEmpty())
    }

    fun acknowledgeSelected() {
        val index = tabs.selectedIndex
        if (index in tabTitles.indices) {
            SettingsBadges.acknowledgeTab(state, tabTitles[index])
            refresh()
        }
    }

    SettingsBadges.onBadgesChanged = { refresh() }
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

/**
 * Marks this collapsible group as the home of a new-settings anchor: reports
 * live expanded state for tab-visit acknowledgement and retires the anchor
 * the moment the user expands the spoiler.
 */
internal fun CollapsibleRow.bindNewSettingBadge(anchorId: String) {
    SettingsBadges.registerGroupExpanded(anchorId) { expanded }
    addExpandedListener { nowExpanded ->
        if (nowExpanded) {
            SettingsBadges.acknowledgeAnchor(AyuIslandsSettings.getInstance().state, anchorId)
        }
    }
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

/**
 * Puts an accent dot after the title of every collapsible group hiding a
 * pending anchor, and restores the plain title once acknowledged.
 *
 * The dot paints from a border decoration on the separator label: the label's
 * icon slot already carries the expand/collapse chevron, and a border keeps
 * the dot at the exact tab-dot size, vertically centered on the title text.
 */
private fun refreshGroupTitleDots(
    tabs: JBTabbedPane,
    tabTitles: List<String>,
    accent: Color,
    state: AyuIslandsState,
) {
    for (anchor in SettingsBadges.registry) {
        refreshGroupTitleDot(anchor, tabs, tabTitles, accent, state)
    }
}

private const val DOT_MARKER = "ayu.newSettingsDotMarker"
private const val DOT_ORIGINAL_BORDER = "ayu.newSettingsDotOriginalBorder"

private fun refreshGroupTitleDot(
    anchor: SettingsBadgeAnchor,
    tabs: JBTabbedPane,
    tabTitles: List<String>,
    accent: Color,
    state: AyuIslandsState,
) {
    val groupTitle = anchor.collapsibleGroupTitle ?: return
    val tabIndex = tabTitles.indexOf(anchor.tabTitle)
    if (tabIndex < 0 || tabIndex >= tabs.tabCount) return
    val root = tabs.getComponentAt(tabIndex) as? Container ?: return
    val label = findTitledSeparator(root, groupTitle)?.label ?: return

    val pending = SettingsBadges.isPending(state, anchor.id)
    val marked = label.getClientProperty(DOT_MARKER) == true
    if (pending && !marked) {
        label.putClientProperty(DOT_ORIGINAL_BORDER, label.border)
        label.putClientProperty(DOT_MARKER, true)
        label.border = CompoundBorder(label.border, TitleDotBorder(accent))
        label.accessibleContext.accessibleDescription = "Contains new settings"
    } else if (!pending && marked) {
        label.border = label.getClientProperty(DOT_ORIGINAL_BORDER) as? Border
        label.putClientProperty(DOT_MARKER, null)
        label.putClientProperty(DOT_ORIGINAL_BORDER, null)
        label.accessibleContext.accessibleDescription = null
    }
}

/** Trailing accent dot at [BADGE_DOT_SIZE], centered on the component's text height. */
private class TitleDotBorder(
    private val color: Color,
) : Border {
    override fun paintBorder(
        c: Component,
        g: Graphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            val size = JBUI.scale(BADGE_DOT_SIZE)
            g2.fillOval(x + width - size, y + (height - size) / 2, size, size)
        } finally {
            g2.dispose()
        }
    }

    override fun getBorderInsets(c: Component): Insets = JBUI.insetsRight(BADGE_GAP + BADGE_DOT_SIZE)

    override fun isBorderOpaque(): Boolean = false
}

private const val GROUP_DOT_SCAN_CAP = 800

private fun findTitledSeparator(
    root: Container,
    title: String,
): TitledSeparator? {
    val queue = ArrayDeque<Component>()
    queue.add(root)
    var visited = 0
    while (queue.isNotEmpty() && visited < GROUP_DOT_SCAN_CAP) {
        val current = queue.removeFirst()
        visited++
        if (current is TitledSeparator && current.text == title) return current
        if (current is Container) queue.addAll(current.components)
    }
    return null
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
