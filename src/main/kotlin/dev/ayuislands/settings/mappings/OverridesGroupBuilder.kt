package dev.ayuislands.settings.mappings

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
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentOverrideResolver
import dev.ayuislands.accent.AccentOverrideSnapshot
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
import dev.ayuislands.settings.premiumFeatureNotice
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.util.Locale
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel

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
class OverridesGroupBuilder(
    private val currentGlobalAccentHex: () -> String? = ::storedCurrentVariantAccentHex,
) {
    private val projectModel = ProjectMappingsTableModel()
    private val languageModel = LanguageMappingsTableModel()
    private val projectTable: JBTable = AutoSizingTable(projectModel)
    private val languageTable: JBTable = AutoSizingTable(languageModel)

    private var storedProjects: List<ProjectMapping> = emptyList()
    private var storedLanguages: List<LanguageMapping> = emptyList()
    private val pendingFallbackAccents: MutableMap<String, String> = linkedMapOf()
    private val pendingForcedLanguages: MutableMap<String, String> = linkedMapOf()
    private var pendingLanguageFallbackAccent: String? = null
    private var storedFallbackAccents: Map<String, String> = emptyMap()
    private var storedForcedLanguages: Map<String, String> = emptyMap()
    private var storedLanguageFallbackAccent: String? = null
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
            onChanged = { fireChanged() },
        )

    /**
     * Captured diagnostics panel for focused-project language resolution.
     * Refreshed on every [reset], pending-change event, and
     * [ProjectLanguageDetectionListener.TOPIC] notification. The legacy
     * `proportionsPanel` name is intentionally retained because test seams and
     * older harness code still reflect into it.
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

    /**
     * License-gate snapshot captured once at [buildGroup] time for the static
     * premium gate copy and table action preview. Diagnostics read the live
     * license state on every refresh so their source/action labels stay aligned
     * with [sourcePending] if the license changes while Settings is open.
     */
    private var rescanLicensed: Boolean = false

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
     * [dev.ayuislands.settings.AyuIslandsAccentPanel] (through the
     * `AyuIslandsSettingsPanel.dispose()` interface method). Without this
     * call the live subscriber count grows by one per Settings open. Safe
     * to call multiple times; no-op when the builder was never wired up.
     */
    fun dispose() {
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
    ) {
        parentProject = contextProject
        loadFromState()

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
        // Capture the license snapshot up front so the diagnostics panel state
        // reads one boolean + one nullable. See `rescanLicensed` KDoc for why
        // license and project eligibility live in separate fields.
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
        cardPanel.add(tableActions.decorateProjectTable(licensed), CARD_PROJECTS)
        cardPanel.add(tableActions.decorateLanguageTable(licensed), CARD_LANGUAGES)
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
            }
        collapsible.expanded = settings.state.overridesGroupExpanded
        collapsible.addExpandedListener { expanded ->
            settings.state.overridesGroupExpanded = expanded
        }
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

    fun isModified(): Boolean {
        val currentProjects = projectModel.snapshot().toFingerprint()
        val storedProjectsFingerprint = storedProjects.toFingerprint()
        if (currentProjects != storedProjectsFingerprint) return true

        val currentLanguages = languageModel.snapshot().toLanguageFingerprint()
        val storedLanguagesFingerprint = storedLanguages.toLanguageFingerprint()
        if (currentLanguages != storedLanguagesFingerprint) return true

        if (pendingFallbackAccents != storedFallbackAccents) return true
        if (pendingLanguageFallbackAccent != storedLanguageFallbackAccent) return true
        return pendingForcedLanguages != storedForcedLanguages
    }

    fun apply() {
        if (!LicenseChecker.isLicensedOrGrace()) return
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
        state.projectFallbackAccents.clear()
        state.projectFallbackAccents.putAll(pendingFallbackAccents)
        state.forcedProjectLanguages.clear()
        state.forcedProjectLanguages.putAll(pendingForcedLanguages)
        state.languageFallbackAccent = pendingLanguageFallbackAccent

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
        captureStoredSnapshot()
        fireChanged()
    }

    fun reset() {
        projectModel.replaceAll(storedProjects.map { ProjectMapping(it.canonicalPath, it.displayName, it.hex) })
        languageModel.replaceAll(storedLanguages.map { LanguageMapping(it.languageId, it.displayName, it.hex) })
        pendingFallbackAccents.clear()
        pendingFallbackAccents.putAll(storedFallbackAccents)
        pendingForcedLanguages.clear()
        pendingForcedLanguages.putAll(storedForcedLanguages)
        pendingLanguageFallbackAccent = storedLanguageFallbackAccent
        refreshResolutionPanel()
        fireChanged()
    }

    fun addPendingChangeListener(runnable: Runnable) {
        if (listeners.none { it === runnable }) {
            listeners += runnable
        }
    }

    /**
     * Resolve the accent hex using the **pending** (not yet applied) overrides model.
     * Falls back to [fallbackGlobalHex] when no override matches.
     */
    fun resolvePending(
        project: Project?,
        fallbackGlobalHex: String,
        cacheOnly: Boolean = false,
    ): String = resolvePendingOverride(project, warmDetector = !cacheOnly)?.hex ?: fallbackGlobalHex

    /**
     * Matching [AccentResolver.Source] for [project] under the **pending** overrides model.
     */
    fun sourcePending(
        project: Project?,
        cacheOnly: Boolean = false,
    ): AccentResolver.Source =
        resolvePendingOverride(project, warmDetector = !cacheOnly)?.source ?: AccentResolver.Source.GLOBAL

    internal fun activeSourceDetailPending(
        project: Project?,
        source: AccentResolver.Source,
        cacheOnly: Boolean = false,
    ): String? {
        if (source !in LANGUAGE_DETAIL_SOURCES) return null
        val activeProject =
            project
                ?.takeUnless { it.isDefault }
                ?.takeUnless { it.isDisposed }
                ?: return null
        val projectKey = AccentResolver.projectKey(activeProject) ?: return null
        val forcedLanguageId = pendingForcedLanguages[projectKey]
        val isManualLanguageSource =
            source == AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE ||
                (
                    source == AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE &&
                        forcedLanguageId != null
                )
        if (isManualLanguageSource) {
            return forcedLanguageId?.let { "${languageDisplayName(it)}, manual" }
        }
        val verdict =
            if (cacheOnly) {
                ProjectLanguageDetector.verdict(activeProject)
            } else {
                ProjectLanguageDetector.verdict(activeProject, warmCache = true)
            }
        return (verdict as? ProjectLanguageVerdict.Detected)?.let(::detectedLanguageDetail)
    }

    private fun resolvePendingOverride(
        project: Project?,
        warmDetector: Boolean,
    ): AccentOverrideResolver.Result? {
        val activeProject =
            project
                ?.takeUnless { it.isDefault }
                ?.takeUnless { it.isDisposed }
                ?: return null
        val projectKey = AccentResolver.projectKey(activeProject) ?: return null
        var cachedVerdict: ProjectLanguageVerdict? = null

        fun verdict(warmCache: Boolean): ProjectLanguageVerdict =
            cachedVerdict
                ?: ProjectLanguageDetector
                    .verdict(activeProject, warmCache = warmCache)
                    .also { cachedVerdict = it }

        return AccentOverrideResolver.resolve(
            AccentOverrideResolver.Request(
                projectKey = projectKey,
                snapshot = pendingOverrideSnapshot(),
                overridesEnabled = LicenseChecker.isLicensedOrGrace(),
                validateHex = false,
                warmDetector = warmDetector,
                detectedLanguage = { (verdict(warmDetector) as? ProjectLanguageVerdict.Detected)?.languageId },
                verdict = ::verdict,
            ),
        )
    }

    private fun pendingOverrideSnapshot(): AccentOverrideSnapshot =
        AccentOverrideSnapshot(
            projectAccents = projectModel.snapshot().associate { it.canonicalPath to it.hex },
            languageAccents = languageModel.snapshot().associate { it.languageId to it.hex },
            projectFallbackAccents = pendingFallbackAccents.toMap(),
            forcedProjectLanguages = pendingForcedLanguages.toMap(),
            languageFallbackAccent = pendingLanguageFallbackAccent,
        )

    // Internals: pending-model resolver + UI wiring helpers

    /**
     * Legacy-named test seam for the diagnostics row summary. The production UI
     * now renders [ProjectLanguageResolutionPanel], but keeping this method name
     * avoids a broad test-harness rewrite while switching the read path to
     * [ProjectLanguageDetector.verdict].
     */
    @org.jetbrains.annotations.TestOnly
    internal fun currentProportionsTextForTest(): String {
        val panel = proportionsPanel ?: createResolutionPanel().also { proportionsPanel = it }
        refreshResolutionPanel()
        return panel.currentSummaryForTest()
    }

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
        val verdict = project?.let(ProjectLanguageDetector::verdict) ?: ProjectLanguageVerdict.Unavailable
        return ProjectLanguageResolutionPanel.State(
            verdict = verdict,
            forcedLanguageId = projectKey?.let(pendingForcedLanguages::get),
            fallbackHex = projectKey?.let(pendingFallbackAccents::get),
            activeSource = diagnosticsSource(projectKey, verdict, licensed),
            canMutate = licensed && projectKey != null,
            canRescan = licensed && projectKey != null,
            canSetFallbackToCurrentAccent = licensed && currentPendingAccentHex() != null,
        )
    }

    private fun diagnosticsSource(
        projectKey: String?,
        verdict: ProjectLanguageVerdict,
        isLicensed: Boolean,
    ): AccentResolver.Source =
        AccentOverrideResolver.source(
            AccentOverrideResolver.Request(
                projectKey = projectKey,
                snapshot = pendingOverrideSnapshot(),
                overridesEnabled = isLicensed,
                validateHex = false,
                detectedLanguage = { (verdict as? ProjectLanguageVerdict.Detected)?.languageId },
                verdict = { verdict },
            ),
        )

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
        return projectModel
            .snapshot()
            .firstOrNull { it.canonicalPath == projectKey }
            ?.hex
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

    internal fun setPendingFallbackAccent(
        projectKey: String,
        hex: String?,
    ) {
        if (hex == null) {
            pendingFallbackAccents.remove(projectKey)
        } else {
            normalizedFallbackAccent(projectKey, hex)?.let { (key, value) ->
                pendingFallbackAccents[key] = value
            }
        }
        fireChanged()
    }

    internal fun setPendingForcedLanguage(
        projectKey: String,
        languageId: String?,
    ) {
        require(projectKey.isNotBlank()) { "projectKey must not be blank" }
        val normalized = normalizeLanguageId(languageId)
        if (normalized == null) {
            pendingForcedLanguages.remove(projectKey)
        } else {
            pendingForcedLanguages[projectKey] = normalized
        }
        fireChanged()
    }

    @org.jetbrains.annotations.TestOnly
    internal fun setPendingLanguageFallbackAccent(hex: String?) {
        pendingLanguageFallbackAccent = normalizedLanguageFallbackAccent(hex, warn = LOG::warn)
        refreshResolutionPanel()
        fireChanged()
    }

    @org.jetbrains.annotations.TestOnly
    internal fun seedResolutionOverridesForTest(
        fallbackAccents: Map<String, String> = emptyMap(),
        forcedLanguages: Map<String, String> = emptyMap(),
        languageFallbackAccent: String? = null,
    ) {
        pendingFallbackAccents.clear()
        pendingFallbackAccents.putAll(normalizedFallbackAccents(fallbackAccents))
        pendingForcedLanguages.clear()
        pendingForcedLanguages.putAll(normalizedForcedLanguages(forcedLanguages))
        pendingLanguageFallbackAccent = normalizedLanguageFallbackAccent(languageFallbackAccent)
    }

    @org.jetbrains.annotations.TestOnly
    internal fun fallbackAccentsForTest(): Map<String, String> = pendingFallbackAccents.toMap()

    @org.jetbrains.annotations.TestOnly
    internal fun forcedLanguagesForTest(): Map<String, String> = pendingForcedLanguages.toMap()

    @org.jetbrains.annotations.TestOnly
    internal fun languageFallbackAccentForTest(): String? = pendingLanguageFallbackAccent

    /**
     * Legacy-named seam: lazily builds the diagnostics panel on first call,
     * refreshes it from [ProjectLanguageDetector.verdict], and returns
     * `(icon, text, tooltip)` triples for each label in layout order.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun proportionsPanelLabelsForTest(): List<Triple<Icon?, String, String?>> {
        val panel = proportionsPanel ?: createResolutionPanel().also { proportionsPanel = it }
        refreshResolutionPanel()
        return panel.labelsForTest()
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
        pendingFallbackAccents.clear()
        pendingFallbackAccents.putAll(normalizedFallbackAccents(state.projectFallbackAccents, warn = LOG::warn))
        pendingForcedLanguages.clear()
        pendingForcedLanguages.putAll(normalizedForcedLanguages(state.forcedProjectLanguages, warn = LOG::warn))
        pendingLanguageFallbackAccent = normalizedLanguageFallbackAccent(state.languageFallbackAccent, warn = LOG::warn)
        captureStoredSnapshot()
    }

    private val fireChanged: () -> Unit = { listeners.forEach { it.run() } }

    private fun captureStoredSnapshot() {
        storedProjects = projectModel.snapshot().map { ProjectMapping(it.canonicalPath, it.displayName, it.hex) }
        storedLanguages = languageModel.snapshot().map { LanguageMapping(it.languageId, it.displayName, it.hex) }
        storedFallbackAccents = pendingFallbackAccents.toMap()
        storedForcedLanguages = pendingForcedLanguages.toMap()
        storedLanguageFallbackAccent = pendingLanguageFallbackAccent
    }

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

/**
 * Isolated state access wrapper so test code can substitute a synthetic state
 * without pulling in the full [AccentMappingsSettings] service graph.
 */
