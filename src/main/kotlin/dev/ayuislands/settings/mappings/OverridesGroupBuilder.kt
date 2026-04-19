package dev.ayuislands.settings.mappings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.table.JBTable
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.LanguageDetectionRules
import dev.ayuislands.accent.ProjectLanguageDetectionListener
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel

/**
 * Builds the "Overrides" group inside the Accent settings tab. Hosts a segmented
 * toggle between Projects and Languages, each backed by its own [JBTable] and
 * [ToolbarDecorator] with add / pin-current / edit-color / remove actions.
 *
 * All mutations happen on an in-memory pending model; callers use [isModified],
 * [apply], and [reset] to participate in the usual settings lifecycle. [addPendingChangeListener]
 * lets observers (the reactive "Currently active: ..." comment) refresh on every edit.
 */
class OverridesGroupBuilder {
    private val projectModel = ProjectMappingsTableModel()
    private val languageModel = LanguageMappingsTableModel()
    private val projectTable: JBTable = AutoSizingTable(projectModel)
    private val languageTable: JBTable = AutoSizingTable(languageModel)

    private var storedProjects: List<ProjectMapping> = emptyList()
    private var storedLanguages: List<LanguageMapping> = emptyList()
    private val listeners: MutableList<Runnable> = mutableListOf()

    private val cardPanel = JPanel(CardLayout())
    private var parentProject: Project? = null

    /**
     * Captured [JPanel] backing the detected-language proportions status row.
     * Children are cleared and rebuilt on every [reset] and every pending-change
     * event via [populateProportionsPanel]. Each child is a [JBLabel] carrying
     * the language-specific icon from
     * [LanguageDetectionRules.iconForLanguageId] (or the info icon for the
     * polyglot state). Null before [buildGroup] has populated it.
     */
    private var proportionsPanel: JPanel? = null

    /**
     * Live `MessageBusConnection` subscribing the proportions panel to
     * [ProjectLanguageDetectionListener.TOPIC]. Stored so [dispose] can tear
     * down the subscription when the Settings panel closes — otherwise every
     * Settings open on the same project accumulates another live subscriber
     * that survives until project close, and every scan completion walks the
     * accumulated list to hit a `panel.isDisplayable` no-op on every stale
     * builder. Null before [buildGroup] runs and after [dispose] has
     * disconnected; [buildGroup] disconnects any prior connection on re-entry
     * so a builder rebuilt in place (variant swap while Settings stays open)
     * doesn't double-subscribe either.
     */
    private var detectionConnection: MessageBusConnection? = null

    /**
     * License-gate snapshot for the rescan affordance, captured once at
     * [buildGroup] time from `LicenseChecker.isLicensedOrGrace()`. Kept as a
     * dedicated boolean (rather than folding into the `parentProject`
     * nullability) so the two "Rescan hidden" reasons stay distinguishable
     * at debug time:
     *   - `parentProject == null` → Settings opened without a focused project.
     *   - `parentProject != null && !rescanLicensed` → unlicensed user.
     *
     * Rescan is a Pro feature by project policy (all new features premium
     * by default). Reading the license once in [buildGroup] keeps the hot
     * render path ([renderProportionsInto]) out of a repeated
     * `LicenseChecker.isLicensedOrGrace()` call and avoids a cyclomatic-
     * budget bump from an inline `&&`.
     *
     * [buildRescanAffordanceLabel]'s `mouseClicked` defence-in-depth
     * re-reads `LicenseChecker.isLicensedOrGrace()` live on click so a
     * license that expires while Settings is open can't still fire a rescan
     * from a stale panel.
     */
    private var rescanLicensed: Boolean = false

    /**
     * Derived rescan-eligibility: non-null iff a focused project is
     * present AND the license snapshot permits the Pro affordance. Kept
     * as a computed property (property getters don't count toward
     * detekt's `TooManyFunctions` budget) so the single read in
     * [renderProportionsInto] reduces to one `?.let` branch. The two
     * reasons for null remain debuggable independently via
     * [parentProject] and [rescanLicensed].
     */
    private val rescanEligibleProject: Project?
        get() = parentProject?.takeIf { rescanLicensed }

    init {
        for (table in listOf(projectTable, languageTable)) {
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            table.rowHeight = TABLE_ROW_HEIGHT
            table.setShowGrid(false)
        }
        projectTable.getColumnModel().getColumn(ProjectMappingsTableModel.COLUMN_COLOR).apply {
            cellRenderer = RoundedSwatchRenderer()
        }
        projectTable.getColumnModel().getColumn(ProjectMappingsTableModel.COLUMN_PROJECT).apply {
            cellRenderer = DimOrphanRenderer { row -> projectModel.isOrphan(row) }
        }
        projectTable.getColumnModel().getColumn(ProjectMappingsTableModel.COLUMN_PATH).apply {
            cellRenderer = DimOrphanRenderer { row -> projectModel.isOrphan(row) }
        }
        languageTable.getColumnModel().getColumn(LanguageMappingsTableModel.COLUMN_COLOR).apply {
            cellRenderer = RoundedSwatchRenderer()
        }
    }

