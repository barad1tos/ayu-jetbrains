package dev.ayuislands.studio.ui

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontPanelEvent
import dev.ayuislands.settings.SettingsBadgeState
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RecoveryNotice
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.model.StudioSettingsView
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.session.StudioProfileProjection
import dev.ayuislands.studio.session.StudioSessionBinding
import dev.ayuislands.studio.session.StudioSettingsProjection
import dev.ayuislands.studio.session.StudioTargets
import kotlinx.coroutines.CancellationException
import javax.swing.JComponent

/** Public Settings lifecycle; native views never own accepted state, recovery work, or resource jobs. */
internal class StudioSettingsHost(
    private val binding: StudioSessionBinding,
    private val context: () -> StudioSettingsContext,
    private val dialogs: StudioHostDialogs,
    private val actions: StudioSectionActions,
    private val sectionChanged: (StudioSettingsSection) -> Unit = {},
    private val metadata: ((StudioMetadataCommand) -> Unit)? = null,
) : UnnamedConfigurable {
    private var view: SettingsNativeView? = null
    private var section = storedSection()

    override fun createComponent(): JComponent {
        view?.let { return it.panel }
        val content = StudioSettingsContent(binding.document, context(), ::stage, ::resource, actions)
        val panel =
            StudioSettingsPanel(
                content.sections,
                ::select,
                { mode -> stage(listOf(SessionIntent.Preview(mode))) },
                { selected ->
                    section = selected
                    sectionChanged(selected)
                },
                ::profileMenu,
            )
        view = SettingsNativeView(content, panel)
        return panel
    }

    override fun reset() {
        val current = requireNotNull(view) { "Create the Settings view before resetting it" }
        if (current.isNew) {
            val phase = binding.controller.state.phase
            if (phase == SessionPhase.Loading || phase == SessionPhase.Retired) modal { binding.load() }
            current.isNew = false
        } else {
            binding.controller.dispatch(SessionEvent.Intent(SessionIntent.Reset))
        }
        binding.controller.attach(::render)
    }

    override fun isModified(): Boolean = StudioSettingsProjection.isModified(binding.controller.state)

    /** Refreshes native observations without fabricating a session edit or observation. */
    fun refresh() = render(binding.controller.state)

    override fun apply() {
        val phase = binding.controller.state.phase
        val event =
            if (phase is SessionPhase.Processing && phase.stage == OperationStage.RECOVERY_REQUIRED) {
                SessionEvent.RetryRecovery(phase.operation)
            } else {
                SessionEvent.AcceptRequested(binding.nextId())
            }
        binding.controller.dispatch(event)
        while (binding.controller.state.phase is SessionPhase.Reviewing) review()
        if (binding.controller.state.notice == SessionNotice.NoChanges) return
        if (binding.controller.state.phase !is SessionPhase.Processing) {
            throw ConfigurationException("The requested settings need attention before they can be applied.")
        }
        val result =
            try {
                modal { binding.execute() }
            } catch (_: CancellationException) {
                binding.lastOutcome
            }
        when (result) {
            is AcceptanceResult.Accepted, AcceptanceResult.NoChanges -> {
                return
            }

            is AcceptanceResult.AcceptedCleanupPending -> {
                throw ConfigurationException("Your settings were saved. Their remaining effects still need to settle.")
            }

            is AcceptanceResult.RecoveryRequired -> {
                throw ConfigurationException(
                    "The interrupted change needs recovery. Click Apply to retry recovery.",
                )
            }

            is AcceptanceResult.NotAccepted -> {
                val message =
                    if (RecoveryNotice.PANEL_GEOMETRY_KEPT in result.notices) {
                        "Your saved settings were preserved. " +
                            "Panel sizes kept by the IDE after its restart were left unchanged. Your draft is retained."
                    } else {
                        "The requested change was not accepted. Your draft is retained."
                    }
                throw ConfigurationException(message)
            }

            else -> {
                throw ConfigurationException("The requested change was not accepted. Your draft is retained.")
            }
        }
    }

    override fun cancel() {
        binding.controller.dispatch(SessionEvent.HostClose(CloseRequest.CANCEL))
    }

    override fun disposeUIResources() {
        binding.controller.detach()
        val disposed = view ?: return
        view = null
        Disposer.dispose(disposed.content)
        disposed.panel.dispose()
        disposed.panel.removeAll()
    }

    private fun render(state: StudioSessionState) {
        val current = view ?: return
        val observation = context()
        val document = binding.document
        current.content.render(document, state, observation, binding.observation.authority)
        val accepted = StudioTargets.current(document, state.identity.target) ?: return
        current.panel.render(
            StudioSettingsView(
                StudioProfileProjection.project(document, state),
                section,
                binding.observation.authority,
                StudioSettingsSamples(observation).accent(accepted.appearance, document.shared),
            ),
        )
        refreshBadges(current.panel)
    }

    private fun refreshBadges(panel: StudioSettingsPanel) {
        val submit = metadata ?: return
        panel.refreshBadges(
            readState = {
                val view =
                    checkNotNull(
                        StudioLegacyProjection.project(binding.document, binding.controller.state.identity.target),
                    )
                SettingsBadgeState.from(view.settings)
            },
            acknowledge = { ids -> submit(StudioMetadataCommand.BadgesSeen(StudioList(ids))) },
        )
    }

    private fun storedSection(): StudioSettingsSection {
        val stored =
            binding.document.auxiliary.ui.records
                .single { it.key.name == "settingsSelectedTab" }
                .value
        val index = (stored as? RawValue.Integer)?.value ?: return StudioSettingsSection.ACCENT
        return StudioSettingsSection.entries.getOrNull(index) ?: StudioSettingsSection.ACCENT
    }

    private fun stage(intents: List<SessionIntent>) {
        intents.forEach { binding.controller.dispatch(SessionEvent.Intent(it)) }
    }

    private fun select(choice: StudioProfileChoice) {
        val intent =
            when (choice) {
                is StudioProfileChoice.Profile -> SessionIntent.Browse(choice.id, DraftId(binding.nextId()))
                is StudioProfileChoice.Draft -> SessionIntent.EditDraft(choice.id)
            }
        stage(listOf(intent))
    }

    private fun resource(event: FontPanelEvent.Resource) {
        val record = binding.controller.state.record ?: return
        val entry = requireNotNull(FontCatalog.forPreset(event.preset))
        val job =
            FontJob(OperationStamp(record.id, record.generation, binding.nextId()), entry.familyName, event.action)
        stage(listOf(SessionIntent.Resource(job)))
    }

    private fun profileMenu() {
        val current = view ?: return
        StudioProfileMenu(binding, dialogs).show(current.panel)
    }

    private fun review() {
        val state = binding.controller.state
        val phase = state.phase as? SessionPhase.Reviewing ?: return
        val dialog = StudioReviewDialog(phase.items, requireNotNull(state.record), binding.document)
        val confirmed = dialogs.review(dialog)
        when (val decision = dialog.decision) {
            is StudioReviewDecision.Continue -> {
                if (!confirmed) cancelReview()
                stage(decision.intents)
                binding.controller.dispatch(SessionEvent.ReviewResolved(binding.nextId(), decision.choices))
            }

            is StudioReviewDecision.Edit -> {
                stage(listOf(SessionIntent.CancelReview, SessionIntent.EditDraft(decision.draft)))
                throw ConfigurationException("Review the selected draft before applying.")
            }

            StudioReviewDecision.Cancel -> {
                cancelReview()
            }
        }
    }

    private fun cancelReview(): Nothing {
        stage(listOf(SessionIntent.CancelReview))
        throw ConfigurationException("Review cancelled. Your draft is retained.")
    }

    private fun <T> modal(action: suspend () -> T): T =
        runWithModalProgressBlocking(
            ModalTaskOwner.component(requireNotNull(view).panel),
            "Ayu Islands",
        ) { action() }
}

private class SettingsNativeView(
    val content: StudioSettingsContent,
    val panel: StudioSettingsPanel,
    var isNew: Boolean = true,
)