private object AccentMappingsSettingsAccess {
    fun stateFor(): AccentMappingsState = AccentMappingsSettings.getInstance().state
}

private fun storedCurrentVariantAccentHex(): String? =
    AyuVariant
        .detect()
        ?.let { variant -> AyuIslandsSettings.getInstance().getAccentForVariant(variant) }

private fun List<ProjectMapping>.toFingerprint(): Set<Triple<String, String, String>> =
    map { Triple(it.canonicalPath, it.displayName, it.hex) }.toSet()

private fun List<LanguageMapping>.toLanguageFingerprint(): Set<Triple<String, String, String>> =
    map { Triple(it.languageId, it.displayName, it.hex) }.toSet()

private fun normalizedFallbackAccents(
    entries: Map<String, String>,
    warn: (String) -> Unit = {},
): Map<String, String> =
    entries
        .mapNotNull { (projectKey, hex) -> normalizedFallbackAccent(projectKey, hex, warn) }
        .toMap()

private fun normalizedLanguageFallbackAccent(
    hex: String?,
    warn: (String) -> Unit = {},
): String? =
    hex
        ?.takeIf { it.isNotBlank() }
        ?.let { rawHex ->
            AccentHex.of(rawHex)?.value ?: run {
                warn("Dropping malformed language fallback override accent")
                null
            }
        }