    /**
     * Tear down the detection-Topic subscription. Called from
     * [dev.ayuislands.settings.AyuIslandsConfigurable.disposeUIResources]
     * via the `dispose()` override on
     * [dev.ayuislands.settings.AyuIslandsAccentPanel] (through the
     * `AyuIslandsSettingsPanel.dispose()` interface method). Without this
     * call the live subscriber count grows by one per Settings open. Safe
     * to call multiple times; no-op when the builder was never wired up.
     */
    fun dispose() {
        // Wrap the disconnect in runCatchingPreservingCancellation to
        // match the sibling defence in RescanLanguageAction: the platform
        // has been observed to throw `AlreadyDisposedException` from
        // `MessageBusConnection.disconnect()` during a plugin-unload
        // race. Without the wrap, a throw here propagates up through
        // `AyuIslandsConfigurable.disposeUIResources` and skips both the
        // remaining panel teardown and `super.disposeUIResources`, which
        // would leak the `BoundConfigurable` binding cleanup.
        runCatchingPreservingCancellation { detectionConnection?.disconnect() }
            .onFailure { exception ->
                LOG.debug("OverridesGroupBuilder detection disconnect failed", exception)
            }
        detectionConnection = null
    }

    fun buildGroup(
        panel: Panel,
        contextProject: Project?,
    ) {
        parentProject = contextProject
        // Defense-in-depth: StartupActivity warms the detector cache off-EDT on
        // project open, but Settings might be opened before that coroutine lands
        // (race window) or the cache might have been invalidated mid-session.
        // Kick a warmup now on the EDT path — the detector's own EDT bail-out
        // schedules a background scan and returns null immediately, so this is
        // zero-cost on EDT; the first paint may show the polyglot copy but the
        // next reset (any model edit or panel reopen) picks up the warm cache.
        contextProject?.let { ProjectLanguageDetector.dominant(it) }
        loadFromState()

        val licensed = LicenseChecker.isLicensedOrGrace()
        // Capture the license snapshot up front so `renderProportionsInto`
        // reads one boolean + one nullable without bumping its cyclomatic
        // count. See `rescanLicensed` KDoc for why license and project
        // eligibility live in separate fields.
        rescanLicensed = licensed

        val projectsRadio = JRadioButton("Projects", true)
        val languagesRadio = JRadioButton("Languages", false)
        val segmentedButtonGroup =
            ButtonGroup().apply {
                add(projectsRadio)
                add(languagesRadio)
            }
        projectsRadio.addActionListener { (cardPanel.layout as CardLayout).show(cardPanel, CARD_PROJECTS) }
        languagesRadio.addActionListener { (cardPanel.layout as CardLayout).show(cardPanel, CARD_LANGUAGES) }
        val segmentedBar =
            JPanel(FlowLayout(FlowLayout.LEADING, BAR_HORIZONTAL_GAP, BAR_VERTICAL_GAP)).apply {
                isOpaque = false
                add(projectsRadio)
                add(languagesRadio)
                // Retain strong reference so actions survive focus changes.
                putClientProperty("ayu.overrides.group", segmentedButtonGroup)
            }
        cardPanel.add(decorateTable(projectTable, projectActions(licensed)), CARD_PROJECTS)
        cardPanel.add(decorateTable(languageTable, languageActions(licensed)), CARD_LANGUAGES)
        // No fixed preferredSize: the AutoSizingTable drives height via
        // getPreferredScrollableViewportSize (row count × row height) and every column
        // auto-packs to the wider of header/content on every model change. AUTO_RESIZE_LAST_COLUMN
        // then lets the last column absorb any remaining width when the containing panel is wider
        // than the sum of packed widths, or shrink (via horizontal scroll) when narrower.

        val settings = AyuIslandsSettings.getInstance()
        val collapsible =
            panel.collapsibleGroup("Overrides") {
                row {
                    comment(
                        "Pin an accent color to a specific project or a programming language. " +
                            "Project overrides win over language overrides; both win over the global accent.",
                    )
                }
                row {
                    cell(segmentedBar)
                }
                row {
                    cell(cardPanel)
                        .resizableColumn()
                        .align(Align.FILL)
                }
                row {
                    val gap = JBUI.scale(PROPORTIONS_ENTRY_GAP_PX)
                    val statusPanel =
                        JPanel(FlowLayout(FlowLayout.LEFT, gap, 0)).apply {
                            isOpaque = false
                            border = null
                        }
                    proportionsPanel = statusPanel
                    populateProportionsPanel(statusPanel)
                    cell(statusPanel)
                }
                if (!licensed) {
                    row {
                        comment("Pro feature")
                    }
                }
            }
        collapsible.expanded = settings.state.overridesGroupExpanded
        collapsible.addExpandedListener { expanded ->
            settings.state.overridesGroupExpanded = expanded
        }
        // Two independent refresh channels share one repopulate helper:
        //  - Pending-change listener: Settings-local edits (add / edit / delete a row)
        //    fire `fireChanged()` synchronously on EDT, which re-reads the warm cache.
        //  - Detection Topic: async scan completions (startup warmup, `ModuleRootListener`
        //    content-root change, user-triggered rescan) fire
        //    `ProjectLanguageDetectionListener.scanCompleted` on EDT — the only signal
        //    the row has to exit a stale winner state without a settings edit.
        addPendingChangeListener { proportionsPanel?.let { populateProportionsPanel(it) } }
        // Subscription lifetime is tied to the Settings panel (disconnected by
        // [dispose] from the Configurable's disposeUIResources). Any prior
        // connection on this same builder is torn down first so a rebuild in
        // place can't double-subscribe. Wrapped in runCatchingPreservingCancellation
        // so a platform regression throwing from `disconnect()` (observed as
        // `AlreadyDisposedException` during plugin-unload races) doesn't
        // propagate out of `buildGroup` and break the Settings render path.
        // The project MessageBus is the ultimate safety net — if Settings is
        // orphaned without disposeUI firing, the connection still drops on
        // project close.
        runCatchingPreservingCancellation { detectionConnection?.disconnect() }
            .onFailure { exception ->
                LOG.debug("OverridesGroupBuilder re-entry disconnect failed", exception)
            }
        detectionConnection = null
        // The `panel.isDisplayable` guard inside invokeLater below covers
        // the window where Settings has been closed but dispose hasn't
        // fired yet — without it, `populateProportionsPanel` would paint
        // into a detached panel and waste EDT budget.
        contextProject?.let { project ->
            val connection = project.messageBus.connect()
            detectionConnection = connection
            connection.subscribe(
                ProjectLanguageDetectionListener.TOPIC,
                ProjectLanguageDetectionListener {
                    SwingUtilities.invokeLater {
                        val panel = proportionsPanel ?: return@invokeLater
                        if (!panel.isDisplayable) return@invokeLater
                        populateProportionsPanel(panel)
                    }
                },
            )
        }
    }

