package dev.ayuislands.studio.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import dev.ayuislands.accent.AccentDetectorLookup
import dev.ayuislands.accent.AccentFallback
import dev.ayuislands.accent.AccentHexPolicy
import dev.ayuislands.accent.AccentResolutionRequest
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ChromeDecorationsProbe
import dev.ayuislands.accent.PersistedAccentMappingsView
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.font.FontPanelResources
import dev.ayuislands.font.FontTerminalAction
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AccentPanelAction
import dev.ayuislands.settings.AccentPanelContext
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.settings.ContextLanguageController
import dev.ayuislands.settings.GlowPanelEvent
import dev.ayuislands.settings.PluginTarget
import dev.ayuislands.settings.PluginsPanelEvent
import dev.ayuislands.settings.SettingsOpenContext
import dev.ayuislands.settings.SyntaxCapabilityController
import dev.ayuislands.settings.SyntaxCapabilityState
import dev.ayuislands.settings.SyntaxLanguageResolver
import dev.ayuislands.settings.SyntaxPanelEvent
import dev.ayuislands.settings.VcsPanelEvent
import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.mappings.MappingsContext
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.settings.mappings.MappingsPanelAction
import dev.ayuislands.settings.mappings.ProjectLanguageResolutionPanel
import dev.ayuislands.settings.observeProjectLanguage
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioFontRequest
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioAccentProjection
import dev.ayuislands.studio.session.StudioFontProjection
import dev.ayuislands.studio.session.StudioFonts
import dev.ayuislands.studio.session.StudioGlowProjection
import dev.ayuislands.studio.session.StudioSettingsProjection
import dev.ayuislands.studio.session.StudioTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns only native observations, detached previews and subscriptions for one visible Settings view. */
internal class StudioSessionPresentation private constructor(
    private val opened: SettingsOpenContext,
    private val opening: StudioSettingsBinding,
    private var preview: StudioSettingsPreview,
    private val changed: () -> Unit,
) : StudioSectionActions,
    Disposable {
    private val job = SupervisorJob(opening.scope.coroutineContext[Job])
    private val scope = CoroutineScope(opening.scope.coroutineContext + job + Dispatchers.EDT)
    private var refreshJob: Job? = null
    private var requestedResources = preview.fonts.resources
    private var isDisposed = false
    private var language = "Kotlin"
    private var capability: SyntaxCapabilityState? = null
    private val languageContext = ContextLanguageController(SyntaxLanguageResolver()::resolve, ::observeProjectLanguage)
    private val capabilityController =
        SyntaxCapabilityController(opened.project, rendered = { observed ->
            capability = observed
            if (!isDisposed) changed()
        })
    private val metadata = StudioUiMetadata(opening)
    private val actions = StudioNativeActions(opened, opening, ::context, ::appearance, ::shared)

    init {
        Disposer.register(this, capabilityController)
        language = languageContext.start(opened.project, opened.activeFile, language, ::selectLanguage)
        capabilityController.selectLanguage(language)
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { refresh() })
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { refresh() })
    }

    fun context(): StudioSettingsContext {
        val resources = opening.binding.document.auxiliary.resources
        if (!isDisposed && resources != requestedResources) refresh()
        val selected = appearance()
        val glow = StudioGlowProjection.settings(selected)
        val preferences = shared()
        val entitlement = opening.entitlement()
        val hasCustomHeader = ChromeDecorationsProbe.isCustomHeaderActive()
        val conflicts =
            ConflictRegistry
                .detectConflicts()
                .flatMap { entry ->
                    entry.affectedElements.map { it to entry }
                }.toMap()
        return StudioSettingsContext(
            entitlement,
            AccentPanelContext(
                preview.variant,
                SystemInfo.isMac,
                SystemInfo.isMac,
                AyuVariant.isIslandsUi(),
                hasCustomHeader,
                if (hasCustomHeader) null else "Unavailable with the current native window decorations.",
                conflicts,
                glowWidth = if (glow.enabled) glow.width.getValue(glow.style) else null,
            ),
            fonts(selected, preferences),
            preview.editor,
            mappings(selected, preferences, entitlement),
            buildSet {
                if (ConflictRegistry.isCodeGlanceProDetected()) add(PluginTarget.CODE_GLANCE)
                if (ConflictRegistry.isIndentRainbowDetected()) add(PluginTarget.INDENT_RAINBOW)
            },
            capability ?: SyntaxCapabilityState.SupportUnavailable(language),
        )
    }

    fun selectSection(section: StudioSettingsSection) {
        if (!isDisposed) metadata.select(section)
    }

    override fun accent(action: AccentPanelAction): AccentPanelEvent? = if (isDisposed) null else actions.accent(action)

    override fun mapping(action: MappingsPanelAction): MappingsEdit? = if (isDisposed) null else actions.mapping(action)

    override fun font(action: FontTerminalAction) {
        if (!isDisposed) actions.font(action)
    }

    override fun glow(action: GlowPanelEvent) {
        if (!isDisposed) actions.glow(action)
    }

    override fun vcs(action: VcsPanelEvent) {
        if (!isDisposed) actions.vcs(action)
    }

    override fun workspace(action: WorkspacePanelEvent) {
        if (!isDisposed) actions.workspace(action)
    }

    override fun plugins(action: PluginsPanelEvent): PluginsPanelEvent? =
        if (isDisposed) null else actions.plugins(action)

    override fun syntax(action: SyntaxPanelEvent) {
        if (isDisposed) return
        when (action) {
            is SyntaxPanelEvent.Language -> {
                languageContext.select(action.value)
                selectLanguage(action.value)
            }
            SyntaxPanelEvent.RecoverSupport -> capabilityController.performRecoveryAction()
            else -> actions.syntax(action)
        }
    }

    override fun dispose() {
        isDisposed = true
        job.cancel()
        languageContext.dispose()
    }

    private fun selectLanguage(selected: String) {
        if (isDisposed) return
        language = selected
        capability = null
        capabilityController.selectLanguage(selected)
        changed()
    }

    private fun refresh() {
        scope.launch {
            if (isDisposed) return@launch
            refreshJob?.cancel()
            refreshJob = currentCoroutineContext()[Job]
            requestedResources = opening.binding.document.auxiliary.resources
            val resources = requestedResources
            try {
                preview = StudioSettingsPreview.capture(resources)
                changed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                LOG.warn("Studio preview observations could not refresh", failure)
                changed()
            }
        }
    }

    private fun appearance(): AppearanceSnapshot {
        val state = opening.binding.controller.state
        val work = state.record?.work
        return work?.drafts?.singleOrNull { it.id == work.selected }?.appearance
            ?: requireNotNull(StudioTargets.current(opening.binding.document, state.identity.target)).appearance
    }

    private fun shared(): SharedPreferences {
        val document = opening.binding.document
        val changes =
            opening.binding.controller.state.record
                ?.work
                ?.shared ?: return document.shared
        return StudioSettingsProjection.shared(document.shared, changes)
    }

    private fun fonts(
        selected: AppearanceSnapshot,
        preferences: SharedPreferences,
    ): FontPanelResources {
        val request = StudioFontProjection.request(selected)
        val settings = StudioFontProjection.selection(selected).settings
        val terminal =
            preferences.system.records
                .single { it.key.name == "fontInstallTerminal" }
                .value
        return FontPanelResources(
            StudioFonts.availability(request, opening.binding.document.auxiliary.resources, preview.fonts),
            preview.monospaceFamilies,
            SystemInfo.isMac,
            (terminal as? RawValue.Text)?.value,
            request is StudioFontRequest.Enabled && settings?.preset?.isCurated == true,
        )
    }

    private fun mappings(
        selected: AppearanceSnapshot,
        preferences: SharedPreferences,
        entitlement: LicenseEntitlement,
    ): MappingsContext {
        val project = opened.project?.takeUnless { it.isDisposed || it.isDefault }
        val key = project?.let(AccentResolver::projectKey)
        val values = StudioAccentProjection.mappings(preferences.mappings)
        val view = PersistedAccentMappingsView { values }
        val fallback =
            StudioAccentEditing.settings(selected, preferences).colors[preview.variant] ?: preview.variant.defaultAccent
        val lookup = AccentDetectorLookup.CacheOnlyLookup
        val resolved =
            if (entitlement == LicenseEntitlement.LICENSED) {
                LicenseChecker.withConfirmedEntitlement(entitlement) {
                    AccentResolver.resolve(
                        project,
                        AccentResolutionRequest(view, lookup, AccentHexPolicy.STRICT),
                        AccentFallback.Global(fallback),
                    )
                }
            } else {
                null
            }
        val canMutate = entitlement == LicenseEntitlement.LICENSED && key != null
        return MappingsContext(
            key,
            resolved?.hex ?: fallback,
            canMutate,
            ProjectLanguageResolutionPanel.State(
                project?.let { ProjectLanguageDetector.verdict(it) } ?: ProjectLanguageVerdict.Unavailable,
                key?.let(view::forcedLanguageId),
                key?.let(view::projectFallbackAccent),
                resolved?.source ?: AccentResolver.Source.GLOBAL,
                canMutate,
                canMutate,
            ),
        )
    }

    companion object {
        private val LOG = logger<StudioSessionPresentation>()

        suspend fun create(
            opened: SettingsOpenContext,
            opening: StudioSettingsBinding,
            changed: () -> Unit,
        ): StudioSessionPresentation {
            val resources = withContext(Dispatchers.EDT) { opening.binding.document.auxiliary.resources }
            val preview = StudioSettingsPreview.capture(resources)
            return withContext(Dispatchers.EDT) { StudioSessionPresentation(opened, opening, preview, changed) }
        }
    }
}