private fun normalizedFallbackAccent(
    projectKey: String,
    hex: String,
    warn: (String) -> Unit = {},
): Pair<String, String>? {
    if (projectKey.isBlank()) {
        warn("Dropping malformed project fallback override row: blank project key")
        return null
    }
    val normalizedHex =
        AccentHex.of(hex)?.value ?: run {
            warn("Dropping malformed project fallback override row (key='$projectKey')")
            return null
        }
    return projectKey to normalizedHex
}

private fun normalizedForcedLanguages(
    entries: Map<String, String>,
    warn: (String) -> Unit = {},
): Map<String, String> =
    entries
        .mapNotNull { (projectKey, languageId) ->
            normalizedForcedLanguage(projectKey, languageId, warn)
        }.toMap()

private fun normalizedForcedLanguage(
    projectKey: String,
    languageId: String,
    warn: (String) -> Unit = {},
): Pair<String, String>? {
    if (projectKey.isBlank()) {
        warn("Dropping malformed forced language override row: blank project key")
        return null
    }
    val normalizedLanguageId =
        normalizeLanguageId(languageId) ?: run {
            warn("Dropping malformed forced language override row (key='$projectKey')")
            return null
        }
    return projectKey to normalizedLanguageId
}

private fun normalizeLanguageId(languageId: String?): String? =
    languageId?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