    // Settings panel lifecycle (isModified / apply / reset)

    fun isModified(): Boolean {
        val currentProjects = projectModel.snapshot().toFingerprint()
        val storedProjectsFingerprint = storedProjects.toFingerprint()
        if (currentProjects != storedProjectsFingerprint) return true

        val currentLanguages = languageModel.snapshot().toLanguageFingerprint()
        val storedLanguagesFingerprint = storedLanguages.toLanguageFingerprint()
        return currentLanguages != storedLanguagesFingerprint
    }

    fun apply() {
        val state = AccentMappingsSettingsAccess.stateFor()
        state.projectAccents.clear()
        state.projectDisplayNames.clear()
        for (row in projectModel.snapshot()) {
            state.projectAccents[row.canonicalPath] = row.hex
            state.projectDisplayNames[row.canonicalPath] = row.displayName
        }
        state.languageAccents.clear()
        for (row in languageModel.snapshot()) {
            state.languageAccents[row.languageId] = row.hex
        }

        // Re-apply the committed mapping set via resolver → applicator → swap-cache sync.
        // Keep the focus-swap cache consistent so the next WINDOW_ACTIVATED event evaluates
        // against the color actually showing on screen right now.
        //
        // Defense-in-depth: the resolver / applicator / swap-cache chain touches LafManager,
        // UIManager, and the project-swap service; a transient failure anywhere in that chain
        // must not short-circuit the stored-snapshot refresh or `fireChanged()` below, or the
        // settings UI would drift (persisted state saved, but `isModified()` keeps reporting
        // "modified" because `storedProjects/languages` stayed on the pre-apply snapshot).
        runCatchingPreservingCancellation {
            AyuVariant.detect()?.let { variant ->
                // Fall through to the OS-active cascade when the builder has no parentProject
                // bound yet — same helper every apply path ultimately converges on.
                val project = parentProject ?: AccentApplicator.resolveFocusedProject()
                val hex = AccentResolver.resolve(project, variant)
                AccentApplicator.apply(hex)
                ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
            }
        }.onFailure { exception ->
            LOG.warn("Re-apply after overrides commit failed; persisted state is saved, UI may need reopen", exception)
        }
        storedProjects = projectModel.snapshot().map { ProjectMapping(it.canonicalPath, it.displayName, it.hex) }
        storedLanguages = languageModel.snapshot().map { LanguageMapping(it.languageId, it.displayName, it.hex) }
        fireChanged()
    }

    fun reset() {
        projectModel.replaceAll(storedProjects.map { ProjectMapping(it.canonicalPath, it.displayName, it.hex) })
        languageModel.replaceAll(storedLanguages.map { LanguageMapping(it.languageId, it.displayName, it.hex) })
        proportionsPanel?.let { populateProportionsPanel(it) }
        fireChanged()
    }

    fun addPendingChangeListener(runnable: Runnable) {
        listeners += runnable
    }

    /**
     * Resolve the accent hex using the **pending** (not yet applied) overrides model.
     * Falls back to [fallbackGlobalHex] when no override matches.
     */
    fun resolvePending(
        project: Project?,
        fallbackGlobalHex: String,
    ): String {
        if (project != null && !project.isDefault && !project.isDisposed) {
            AccentResolver.projectKey(project)?.let { key ->
                projectModel.snapshot().firstOrNull { it.canonicalPath == key }?.let { return it.hex }
            }
            val languages = languageModel.snapshot()
            if (languages.isNotEmpty()) {
                ProjectLanguageDetector.dominant(project)?.let { languageId ->
                    // LanguageMapping.init enforces lowercase at construction; ProjectLanguageDetector
                    // returns lowercase ids. Case-sensitive equality is sufficient — plain `==`
                    // stays consistent with LanguageMappingsTableModel.containsLanguage.
                    languages.firstOrNull { it.languageId == languageId }?.let { return it.hex }
                }
            }
        }
        return fallbackGlobalHex
    }

    /**
     * Matching [AccentResolver.Source] for [project] under the **pending** overrides model.
     */
    fun sourcePending(project: Project?): AccentResolver.Source {
        if (project != null && !project.isDefault && !project.isDisposed) {
            val key = AccentResolver.projectKey(project)
            if (key != null && projectModel.snapshot().any { it.canonicalPath == key }) {
                return AccentResolver.Source.PROJECT_OVERRIDE
            }
            val languages = languageModel.snapshot()
            if (languages.isNotEmpty()) {
                val dominant = ProjectLanguageDetector.dominant(project)
                // See resolvePending: both sides are lowercase by invariant, no compensator needed.
                if (dominant != null && languages.any { it.languageId == dominant }) {
                    return AccentResolver.Source.LANGUAGE_OVERRIDE
                }
            }
        }
        return AccentResolver.Source.GLOBAL
    }

    // Internals: pending-model resolver + UI wiring helpers

