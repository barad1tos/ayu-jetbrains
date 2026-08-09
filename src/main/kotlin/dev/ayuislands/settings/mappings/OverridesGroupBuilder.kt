package dev.ayuislands.settings.mappings

import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.table.JBTable
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentDetectorLookup
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentHexPolicy
import dev.ayuislands.accent.AccentResolutionChainBuilder
import dev.ayuislands.accent.AccentResolutionRequest
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.LanguageDetectionRules
import dev.ayuislands.accent.ProjectLanguageDetectionListener
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PremiumFeatureGate
import dev.ayuislands.settings.SettingsParticipant
import dev.ayuislands.settings.bindNewSettingBadge
import dev.ayuislands.settings.premiumFeatureNotice
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel

internal data class PendingAccentPreview(
    val hex: String,
    val source: AccentResolver.Source,
    val detail: String?,
)

/**
 * Builds the "Overrides" group inside the Accent settings tab. Hosts a segmented
 * toggle between Projects and Languages, each backed by its own [JBTable] and
 * toolbar controls with add / pin-current / edit-color / remove actions.
 *
 * All mutations happen on an in-memory pending model; callers use [isModified],
 * [apply], and [reset] to participate in the usual settings lifecycle. [addPendingChangeListener]
 * lets observers (the reactive "Currently active: ..." comment) refresh on every edit.
 */
