package dev.ayuislands.studio.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.ayuislands.font.FontPanelEvent
import dev.ayuislands.font.FontPanelView
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AccentPanelAction
import dev.ayuislands.settings.AccentPanelGroup
import dev.ayuislands.settings.AccentPanelView
import dev.ayuislands.settings.AccentPreviewInput
import dev.ayuislands.settings.GlowPanelView
import dev.ayuislands.settings.PluginTarget
import dev.ayuislands.settings.PluginsAvailability
import dev.ayuislands.settings.PluginsPanelView
import dev.ayuislands.settings.SyntaxPanelView
import dev.ayuislands.settings.VcsPanelView
import dev.ayuislands.settings.VcsSection
import dev.ayuislands.settings.WorkspacePanelView
import dev.ayuislands.settings.mappings.MappingsObservations
import dev.ayuislands.settings.mappings.MappingsPanelView
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFontRequest
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.session.SessionEdits
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioFontProjection
import dev.ayuislands.studio.session.StudioGlowProjection
import dev.ayuislands.studio.session.StudioMappingsEditing
import dev.ayuislands.studio.session.StudioPluginsEditing
import dev.ayuislands.studio.session.StudioSettingsProjection
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.studio.session.StudioTargets
import dev.ayuislands.studio.session.StudioVcsProjection
import dev.ayuislands.studio.session.StudioWorkspaceEditing