    /**
     * Compute the current status-line text by reading from the detector's warm cache.
     *
     * Returns the fixed polyglot copy ([POLYGLOT_COPY]) when:
     *  - [parentProject] is null (Settings opened with no focused project)
     *  - [ProjectLanguageDetector.proportions] returns null (cold cache, polyglot
     *    no-winner verdict, or legacy-SDK fallback path — all leave weightsCache empty)
     *  - [LanguageDetectionRules.pickTopLanguagesForDisplay] produces a blank string
     *    (all-zero weights or markup-only below-threshold — same visual as polyglot)
     *
     * Otherwise returns [DETECTED_PREFIX] concatenated with the formatter output,
     * passed through [StringUtil.escapeXmlEntities] — the current icon-row
     * renderer uses plain-text `JBLabel`s that never parse HTML, so the escape
     * is not load-bearing for the production UI, but this helper survives as a
     * regression guard that locks the T-26-01 threat model (a third-party
     * language plugin registering a display name containing markup): the
     * escape keeps the text projection safe against a future render path that
     * switches back to an HTML-capable label.
     *
     * O(1) HashMap probe plus a bounded formatter pass; safe to call on EDT.
     *
     * Exposed as an `internal @TestOnly` seam so
     * `OverridesGroupBuilderProportionsTest` can assert every user-visible render
     * state without instantiating the Swing tree — the UI builder itself no
     * longer calls this helper (the icon-panel path lives in
     * [populateProportionsPanel]), so this method survives as a pure test
     * projection over the text-equivalent rendering.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun currentProportionsTextForTest(): String {
        val project = parentProject ?: return POLYGLOT_COPY
        val weights = ProjectLanguageDetector.proportions(project) ?: return POLYGLOT_COPY
        val rendered = LanguageDetectionRules.pickTopLanguagesForDisplay(weights)
        if (rendered.isBlank()) return POLYGLOT_COPY
        return DETECTED_PREFIX + StringUtil.escapeXmlEntities(rendered)
    }

    /**
     * Rebuild the status-panel children from the detector's warm cache. Clears
     * existing children first so stale entries from a previous project focus
     * don't leak. Called from the inline panel construction inside [buildGroup]
     * (initial paint), from [reset] (Settings re-opens or Cancel), and from the
     * pending-change listener registered in [buildGroup] (override add / edit /
     * delete path via [fireChanged]).
     *
     * Two render paths:
     *  - **Icon row** (normal): one [JBLabel] per [LanguageDetectionRules.DisplayEntry]
     *    with the IDE-platform icon from [LanguageDetectionRules.iconForLanguageId]
     *    (null for the "other" bucket — JBLabel without an icon renders as
     *    text-only, same font). Labels carry the raw display name + percentage.
     *    Because `JBLabel.text` is plain-text by default (NOT `<html>`-prefixed),
     *    any pathological language display name renders literally — no HTML
     *    interpretation, no need for escape-xml.
     *  - **Polyglot row** (weights cache cold OR `pickDisplayEntries` returned
     *    an empty list): one [JBLabel] with
     *    `AllIcons.General.Information` + [POLYGLOT_COPY]. Visually distinct
     *    from the icon row so the user instantly sees "no single dominant".
     */
    private fun populateProportionsPanel(panel: JPanel) {
        // Wrap the entire rebuild under runCatchingPreservingCancellation so a
        // third-party Language plugin throwing from `associatedFileType.icon`,
        // a `ConcurrentModificationException` on `Language.getRegisteredLanguages()`
        // during a mid-render plugin unload, or any other transient platform
        // failure cannot propagate out of this EDT callback — the UI DSL builder,
        // the pending-change listener chain (`listeners.forEach { it.run() }`),
        // and `reset()` all call this helper and a bubbled exception would break
        // the entire Overrides group, not just the proportions row.
        //
        // Atomic-swap strategy: render into a detached staging `JPanel` first, and
        // only touch the live `panel` once rendering fully succeeded. A prior
        // version cleared `panel` BEFORE rendering, so a mid-render throw left the
        // user looking at a blank row while the log claimed "previous state" —
        // misleading triage. Now the live panel is either fully replaced or left
        // exactly as the user last saw it.
        // Construct a FRESH FlowLayout for the staging panel instead of aliasing
        // `panel.layout`. `FlowLayout` is stateless today, but sharing a layout
        // instance across two containers is undefined behavior if a future edit
        // swaps the live panel's layout to one that caches per-child constraints
        // (GridBagLayout, MigLayout). Reconstruct from the same scaled gap used
        // in `buildGroup` so the staging pack width matches the live pack width.
        val gap = JBUI.scale(PROPORTIONS_ENTRY_GAP_PX)
        val staging =
            JPanel(FlowLayout(FlowLayout.LEFT, gap, 0)).apply {
                isOpaque = panel.isOpaque
                border = panel.border
            }
        val outcome = runCatchingPreservingCancellation { renderProportionsInto(staging) }
        outcome.onFailure { exception ->
            LOG.warn("Proportions panel repopulate failed; leaving previous state", exception)
            return
        }
        panel.removeAll()
        staging.components.toList().forEach { child ->
            staging.remove(child)
            panel.add(child)
        }
        panel.revalidate()
        panel.repaint()
    }