@Suppress("LargeClass", "TooManyFunctions")
internal class OverridesGroupBuilder(
    private val currentGlobalAccentHex: () -> String? = ::storedCurrentVariantAccentHex,
    private val draft: AccentMappingsDraft = AccentMappingsDraft(),
    private val stateProvider: () -> AccentMappingsState = {
        AccentMappingsSettings.getInstance().state
    },
) : SettingsParticipant {
    private val projectModel = ProjectMappingsTableModel(draft)
    private val languageModel = LanguageMappingsTableModel(draft)
    private val projectTable: JBTable = AutoSizingTable(projectModel)
    private val languageTable: JBTable = AutoSizingTable(languageModel)

    private val listeners: MutableList<Runnable> = mutableListOf()
    private val diagnosticsRefreshListener = Runnable { refreshResolutionPanel() }

    private val cardPanel = JPanel(CardLayout())
    private var parentProject: Project? = null
    private val tableActions =
        OverridesTableActions(
            projectModel = projectModel,
            languageModel = languageModel,
            projectTable = projectTable,
            languageTable = languageTable,
            parentProjectProvider = { parentProject },
            isLicensed = LicenseChecker::isLicensedOrGrace,
            onChanged = { fireChanged() },
        )

    /**
     * Captured diagnostics panel for focused-project language resolution.
     * Refreshed on every [reset], pending-change event, and
     * [ProjectLanguageDetectionListener.TOPIC] notification.
     */
    private var proportionsPanel: ProjectLanguageResolutionPanel? = null

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
    private var detectionConnectionParent: Disposable? = null
    private var hasLoadedState = false

    /**
     * Derived rescan-eligibility: non-null iff a focused project is present and
     * the current live license permits the Pro affordance.
     */
    private val rescanEligibleProject: Project?
        get() =
            parentProject
                ?.takeUnless { it.isDefault }
                ?.takeUnless { it.isDisposed }
                ?.takeIf { LicenseChecker.isLicensedOrGrace() }

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
        languageTable.getColumnModel().getColumn(LanguageMappingsTableModel.COLUMN_LANGUAGE).apply {
            cellRenderer = LanguageIconRenderer()
        }
    }

    /**
     * Tear down the detection-Topic subscription. Called from
     * [dev.ayuislands.settings.AyuIslandsConfigurable.disposeUIResources]
     * via the `dispose()` override on
     * [dev.ayuislands.settings.AyuIslandsAccentPanel] through its
     * `SettingsParticipant.dispose()` method. Without this
     * call the live subscriber count grows by one per Settings open. Safe
     * to call multiple times; no-op when the builder was never wired up.
     */
    override fun dispose() {
        safelyDisconnectDetection("dispose")
        listeners.clear()
    }

    /**
     * Shared disconnect-and-null helper for the two call sites that
     * tear down [detectionConnection]: [dispose] from
     * `AyuIslandsConfigurable.disposeUIResources` and the re-entry
     * guard inside [buildGroup] when a variant swap reuses the builder.
     *
     * Wrapping the disconnect in `runCatchingPreservingCancellation`
     * matches the sibling defence in
     * [dev.ayuislands.actions.RescanLanguageAction]: the platform has
     * been observed to throw `AlreadyDisposedException` from
     * `MessageBusConnection.disconnect()` during a plugin-unload race.
     * Without the wrap, a throw here propagates up through
     * `AyuIslandsConfigurable.disposeUIResources` and skips both the
     * remaining panel teardown and `super.disposeUIResources`, which
     * would leak the `BoundConfigurable` binding cleanup.
     *
     * The [site] string names the caller in the breadcrumb so triage
     * can distinguish a dispose-path failure from a rebuild-path one —
     * the former is a Settings-close race, the latter is a mid-session
     * variant swap.
     */
    private fun safelyDisconnectDetection(site: String) {
        val connection = detectionConnection
        val parent = detectionConnectionParent
        detectionConnection = null
        detectionConnectionParent = null
        runCatchingPreservingCancellation { connection?.disconnect() }
            .onFailure { exception ->
                LOG.debug("OverridesGroupBuilder $site disconnect failed", exception)
            }
        parent?.let { disposable ->
            runCatchingPreservingCancellation { Disposer.dispose(disposable) }
                .onFailure { exception ->
                    LOG.debug("OverridesGroupBuilder $site parent dispose failed", exception)
                }
        }
    }

    fun buildGroup(
        panel: Panel,
        contextProject: Project?,
        trailingContent: (Panel.() -> Unit)? = null,
    ) {
        parentProject = contextProject
        loadStateOnce()

        val licensed = LicenseChecker.isLicensedOrGrace()
        val gate =
            PremiumFeatureGate(
                featureName = "Accent overrides",
                lockedDescription =
                    "Accent overrides are a Pro feature. " +
                        "Preview project and language accent pins here.",
                requestMessage = "Unlock accent overrides",
                isUnlocked = licensed,
            )
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
        cardPanel.add(tableActions.decorateProjectTable(showPinAction = licensed), CARD_PROJECTS)
        cardPanel.add(tableActions.decorateLanguageTable(), CARD_LANGUAGES)
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
                premiumFeatureNotice(gate)
                row {
                    cell(segmentedBar)
                }
                row {
                    cell(cardPanel)
                        .resizableColumn()
                        .align(Align.FILL)
                }
                row {
                    val resolutionPanel = createResolutionPanel()
                    proportionsPanel = resolutionPanel
                    refreshResolutionPanel()
                    cell(resolutionPanel)
                }
                trailingContent?.invoke(this)
            }
        collapsible.expanded = settings.state.overridesGroupExpanded
        collapsible.addExpandedListener { expanded ->
            settings.state.overridesGroupExpanded = expanded
        }
        // The project-icon accent toggle ships inside this group (trailingContent).
        collapsible.bindNewSettingBadge("accent-from-project-icon")
        // Two independent refresh channels share one diagnostics refresh helper:
        //  - Pending-change listener: Settings-local edits (add / edit / delete a row)
        //    fire `fireChanged()` synchronously on EDT, which re-reads the warm cache.
        //  - Detection Topic: async scan completions (startup warmup, `ModuleRootListener`
        //    content-root change, user-triggered rescan) fire
        //    `ProjectLanguageDetectionListener.scanCompleted` on EDT — the only signal
        //    the row has to exit a stale winner state without a settings edit.
        addPendingChangeListener(diagnosticsRefreshListener)
        // Subscription lifetime is tied to an owned Disposable parent and is
        // also disconnected from [dispose] / re-entry. The explicit disconnect
        // preserves the existing failure-tolerant teardown path; the parented
        // MessageBus connection gives the platform a proper lifetime owner.
        safelyDisconnectDetection("re-entry")
        // The `panel.isDisplayable` guard inside invokeLater below covers
        // the window where Settings has been closed but dispose hasn't
        // fired yet — without it, `refreshResolutionPanel` would paint into a
        // detached panel and waste EDT budget.
        contextProject?.let { project ->
            val connectionParent = Disposer.newDisposable("AyuIslandsOverrides.languageDiagnostics")
            detectionConnectionParent = connectionParent
            val connection = project.messageBus.connect(connectionParent)
            detectionConnection = connection
            connection.subscribe(
                ProjectLanguageDetectionListener.TOPIC,
                ProjectLanguageDetectionListener {
                    SwingUtilities.invokeLater {
                        val panel = proportionsPanel ?: return@invokeLater
                        if (!panel.isDisplayable) return@invokeLater
                        refreshResolutionPanel()
                    }
                },
            )
        }
    }

    // Settings panel lifecycle (isModified / apply / reset)

    override fun isModified(): Boolean = draft.isModified

    override fun apply() {
        if (!LicenseChecker.isLicensedOrGrace()) return
        draft.writeTo(stateProvider())

        // Re-apply the committed mapping set via resolver → applicator → swap-cache sync.
        // Keep the focus-swap cache consistent so the next WINDOW_ACTIVATED event evaluates
        // against the color actually showing on screen right now.
        //
        // Defense-in-depth: the resolver / applicator / swap-cache chain touches LafManager,
        // UIManager, and the project-swap service; a transient failure anywhere in that chain
        // must not short-circuit the draft commit or `fireChanged()` below, or the
        // settings UI would drift (persisted state saved, but `isModified()` keeps reporting
        // "modified" because the draft's committed snapshot stayed on the pre-apply state).
        runCatchingPreservingCancellation {
            AyuVariant.detect()?.let { variant ->
                // Fall through to the OS-active cascade when the builder has no parentProject
                // bound yet — same helper every apply path ultimately converges on.
                val project = parentProject ?: AccentApplicator.resolveFocusedProject()
                val hex = AccentResolver.resolve(project, variant)
                val applied = AccentApplicator.applyFromHexString(hex)
                if (applied) {
                    ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
                } else {
                    LOG.warn("Skipping swap publish: applyFromHexString rejected '$hex'")
                }
            }
        }.onFailure { exception ->
            LOG.warn("Re-apply after overrides commit failed; persisted state is saved, UI may need reopen", exception)
        }
        draft.markCommitted()
        fireChanged()
    }

    override fun reset() {
        draft.reset()
        projectModel.refreshAll()
        languageModel.refreshAll()
        refreshResolutionPanel()
        fireChanged()
    }

    fun addPendingChangeListener(runnable: Runnable) {
        if (listeners.none { it === runnable }) {
            listeners += runnable
        }
    }

    internal fun preview(
        project: Project?,
        fallbackGlobalHex: String,
        cacheOnly: Boolean = false,
    ): PendingAccentPreview =
        preview(
            project = project,
            fallbackGlobalHex = fallbackGlobalHex,
            detectorLookup = AccentDetectorLookup.SnapshotLookup(shouldReadFallbackEarly = cacheOnly),
        )

    private fun preview(
        project: Project?,
        fallbackGlobalHex: String,
        detectorLookup: AccentDetectorLookup.SnapshotLookup,
    ): PendingAccentPreview {
        val winner =
            AccentResolutionChainBuilder.overrideWinner(
                project,
                AccentResolutionRequest(
                    view = draft,
                    lookup = detectorLookup,
                    policy = AccentHexPolicy.RAW,
                ),
            )
        val source = winner?.source ?: AccentResolver.Source.GLOBAL
        return PendingAccentPreview(
            hex = winner?.hex ?: fallbackGlobalHex,
            source = source,
            detail = activeSourceDetail(project, source, detectorLookup.consultedVerdict),
        )
    }

    private fun activeSourceDetail(
        project: Project?,
        source: AccentResolver.Source,
        verdict: ProjectLanguageVerdict?,
    ): String? {
        if (source !in LANGUAGE_DETAIL_SOURCES) return null
        val activeProject =
            project
                ?.takeUnless { it.isDefault }
                ?.takeUnless { it.isDisposed }
                ?: return null
        val projectKey = AccentResolver.projectKey(activeProject) ?: return null
        val forcedLanguageId = draft.forcedLanguageId(projectKey)
        val isManualLanguageSource =
            source == AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE ||
                (
                    source == AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE &&
                        forcedLanguageId != null
                )
        if (isManualLanguageSource) {
            return forcedLanguageId?.let { "${languageDisplayName(it)}, manual" }
        }
        return (verdict as? ProjectLanguageVerdict.Detected)?.let(::detectedLanguageDetail)
    }

    // Internals: pending-model resolver + UI wiring helpers

    private fun createResolutionPanel(): ProjectLanguageResolutionPanel =
        ProjectLanguageResolutionPanel(
            currentAccentHex = ::currentPendingAccentHex,
            onSetFallback = { hex -> setFocusedProjectFallback(hex) },
            onSetForcedLanguage = { languageId -> setFocusedProjectForcedLanguage(languageId) },
            onClearForcedLanguage = { setFocusedProjectForcedLanguage(null) },
            onClearFallback = { setFocusedProjectFallback(null) },
            onRescan = { rescanEligibleProject?.let(ProjectLanguageDetector::rescan) },
            canRescanNow = { LicenseChecker.isLicensedOrGrace() },
        )

    private fun refreshResolutionPanel() {
        proportionsPanel?.refresh(resolutionPanelState())
    }

    private fun resolutionPanelState(): ProjectLanguageResolutionPanel.State {
        val project = parentProject
        val projectKey = focusedProjectKey()
        val licensed = LicenseChecker.isLicensedOrGrace()
        val detectorLookup = AccentDetectorLookup.SnapshotLookup(shouldReadFallbackEarly = true)
        val preview = preview(project, fallbackGlobalHex = "", detectorLookup = detectorLookup)
        val verdict = project?.let(detectorLookup::snapshotVerdict) ?: ProjectLanguageVerdict.Unavailable
        return ProjectLanguageResolutionPanel.State(
            verdict = verdict,
            forcedLanguageId = projectKey?.let(draft::forcedLanguageId),
            fallbackHex = projectKey?.let(draft::projectFallbackAccent),
            // Same cache-only engine walk as the "Currently active" label — the
            // diagnostics row and the label can never disagree on the source.
            activeSource = preview.source,
            canMutate = licensed && projectKey != null,
            canRescan = licensed && projectKey != null,
            canSetFallbackToCurrentAccent = licensed && currentPendingAccentHex() != null,
        )
    }

    private fun currentPendingAccentHex(): String? {
        val candidate = currentProjectOverrideHex() ?: currentGlobalAccentHex()
        return candidate?.let { hex -> AccentHex.of(hex)?.value }
    }

    private fun detectedLanguageDetail(verdict: ProjectLanguageVerdict.Detected): String {
        val percent =
            verdict.weights
                ?.let { weights ->
                    LanguageDetectionRules
                        .pickDisplayEntries(weights)
                        .firstOrNull { it.id == verdict.languageId }
                        ?.percent
                }?.let { ", $it%" }
                ?: ""
        return languageDisplayName(verdict.languageId) + percent
    }

    private fun languageDisplayName(languageId: String): String =
        LanguageDetectionRules.displayNameForLanguageId(languageId)

    private fun currentProjectOverrideHex(): String? {
        val projectKey = focusedProjectKey() ?: return null
        return draft.projectAccent(projectKey)
    }

    private fun focusedProjectKey(): String? {
        val project =
            parentProject
                ?.takeUnless { it.isDefault }
                ?.takeUnless { it.isDisposed }
                ?: return null
        return AccentResolver.projectKey(project)
    }

    private fun setFocusedProjectFallback(hex: String?) {
        if (!LicenseChecker.isLicensedOrGrace()) return
        val projectKey = focusedProjectKey() ?: return
        setPendingFallbackAccent(projectKey, hex)
    }

    private fun setFocusedProjectForcedLanguage(languageId: String?) {
        if (!LicenseChecker.isLicensedOrGrace()) return
        val projectKey = focusedProjectKey() ?: return
        setPendingForcedLanguage(projectKey, languageId)
    }

    internal fun setPendingFallbackAccent(
        projectKey: String,
        hex: String?,
    ) {
        draft.setProjectFallbackAccent(projectKey, hex)
        fireChanged()
    }

    internal fun setPendingForcedLanguage(
        projectKey: String,
        languageId: String?,
    ) {
        draft.setForcedLanguage(projectKey, languageId)
        fireChanged()
    }

    internal fun loadFromState() {
        draft.load(
            stateProvider(),
            languageDisplayName = { languageId ->
                Language
                    .findLanguageByID(languageId)
                    ?.displayName
            },
            warn = LOG::warn,
            reportLookupFailure = LOG::warn,
        )
        hasLoadedState = true
        projectModel.refreshAll()
        languageModel.refreshAll()
    }

    private fun loadStateOnce() {
        if (!hasLoadedState) {
            loadFromState()
        }
    }

    private val fireChanged: () -> Unit = { listeners.forEach { it.run() } }

    companion object {
        private val LOG = logger<OverridesGroupBuilder>()
        private const val CARD_PROJECTS = "projects"
        private const val CARD_LANGUAGES = "languages"
        private const val TABLE_ROW_HEIGHT = 24
        private const val BAR_HORIZONTAL_GAP = 4
        private const val BAR_VERTICAL_GAP = 0
        private val LANGUAGE_DETAIL_SOURCES =
            setOf(
                AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE,
                AccentResolver.Source.LANGUAGE_OVERRIDE,
                AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE,
            )
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
                foreground = javax.swing.UIManager.getColor("Label.disabledForeground") ?: JBColor.GRAY
                toolTipText = "Path no longer exists on disk"
            } else {
                toolTipText = null
            }
            return this
        }
    }

    private class LanguageIconRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val model = table.model as? LanguageMappingsTableModel ?: return this
            val modelRow = table.convertRowIndexToModel(row)
            icon = model.rowAt(modelRow)?.let { LanguageDetectionRules.iconForLanguageId(it.languageId) }
            return this
        }
    }
}

private fun storedCurrentVariantAccentHex(): String? =
    AyuVariant
        .detect()
        ?.let { variant -> AyuIslandsSettings.getInstance().getAccentForVariant(variant) }
