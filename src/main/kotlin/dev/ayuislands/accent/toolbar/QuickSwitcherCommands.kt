package dev.ayuislands.accent.toolbar

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentDetectorLookup
import dev.ayuislands.accent.AccentFallback
import dev.ayuislands.accent.AccentHexPolicy
import dev.ayuislands.accent.AccentResolutionChain
import dev.ayuislands.accent.AccentResolutionChainBuilder
import dev.ayuislands.accent.AccentResolutionRequest
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.accent.PersistedAccentMappingsView
import dev.ayuislands.accent.SystemAccentProvider
import dev.ayuislands.accent.rethrowIfCancelled
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.persistence.StudioLegacyView
import dev.ayuislands.studio.session.StudioQuickCommand
import dev.ayuislands.studio.ui.StudioCommandFeedback

/** One popup captures one target incarnation; every read still comes from accepted Studio state. */
internal class QuickSwitcherCommands private constructor(
    val project: Project?,
    val context: AccentContext,
    private val target: TargetObservation,
    private val service: StudioService,
) {
    val document: StudioDocument? get() = service.document(target)

    val view: StudioLegacyView? get() = service.view(target)

    fun chain(): AccentResolutionChain? {
        val accepted = view ?: return null
        val settings = accepted.settings
        val fallback =
            when (val captured = context) {
                is AccentContext.Ayu -> {
                    val system = if (settings.effectiveFollowSystemAccent()) SystemAccentProvider.resolve() else null
                    val stored =
                        when (captured.ayuVariant) {
                            AyuVariant.MIRAGE -> settings.mirageAccent
                            AyuVariant.DARK -> settings.darkAccent
                            AyuVariant.LIGHT -> settings.lightAccent
                        }
                    AccentFallback.Global(system ?: stored ?: captured.ayuVariant.defaultAccent)
                }
                AccentContext.External ->
                    AccentFallback.External(
                        ExternalAccentSource.fromName(settings.externalThemeAccentSource),
                        settings.externalThemeAccent,
                    )
            }
        val request =
            AccentResolutionRequest(
                PersistedAccentMappingsView { accepted.mappings },
                AccentDetectorLookup.CacheOnlyLookup,
                AccentHexPolicy.STRICT,
            )
        return AccentResolutionChainBuilder.resolve(project, request, fallback)
    }

    fun accent(): String? =
        try {
            chain()?.winner?.hex
        } catch (failure: RuntimeException) {
            failure.rethrowIfCancelled()
            LOG.warn("Quick Switcher could not resolve the captured accent", failure)
            null
        }

    fun color(
        hex: String,
        accepted: () -> Unit = {},
    ) {
        val command =
            when (val captured = context) {
                is AccentContext.Ayu -> StudioQuickCommand.Accent(AccentPanelEvent.Color(captured.ayuVariant, hex))
                AccentContext.External -> StudioQuickCommand.ExternalAccent(hex)
            }
        submit(command, accepted)
    }

    fun togglePin(accepted: () -> Unit = {}) {
        if (target.target !is StudioTarget.Project) return
        val current = chain() ?: return
        val command =
            if (current.winner.source == AccentResolver.Source.PROJECT_OVERRIDE) {
                StudioQuickCommand.Unpin
            } else {
                StudioQuickCommand.Pin(current.winner.hex ?: return)
            }
        submit(command, accepted)
    }

    fun submit(
        command: StudioQuickCommand,
        accepted: () -> Unit = {},
    ) {
        try {
            service.submitQuick(target, command) { result -> StudioCommandFeedback.show(project, result, accepted) }
        } catch (failure: RuntimeException) {
            failure.rethrowIfCancelled()
            LOG.warn("Quick Switcher could not submit the captured command", failure)
        }
    }

    companion object {
        private val LOG = logger<QuickSwitcherCommands>()

        fun capture(
            project: Project? = AccentApplicator.resolveFocusedProject(),
            context: AccentContext? = AccentContext.detectQuickSwitcher(),
        ): QuickSwitcherCommands? {
            val capturedContext = context ?: return null
            val service = StudioService.getInstance()
            val target = service.capture(project) ?: return null
            if (service.view(target) == null) return null
            return QuickSwitcherCommands(project, capturedContext, target, service)
        }
    }
}