    /**
     * Paint the proportions content into [panel] — the polyglot fallback label
     * when there are no entries, otherwise the leading prefix plus one
     * icon+percent label per entry with separators between them.
     *
     * Extracted out of [populateProportionsPanel] to keep the EDT callback's
     * cognitive complexity within detekt's 15-branch budget: the runCatching
     * wrapper, the onFailure sink, and the revalidate/repaint calls stay in the
     * caller while all branching / looping / label construction happens here.
     */
    private fun renderProportionsInto(panel: JPanel) {
        val subdued = UIUtil.getContextHelpForeground()
        val project = parentProject
        val weights = project?.let { ProjectLanguageDetector.proportions(it) }
        val entries =
            weights?.let { LanguageDetectionRules.pickDisplayEntries(it) } ?: emptyList()
        if (entries.isEmpty()) {
            panel.add(
                JBLabel(POLYGLOT_COPY, AllIcons.General.Information, SwingConstants.LEADING).apply {
                    foreground = subdued
                },
            )
        } else {
            panel.add(JBLabel(PROPORTIONS_PREFIX).apply { foreground = subdued })
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    panel.add(JBLabel(PROPORTIONS_SEPARATOR.toString()).apply { foreground = subdued })
                }
                val icon = entry.id?.let { LanguageDetectionRules.iconForLanguageId(it) }
                // Named entries render as icon + percent only (icon identifies the
                // language). The "other" bucket has no icon and no single language —
                // render its literal "other" label before the percent so the row
                // still reads as a unit instead of a dangling bare percent that
                // looks like a rendering glitch.
                val text =
                    if (entry.id == null) {
                        "${entry.label} ${entry.percent}%"
                    } else {
                        "${entry.percent}%"
                    }
                panel.add(
                    JBLabel(text, icon, SwingConstants.LEADING).apply {
                        foreground = subdued
                        // Hover affordance: icon alone can be unfamiliar for less-common
                        // languages, so the display name surfaces in the tooltip.
                        // toolTipText is plain-text unless it starts with <html>, so
                        // any pathological Language.displayName renders literally —
                        // no HTML interpretation, consistent with the label-text
                        // safety story.
                        toolTipText = entry.label
                    },
                )
            }
        }
        // Trailing Rescan affordance appended in BOTH paths (normal + polyglot)
        // so the user is never stuck on a stale verdict without an escape hatch.
        // Source of truth: [rescanEligibleProject] — null when there is no
        // focused project OR when the user is unlicensed (see
        // `rescanLicensed` KDoc). Reading it as a single `?.let` keeps this
        // method under detekt's cyclomatic budget. `mouseClicked` re-reads
        // the live license as defence-in-depth against a license that
        // expires while Settings is open.
        rescanEligibleProject?.let { rescanProject ->
            panel.add(JBLabel(PROPORTIONS_SEPARATOR.toString()).apply { foreground = subdued })
            panel.add(buildRescanAffordanceLabel(rescanProject, subdued))
        }
    }

    /**
     * Build the clickable "Rescan" label at the trailing end of the proportions row.
     *
     * Behavior contract (muted-by-default, accent-on-hover, click-to-rescan):
     *  - Foreground starts at `subdued` so the label blends with the row.
     *  - `mouseEntered` re-resolves the currently-applied accent live (NOT
     *    captured at build time) so a mid-session accent change surfaces on
     *    the next hover. `runCatchingPreservingCancellation` contains any
     *    platform-API throw — this runs on every hover tick and an uncaught
     *    EDT exception would break the entire Settings row.
     *  - `mouseExited` restores `subdued`.
     *  - `mouseClicked` calls [ProjectLanguageDetector.rescan]; the
     *    invalidate + schedule + publish chain lives in the detector so the
     *    click inherits dedup-gate coalescing for free.
     *
     * Extracted out of [renderProportionsInto] so the outer function stays
     * inside detekt's cyclomatic budget now that the trailing affordance adds
     * its own conditional branches.
     */
    private fun buildRescanAffordanceLabel(
        project: Project,
        subdued: Color,
    ): JBLabel =
        JBLabel(RESCAN_LABEL).apply {
            foreground = subdued
            toolTipText = RESCAN_TOOLTIP
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(event: MouseEvent) {
                        foreground =
                            runCatchingPreservingCancellation {
                                val variant =
                                    AyuVariant.detect()
                                        ?: return@runCatchingPreservingCancellation subdued
                                ColorUtil.fromHex(AccentResolver.resolve(project, variant))
                            }.onFailure { exception ->
                                // DEBUG not WARN: hover fires on every
                                // pointer entry, so a systemic regression
                                // (malformed stored hex, plugin-unload
                                // race) must not spam idea.log. Still
                                // leaves a triage breadcrumb when a user
                                // reports "hover doesn't light up".
                                LOG.debug(
                                    "Rescan hover AccentResolver threw; falling back to subdued",
                                    exception,
                                )
                            }.getOrDefault(subdued)
                        repaint()
                    }

                    override fun mouseExited(event: MouseEvent) {
                        foreground = subdued
                        repaint()
                    }

                    override fun mouseClicked(event: MouseEvent) {
                        // Defence-in-depth license re-check: the
                        // `rescanLicensed` field was snapshotted at
                        // `buildGroup` time. If the license expired while
                        // Settings was open (grace roll-off, revocation via
                        // LicensingFacade), the label is still visible but
                        // the click must NOT fire a rescan. Matches the
                        // `actionPerformed` gate in RescanLanguageAction.
                        if (!LicenseChecker.isLicensedOrGrace()) return
                        ProjectLanguageDetector.rescan(project)
                    }
                },
            )
        }

    /**
     * Builder-level `@TestOnly` seam that binds a focused project without
     * spinning up a full Swing panel — wiring tests can exercise
     * `resolvePending` / `sourcePending` / proportions rendering without going
     * through [buildGroup].
     */
    @org.jetbrains.annotations.TestOnly
    internal fun setParentProjectForTest(
        project: Project?,
        licensed: Boolean = true,
    ) {
        parentProject = project
        // Tests bypass `buildGroup`, so `rescanLicensed` would otherwise
        // stay false and the inline Rescan affordance would never appear
        // under test. The `licensed` parameter mirrors the gate that
        // `buildGroup` reads from `LicenseChecker.isLicensedOrGrace()` in
        // production — default `true` keeps every existing test driving the
        // rescan-affordance-visible path without having to stand up the
        // platform `ApplicationManager` service graph just to mock
        // `LicenseChecker`. A dedicated unlicensed-suppression spec passes
        // `licensed = false` to lock the inverse contract.
        rescanLicensed = licensed
    }

    /**
     * Builder-level seam for the pending table models. Tests seed rows
     * through this helper to exercise `apply()` / `isModified()` / `reset()`
     * without going through the Swing ToolbarDecorator "+" / "-" path — the
     * alternative is a Swing-heavy fixture just to populate two rows.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun seedPendingForTest(
        projects: Collection<ProjectMapping> = emptyList(),
        languages: Collection<LanguageMapping> = emptyList(),
    ) {
        projectModel.replaceAll(projects)
        languageModel.replaceAll(languages)
    }

    /**
     * Builder-level seam: lazily builds the proportions panel on first call (so
     * wiring tests can bypass `buildGroup`), repopulates from the current
     * [parentProject] + detector cache, and returns `(icon, text)` pairs for
     * each child in layout order.
     *
     * Combines refresh + read into one seam so test call sites stay terse and
     * the public surface of [OverridesGroupBuilder] stays under the detekt
     * `TooManyFunctions` threshold — refresh helpers were inlined into [reset]
     * and the pending-change listener instead of adding dedicated private
     * functions.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun proportionsPanelLabelsForTest(): List<Triple<Icon?, String, String?>> {
        val panel = proportionsPanel ?: JPanel().also { proportionsPanel = it }
        populateProportionsPanel(panel)
        return panel.components.filterIsInstance<JBLabel>().map { label ->
            Triple(label.icon, label.text, label.toolTipText)
        }
    }

    internal fun loadFromState() {
        val state = AccentMappingsSettingsAccess.stateFor()
        // Per-row resilience: ProjectMapping / LanguageMapping have require(...) invariants at
        // construction (valid #RRGGBB hex, lowercase language id, non-blank canonical path).
        // If the persisted XML contains even one malformed row — hand-edited, imported, legacy —
        // a blanket map { ... } would throw during Settings panel construction and the whole
        // Overrides UI would refuse to open. Worse, the user would have no way to fix it
        // because the UI that shows the bad row is exactly the UI that's broken. Drop malformed
        // rows individually with a warn log; users can re-add them from the surviving UI.
        val projects =
            state.projectAccents.mapNotNull { (path, hex) ->
                runCatching {
                    ProjectMapping(
                        canonicalPath = path,
                        displayName = state.projectDisplayNames[path] ?: File(path).name,
                        hex = hex,
                    )
                }.onFailure { exception ->
                    LOG.warn(
                        "Dropping malformed project override row (path='$path', hex='$hex'): ${exception.message}",
                    )
                }.getOrNull()
            }
        val languages =
            state.languageAccents.mapNotNull { (id, hex) ->
                runCatching {
                    val displayName =
                        runCatching {
                            com.intellij.lang.Language
                                .findLanguageByID(id)
                                ?.displayName
                        }.onFailure { exception ->
                            // Display-name lookup fell through to the raw id fallback — log at
                            // DEBUG because the row is still usable; surface the platform API
                            // regression only for users who enable debug logging.
                            LOG.debug("Language display-name lookup failed for id='$id'", exception)
                        }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: id
                    LanguageMapping(
                        languageId = id,
                        displayName = displayName,
                        hex = hex,
                    )
                }.onFailure { exception ->
                    LOG.warn(
                        "Dropping malformed language override row (id='$id', hex='$hex'): ${exception.message}",
                    )
                }.getOrNull()
            }
        projectModel.replaceAll(projects)
        languageModel.replaceAll(languages)
        storedProjects = projectModel.snapshot().map { ProjectMapping(it.canonicalPath, it.displayName, it.hex) }
        storedLanguages = languageModel.snapshot().map { LanguageMapping(it.languageId, it.displayName, it.hex) }
    }

    private val fireChanged: () -> Unit = { listeners.forEach { it.run() } }

    private fun decorateTable(
        table: JBTable,
        actions: TableActions,
    ): JComponent {
        val decorator =
            ToolbarDecorator
                .createDecorator(table)
                .disableUpDownActions()
                .setAddAction { actions.add() }
                .setEditAction { actions.edit() }
                .setRemoveAction { actions.remove() }
                .setAddActionName("Add")
                .setEditActionName("Edit Color")
                .setRemoveActionName("Remove")
                .setAddActionUpdater { _ -> actions.addEnabled() }
                .setEditActionUpdater { _ -> actions.editEnabled() }
                .setRemoveActionUpdater { _ -> actions.removeEnabled() }

        actions.extraActions.forEach { decorator.addExtraAction(it) }
        val wrapper = JPanel(BorderLayout())
        wrapper.add(decorator.createPanel(), BorderLayout.CENTER)
        return wrapper
    }

    private fun projectActions(licensed: Boolean): TableActions {
        val extras: List<AnAction> =
            if (licensed) {
                listOf(
                    object : AnAction(
                        "Pin Current Project",
                        "Add the current project with the global accent",
                        AllIcons.Actions.PinTab,
                    ) {
                        override fun actionPerformed(event: AnActionEvent) {
                            pinCurrentProject()
                        }

                        override fun update(event: AnActionEvent) {
                            // Inlined pin-eligibility gate: the action is enabled only when a
                            // focused, non-default, non-disposed project has a canonical key
                            // that isn't already pinned. Kept inline rather than as a helper so
                            // the file stays under detekt's TooManyFunctions threshold.
                            val project = parentProject
                            event.presentation.isEnabled =
                                project != null &&
                                !project.isDefault &&
                                !project.isDisposed &&
                                AccentResolver.projectKey(project)?.let { key ->
                                    !projectModel.containsPath(key)
                                } == true
                        }
                    },
                )
            } else {
                emptyList()
            }

        return TableActions(
            add = { showAddProjectDialog() },
            edit = {
                editSelectedColor(
                    table = projectTable,
                    rowAt = projectModel::rowAt,
                    hex = ProjectMapping::hex,
                    displayName = ProjectMapping::displayName,
                    updateHex = projectModel::updateHex,
                )
            },
            remove = { removeSelectedRow(projectTable, projectModel::remove) },
            addEnabled = { licensed },
            editEnabled = { licensed && projectTable.selectedRow >= 0 },
            removeEnabled = { projectTable.selectedRow >= 0 },
            extraActions = extras,
        )
    }

    private fun languageActions(licensed: Boolean): TableActions =
        TableActions(
            add = { showAddLanguageDialog() },
            edit = {
                editSelectedColor(
                    table = languageTable,
                    rowAt = languageModel::rowAt,
                    hex = LanguageMapping::hex,
                    displayName = LanguageMapping::displayName,
                    updateHex = languageModel::updateHex,
                )
            },
            remove = { removeSelectedRow(languageTable, languageModel::remove) },
            addEnabled = { licensed },
            editEnabled = { licensed && languageTable.selectedRow >= 0 },
            removeEnabled = { languageTable.selectedRow >= 0 },
            extraActions = emptyList(),
        )

    // Project-table actions: add / edit / remove + pin-current

    private fun showAddProjectDialog() {
        val excluded = projectModel.snapshot().map { it.canonicalPath }.toSet()
        val dialog = AddProjectMappingDialog(parentProject, excluded)
        if (!dialog.showAndGet()) return
        val path = dialog.resultCanonicalPath ?: return
        val hex = dialog.resultHex ?: return
        val name = dialog.resultDisplayName ?: File(path).name
        val index = projectModel.add(ProjectMapping(path, name, hex))
        projectTable.selectionModel.setSelectionInterval(index, index)
        fireChanged()
    }

    private fun pinCurrentProject() {
        val project = parentProject ?: return
        if (project.isDefault || project.isDisposed) return
        val key = AccentResolver.projectKey(project) ?: return
        if (projectModel.containsPath(key)) return
        val variant = AyuVariant.detect() ?: AyuVariant.MIRAGE
        val hex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        val name = project.name.takeIf { it.isNotBlank() } ?: File(key).name
        val index = projectModel.add(ProjectMapping(key, name, hex))
        projectTable.selectionModel.setSelectionInterval(index, index)
        fireChanged()
    }

    /**
     * Generic remove-selected-row flow shared by the project and language tables.
     * Shares the JBTable selection probe, the model-agnostic remove call, and the
     * pending-change notification.
     */
    private fun removeSelectedRow(
        table: JBTable,
        remove: (Int) -> Unit,
    ) {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        remove(row)
        fireChanged()
    }

    // Language-table actions: add / edit / remove

    private fun showAddLanguageDialog() {
        val excluded = languageModel.snapshot().map { it.languageId }.toSet()
        val dialog = AddLanguageMappingDialog(parentProject, excluded)
        if (!dialog.showAndGet()) return
        val id = dialog.resultLanguageId ?: return
        val hex = dialog.resultHex ?: return
        val name = dialog.resultDisplayName ?: id
        val index = languageModel.add(LanguageMapping(id, name, hex))
        languageTable.selectionModel.setSelectionInterval(index, index)
        fireChanged()
    }

    /**
     * Generic edit-color flow shared by project and language tables. Takes accessors
     * for the model-specific lookup and update so the Project- and Language- specific
     * callers collapse to a single call with closures capturing their model/table —
     * sharing the JBTable selection probe, the modal dialog wiring, and the
     * pending-change notification.
     */
    private inline fun <M> editSelectedColor(
        table: JBTable,
        rowAt: (Int) -> M?,
        hex: (M) -> String,
        displayName: (M) -> String,
        updateHex: (Int, String) -> Unit,
    ) {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val mapping = rowAt(row) ?: return
        val dialog = EditAccentColorDialog(parentProject, hex(mapping), displayName(mapping))
        if (!dialog.showAndGet()) return
        updateHex(row, dialog.resultHex)
        fireChanged()
    }

    companion object {
        private val LOG = logger<OverridesGroupBuilder>()
        private const val CARD_PROJECTS = "projects"
        private const val CARD_LANGUAGES = "languages"
        private const val TABLE_ROW_HEIGHT = 24
        private const val BAR_HORIZONTAL_GAP = 4
        private const val BAR_VERTICAL_GAP = 0

        /**
         * Horizontal gap between entries in the proportions status row.
         * Scaled via [com.intellij.util.ui.JBUI.scale] for HiDPI displays.
         * 12 px at 1x matches the rhythm of other Settings rows in the plugin.
         */
        private const val PROPORTIONS_ENTRY_GAP_PX: Int = 12

        /**
         * Fixed status-line copy rendered under Per-Language Accent Pins when the
         * detector has no warm weights for the focused project — cache is cold, the
         * most recent scan produced no dominant winner, or the legacy SDK / module
         * fallback path was used (no weights to proportion). Exact string is locked
         * per phase-26 D-05 / ROADMAP success criterion. NOT i18n'd, NOT
         * user-configurable. The em-dash is U+2014, not a hyphen.
         */
        const val POLYGLOT_COPY: String =
            "Polyglot — no single dominant language; global accent applies"

        /**
         * Prefix for the detected-language proportions line. Paired with the output
         * of [LanguageDetectionRules.pickTopLanguagesForDisplay]. The trailing space
         * is part of the locked copy — do not trim.
         */
        const val DETECTED_PREFIX: String = "Detected in this project: "

        /**
         * Header rendered in front of the icon-row proportions layout under the
         * overrides table. Kept terse ("Detected:") because the row sits below
         * the overrides table where context is already established, and the
         * full "Languages detected" was verbose for a subdued helper-text row.
         */
        const val PROPORTIONS_PREFIX: String = "Detected:"

        /**
         * Middle-dot (U+00B7) separator glued to the end of every entry except
         * the last. Matches the visual separator style used in the font preset
         * panel (e.g. `Maple Mono · 14pt · Regular · 1.0× · ligatures`).
         */
        const val PROPORTIONS_SEPARATOR: Char = '·'

        /**
         * Literal text of the inline clickable "Rescan" affordance at the
         * trailing end of the proportions row. Kept short so it doesn't
         * dominate the muted status line — the cursor change + hover-accent
         * already signal interactivity.
         */
        const val RESCAN_LABEL: String = "Rescan"

        /**
         * Tooltip for the inline Rescan label. Clarifies what will happen
         * without bloating the visible text — hover discoverability.
         */
        const val RESCAN_TOOLTIP: String =
            "Re-detect the dominant language of this project"
    }

    /**
     * [JBTable] that sizes its viewport to the current row count (clamped to
     * [MIN_VISIBLE_ROWS]..[MAX_VISIBLE_ROWS]) and auto-packs every column to the wider of
     * header / cell content on every model change. With [AUTO_RESIZE_LAST_COLUMN] Swing then
     * lets the last column absorb any extra width when the containing panel is wider than
     * the sum of packed widths, and shrink (horizontal scroll) when narrower.
     */
    private class AutoSizingTable(
        model: TableModel,
    ) : JBTable(model) {
        init {
            autoResizeMode = AUTO_RESIZE_LAST_COLUMN
            model.addTableModelListener { repack() }
        }

        override fun getPreferredScrollableViewportSize(): Dimension {
            val visibleRows = rowCount.coerceIn(MIN_VISIBLE_ROWS, MAX_VISIBLE_ROWS)
            val headerHeight = tableHeader?.preferredSize?.height ?: 0
            val bodyHeight = visibleRows * rowHeight
            // Viewport width = sum of packed column widths so the surrounding Kotlin UI DSL
            // panel (Align.FILL) grows to show every column fully before kicking in horizontal
            // scrolling. Floor to MIN_VIEWPORT_WIDTH so an empty table still looks reasonable
            // on first render (before the TableModelListener has fired a pack pass).
            val summedWidth = (0 until columnCount).sumOf { columnModel.getColumn(it).preferredWidth }
            val width = maxOf(summedWidth, MIN_VIEWPORT_WIDTH)
            return Dimension(width, bodyHeight + headerHeight)
        }

        /**
         * Packs every column (including the last) to the widest of header + cell content.
         * [AUTO_RESIZE_LAST_COLUMN] then lets the last column expand when the containing
         * panel exceeds the sum, and lets it shrink (horizontal scroll) when the panel is
         * narrower.
         */
        fun repack() {
            for (index in 0 until columnCount) {
                packColumn(index)
            }
            revalidate()
            repaint()
        }

        private fun packColumn(columnIndex: Int) {
            val column = columnModel.getColumn(columnIndex)
            val headerWidth =
                tableHeader
                    ?.defaultRenderer
                    ?.getTableCellRendererComponent(
                        this,
                        column.headerValue,
                        false,
                        false,
                        -1,
                        columnIndex,
                    )?.preferredSize
                    ?.width
                    ?: COLUMN_MIN_WIDTH
            var width = maxOf(COLUMN_MIN_WIDTH, headerWidth + PACK_PADDING)
            for (row in 0 until rowCount) {
                val renderer = getCellRenderer(row, columnIndex)
                val comp = prepareRenderer(renderer, row, columnIndex)
                width = maxOf(width, comp.preferredSize.width + PACK_PADDING)
            }
            column.preferredWidth = width.coerceAtMost(COLUMN_MAX_WIDTH)
        }

        companion object {
            private const val MIN_VISIBLE_ROWS = 2
            private const val MAX_VISIBLE_ROWS = 8
            private const val COLUMN_MIN_WIDTH = 60
            private const val COLUMN_MAX_WIDTH = 600
            private const val PACK_PADDING = 16
            private const val MIN_VIEWPORT_WIDTH = 520
        }
    }

    private data class TableActions(
        val add: () -> Unit,
        val edit: () -> Unit,
        val remove: () -> Unit,
        val addEnabled: () -> Boolean,
        val editEnabled: () -> Boolean,
        val removeEnabled: () -> Boolean,
        val extraActions: List<AnAction>,
    )

    private class DimOrphanRenderer(
        private val orphanProbe: (Int) -> Boolean,
    ) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val orphan = orphanProbe(row)
            if (orphan) {
                foreground = javax.swing.UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                toolTipText = "Path no longer exists on disk"
            } else {
                toolTipText = null
            }
            return this
        }
    }
}

/**
 * Isolated state access wrapper so test code can substitute a synthetic state
 * without pulling in the full [AccentMappingsSettings] service graph.
 */
private object AccentMappingsSettingsAccess {
    fun stateFor(): AccentMappingsState = AccentMappingsSettings.getInstance().state
}

private fun List<ProjectMapping>.toFingerprint(): Set<Triple<String, String, String>> =
    map { Triple(it.canonicalPath, it.displayName, it.hex) }.toSet()

private fun List<LanguageMapping>.toLanguageFingerprint(): Set<Triple<String, String, String>> =
    map { Triple(it.languageId, it.displayName, it.hex) }.toSet()