/** One retained instance of every actual section. Rendering only updates detached samples and controls. */
internal class StudioSettingsContent(
    initial: StudioDocument,
    context: StudioSettingsContext,
    private val stage: (List<SessionIntent>) -> Unit,
    resource: (FontPanelEvent.Resource) -> Unit,
    private val actions: StudioSectionActions,
) : Disposable {
    private val syntaxColors = StudioSyntaxColors()
    private val currentSamples = StudioSettingsSamples(context, syntaxColors)
    private val draftSamples = StudioSettingsSamples(context, syntaxColors)
    private val accent = StudioAccentSection(expandedAccent(initial), stage, ::accentAction)
    private val font = StudioFontSection(stage, resource, actions::font)
    private val glow = StudioGlowSection(stage, actions::glow)
    private val syntax =
        StudioSyntaxSection(currentSamples.syntax(initial.currents.global.appearance), stage, actions::syntax)
    private val vcs =
        StudioVcsSection(
            currentSamples.vcs(initial.currents.global.appearance),
            expandedVcs(initial),
            stage,
            actions::vcs,
        )
    private val workspace = StudioWorkspaceSection(stage, actions::workspace)
    private val plugins = StudioPluginsSection(stage, ::pluginAction)
    private var rendered: ContentFrame? = null
    val sections =
        listOf(
            accent.content,
            font.content,
            glow.content,
            syntax.content,
            vcs.content,
            workspace.content,
            plugins.content,
        )

    init {
        Disposer.register(this, accent)
        Disposer.register(this, syntax)
    }

    fun render(
        document: StudioDocument,
        state: StudioSessionState,
        context: StudioSettingsContext,
        authority: AuthorityStatus,
    ) {
        val record = state.record ?: return
        val draft = record.work.drafts.single { it.id == record.work.selected }
        val shared = StudioSettingsProjection.shared(document.shared, record.work.shared)
        val current = requireNotNull(StudioTargets.current(document, record.target)).appearance
        currentSamples.updateContext(context)
        draftSamples.updateContext(context)
        val editable =
            authority == AuthorityStatus.READY &&
                SessionEdits.permits(state.phase, SessionIntent.Reapply(draft.id, StudioList(emptyList())))
        val frame = ContentFrame(draft, shared, context, editable)
        rendered = frame
        renderAccent(
            frame,
            draftSamples,
            AccentPreviewInput(
                currentSamples.accent(current, document.shared),
                StudioAccentEditing.settings(current, document.shared).elements.toggles,
            ),
        )
        renderFont(frame, currentSamples.font(current))
        glow.render(
            draft,
            GlowPanelView(
                StudioGlowProjection.settings(draft.appearance),
                draftSamples.glow(draft.appearance, shared),
                editable,
                frame.licensed,
                emptySet(),
            ),
            currentSamples.glow(current, document.shared),
        )
        syntax.render(
            draft,
            SyntaxPanelView(
                StudioSyntaxProjection.settings(draft.appearance),
                context.syntax.languageId,
                null,
                context.syntax,
                editable,
                frame.licensed,
            ),
            currentSamples.syntax(current),
            draftSamples.syntax(draft.appearance),
        )
        vcs.render(
            draft,
            VcsPanelView(StudioVcsProjection.settings(draft.appearance), editable, frame.licensed),
            currentSamples.vcs(current),
            draftSamples.vcs(draft.appearance),
        )
        val workspaceSettings = StudioWorkspaceEditing.settings(shared.workspace)
        workspace.render(
            shared.workspace,
            WorkspacePanelView(workspaceSettings, workspaceSettings, editable, frame.licensed),
            StudioWorkspaceEditing.settings(document.shared.workspace),
        )
        plugins.render(
            shared.plugins,
            PluginsPanelView(
                StudioPluginsEditing.settings(shared.plugins),
                PluginsAvailability(
                    frame.licensed,
                    PluginTarget.CODE_GLANCE in context.plugins,
                    PluginTarget.INDENT_RAINBOW in context.plugins,
                ),
                editable,
                emptySet(),
            ),
            currentSamples.plugins(current, document.shared),
            draftSamples.plugins(draft.appearance, shared),
        )
    }

    override fun dispose() {
        sections.forEach { it.component.parent?.remove(it.component) }
    }

    private fun renderAccent(
        frame: ContentFrame,
        samples: StudioSettingsSamples,
        currentPreview: AccentPreviewInput,
    ) {
        val settings = StudioAccentEditing.settings(frame.draft.appearance, frame.shared)
        val mappings =
            MappingsPanelView(
                StudioMappingsEditing.settings(frame.shared.mappings),
                frame.context.mappings,
                MappingsObservations(),
                frame.editable,
                frame.licensed,
            )
        val view = AccentPanelView(settings, frame.context.accent, mappings, frame.editable, frame.licensed)
        accent.render(
            frame.draft,
            frame.shared,
            view,
            currentPreview,
            AccentPreviewInput(samples.accent(frame.draft.appearance, frame.shared), settings.elements.toggles),
        )
    }

    private fun renderFont(
        frame: ContentFrame,
        current: dev.ayuislands.font.FontPanelPreview?,
    ) {
        val terminal =
            frame.shared.system.records
                .single { it.key.name == "fontInstallTerminal" }
        val selected = StudioFontProjection.selection(frame.draft.appearance)
        val resources = frame.context.font.copy(terminal = (terminal.value as? RawValue.Text)?.value)
        font.render(
            frame.draft,
            FontPanelView(
                selected,
                StudioFontProjection.request(frame.draft.appearance) != StudioFontRequest.Disabled,
                draftSamples.font(frame.draft.appearance),
                resources,
                frame.editable,
            ),
            current,
            terminal,
        )
    }

    private fun accentAction(action: AccentPanelAction) {
        if (action is AccentPanelAction.Mapping) {
            actions.mapping(action.action)?.let(accent::acceptMapping)
        } else {
            actions.accent(action)?.let(accent::accept)
        }
    }

    private fun pluginAction(event: dev.ayuislands.settings.PluginsPanelEvent) {
        val frame = rendered ?: return
        actions.plugins(event)?.let { completed ->
            val changes = StudioPluginsEditing.edits(frame.shared.plugins, completed)
            if (changes.isNotEmpty()) stage(changes)
        }
    }
}

private data class ContentFrame(
    val draft: DraftRecord,
    val shared: SharedPreferences,
    val context: StudioSettingsContext,
    val editable: Boolean,
) {
    val licensed: Boolean get() = context.entitlement == LicenseEntitlement.LICENSED
}

private fun expandedAccent(document: StudioDocument): Set<AccentPanelGroup> {
    val fields =
        document.auxiliary.ui.records
            .associate { it.key.name to it.value }
    return StudioUiMetadata.accentFields.filter { (_, field) -> fields[field] == RawValue.BooleanValue(true) }.keys
}

private fun expandedVcs(document: StudioDocument): Map<VcsSection, Boolean> {
    val fields =
        document.auxiliary.ui.records
            .associate { it.key.name to it.value }
    return StudioUiMetadata.vcsFields.mapValues { (_, field) -> fields[field] == RawValue.BooleanValue(true) }
}
