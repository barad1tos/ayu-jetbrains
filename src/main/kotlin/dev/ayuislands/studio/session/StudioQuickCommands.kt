package dev.ayuislands.studio.session

import dev.ayuislands.font.FontPanelEvent
import dev.ayuislands.font.FontPreset
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.settings.GlowPanelEvent
import dev.ayuislands.settings.PluginsPanelEvent
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.settings.mappings.MappingsTable
import dev.ayuislands.settings.mappings.ProjectMapping
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionHandling
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.StudioTarget

internal sealed interface StudioQuickCommand {
    data class Profile(
        val profile: ProfileId,
        val draft: DraftId,
    ) : StudioQuickCommand

    data class Accent(
        val event: AccentPanelEvent,
    ) : StudioQuickCommand

    data class GlowEnabled(
        val value: Boolean,
    ) : StudioQuickCommand

    data class ExternalAccent(
        val hex: String,
    ) : StudioQuickCommand

    data class Onboarding(
        val font: FontPreset,
        val glow: GlowPreset?,
    ) : StudioQuickCommand

    data object Unpin : StudioQuickCommand

    /** The captured project can acquire an automatic icon pin only while still unpinned. */
    data class PinIfAbsent(
        val hex: String,
        val displayName: String,
    ) : StudioQuickCommand

    /** The project key comes from the captured target, never a later focus observation. */
    data class Pin(
        val hex: String,
    ) : StudioQuickCommand
}

internal sealed interface QuickCommandPreparation {
    data class Ready(
        val request: AcceptanceRequest,
    ) : QuickCommandPreparation

    data class ReviewRequired(
        val items: StudioList<AcceptanceReview>,
    ) : QuickCommandPreparation

    data class Blocked(
        val reasons: StudioList<AcceptanceBlock>,
    ) : QuickCommandPreparation
}

/** Translates immediate gestures through the same field edits, draft transitions and acceptance policy as Settings. */
internal object StudioQuickCommands {
    /**
     * The application owner supplies a clean accepted-target record and current live-session observations.
     * Preparation is pure: it does not dispatch journal commands, publish a session or execute native effects.
     * The shared coordinator must revalidate and flush the returned request during actual admission.
     */
    fun prepare(
        command: StudioQuickCommand,
        state: StudioSessionState,
        inputs: SessionInputs,
        operationId: String,
    ): QuickCommandPreparation {
        if (operationId.isBlank() || !isEditable(state)) return invalid()
        val intents = edits(command, state, inputs) ?: return invalid()
        val edited = reduce(state, intents, inputs) ?: return invalid()
        val request =
            SessionTransitions.request(edited, operationId).copy(
                origin = AcceptanceOrigin.QUICK_SWITCHER,
                iconPin =
                    if (command is StudioQuickCommand.PinIfAbsent) {
                        (state.identity.target as? StudioTarget.Project)?.key
                    } else {
                        null
                    },
            )
        return when (val result = StudioAcceptancePlanner.plan(request, inputs.latest, inputs.context)) {
            is AcceptancePlanResult.Ready -> QuickCommandPreparation.Ready(request)
            is AcceptancePlanResult.ReviewRequired -> QuickCommandPreparation.ReviewRequired(result.items)
            is AcceptancePlanResult.Blocked ->
                if (result.reasons.singleOrNull() == AcceptanceBlock.FONT_REFRESH) {
                    QuickCommandPreparation.Ready(request)
                } else {
                    QuickCommandPreparation.Blocked(result.reasons)
                }
        }
    }

    private fun isEditable(state: StudioSessionState): Boolean {
        if (state.phase != SessionPhase.Editing) return false
        val record = state.record ?: return false
        return record.availability == SessionAvailability.LIVE &&
            record.id == state.identity.id &&
            record.target == state.identity.target &&
            !StudioAcceptancePlanner.hasPending(record.work)
    }

    private fun edits(
        command: StudioQuickCommand,
        state: StudioSessionState,
        inputs: SessionInputs,
    ): List<SessionIntent>? {
        val record = requireNotNull(state.record)
        val draft = record.work.drafts.singleOrNull { it.id == record.work.selected } ?: return null
        return when (command) {
            is StudioQuickCommand.PinIfAbsent -> {
                val target = state.identity.target as? StudioTarget.Project ?: return null
                StudioMappingsEditing.edits(
                    inputs.latest.shared.mappings,
                    MappingsEdit.AddProject(ProjectMapping(target.key.value, command.displayName, command.hex)),
                )
            }
            is StudioQuickCommand.ExternalAccent ->
                StudioPluginsEditing.edits(inputs.latest.shared.plugins, PluginsPanelEvent.Accent(command.hex))
            is StudioQuickCommand.Onboarding -> onboarding(draft, command)
            StudioQuickCommand.Unpin -> {
                val target = state.identity.target as? StudioTarget.Project ?: return null
                StudioMappingsEditing.edits(
                    inputs.latest.shared.mappings,
                    MappingsEdit.Remove(MappingsTable.PROJECTS, target.key.value),
                )
            }
            is StudioQuickCommand.Profile -> listOf(SessionIntent.Browse(command.profile, command.draft))
            is StudioQuickCommand.Accent -> StudioAccentEditing.edits(draft, inputs.latest.shared, command.event)
            is StudioQuickCommand.GlowEnabled -> StudioGlowEditing.edits(draft, GlowPanelEvent.Enable(command.value))
            is StudioQuickCommand.Pin -> {
                val target = state.identity.target as? StudioTarget.Project ?: return null
                StudioMappingsEditing.edits(
                    inputs.latest.shared.mappings,
                    MappingsEdit.Color(MappingsTable.PROJECTS, target.key.value, command.hex),
                )
            }
        }
    }

    private fun onboarding(
        draft: dev.ayuislands.studio.model.DraftRecord,
        command: StudioQuickCommand.Onboarding,
    ): List<SessionIntent> =
        buildList {
            addAll(StudioFontEditing.edits(draft, FontPanelEvent.Enable(true)))
            addAll(StudioFontEditing.edits(draft, FontPanelEvent.Preset(command.font)))
            command.glow?.let { preset ->
                addAll(StudioGlowEditing.edits(draft, GlowPanelEvent.Enable(true)))
                addAll(StudioGlowEditing.edits(draft, GlowPanelEvent.Preset(preset)))
            }
        }

    private fun reduce(
        initial: StudioSessionState,
        intents: List<SessionIntent>,
        inputs: SessionInputs,
    ): StudioSessionState? {
        var state = initial
        for (intent in intents) {
            val transition = StudioSessionReducer.reduce(state, SessionEvent.Intent(intent), inputs)
            if (transition.handling != SessionHandling.HANDLED) return null
            state = transition.state
        }
        return state
    }

    private fun invalid(): QuickCommandPreparation.Blocked =
        QuickCommandPreparation.Blocked(StudioList(listOf(AcceptanceBlock.INVALID_REQUEST)))
}
