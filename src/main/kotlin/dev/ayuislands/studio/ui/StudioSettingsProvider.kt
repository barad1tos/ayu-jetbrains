package dev.ayuislands.studio.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.EDT
import dev.ayuislands.settings.SettingsOpenContext
import dev.ayuislands.studio.StudioApplicationGraph
import dev.ayuislands.studio.StudioApplicationState
import dev.ayuislands.studio.StudioBootstrapResult
import dev.ayuislands.studio.captureStudioModality
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.StudioSettingsOpening
import dev.ayuislands.studio.session.StudioSettingsSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** One provider retains the captured target and an uncertain recovery transfer for one Settings opener. */
internal fun StudioApplicationGraph.settingsProvider(): suspend (SettingsOpenContext) -> StudioSettingsAccess =
    SettingsProvider(this)::open

private class SettingsProvider(
    private val graph: StudioApplicationGraph,
) {
    private var isCaptured = false
    private var target: TargetObservation? = null
    private var opener: StudioSettingsSessions? = null

    suspend fun open(context: SettingsOpenContext): StudioSettingsAccess =
        withContext(Dispatchers.EDT) {
            if (!isCaptured) {
                target = graph.projects.capture(context.project)
                isCaptured = true
            }
            val original = target ?: return@withContext unavailableProject()
            val observed = graph.projects.capture(context.project)
            if (!matches(original, observed)) return@withContext unavailableProject()
            when (val availability = graph.application.retry()) {
                is StudioApplicationState.Ready -> openReady(context, original, availability)
                is StudioApplicationState.Unavailable -> StudioSettingsAccess.Unavailable(reason(availability.reason))
                is StudioApplicationState.InitializationBlocked ->
                    StudioSettingsAccess.Unavailable(
                        "Ayu Islands could not finish opening your project settings. " +
                            "Your saved choices are preserved. Retry to continue.",
                    )
                StudioApplicationState.Loading ->
                    StudioSettingsAccess.Unavailable(
                        "Ayu Islands settings are still opening. Retry in a moment.",
                    )
                StudioApplicationState.Closed ->
                    StudioSettingsAccess.Unavailable(
                        "Ayu Islands is shutting down. Reopen settings after restarting the IDE.",
                    )
            }
        }

    private suspend fun openReady(
        context: SettingsOpenContext,
        original: TargetObservation,
        ready: StudioApplicationState.Ready,
    ): StudioSettingsAccess {
        val current = graph.projects.capture(context.project)
        if (!matches(original, current)) return unavailableProject()
        val sessionOpener =
            opener ?: StudioSettingsSessions(
                ready.sessions,
                resource = { binding, job -> graph.resources.start(context.project, binding, job) },
                ids = { UUID.randomUUID().toString() },
                chooseRecovery = { offers ->
                    val dialog = StudioDraftDialog(offers, context.project)
                    dialog.showAndGet()
                    dialog.choice
                },
            ).also { opener = it }
        return when (val opened = sessionOpener.open(requireNotNull(current))) {
            is StudioSettingsOpening.Ready ->
                StudioSettingsAccess.Ready(
                    StudioSettingsBinding(opened.binding, ::saveMetadata, graph.scope, graph::entitlement),
                )
            is StudioSettingsOpening.Unavailable ->
                StudioSettingsAccess.Unavailable(
                    "The saved work could not be opened safely. Your work has been preserved. Retry to continue.",
                )
        }
    }

    private fun saveMetadata(command: StudioMetadataCommand) {
        graph.scope.launch(captureStudioModality()) {
            val result = graph.submitMetadata(command)
            if (result !is AcceptanceResult.Accepted && result != AcceptanceResult.NoChanges) {
                Notifications.Bus.notify(
                    Notification(
                        "Ayu Islands",
                        "Settings choice could not be saved",
                        "Your appearance is unchanged. Reopen Ayu Islands settings to retry recovery, " +
                            "then choose the option again.",
                        NotificationType.WARNING,
                    ),
                )
            }
        }
    }

    private fun matches(
        original: TargetObservation,
        observed: TargetObservation?,
    ): Boolean =
        observed != null &&
            observed.target == original.target &&
            observed.generation == original.generation &&
            observed.presence != TargetPresence.UNAVAILABLE

    private fun unavailableProject() =
        StudioSettingsAccess.Unavailable(
            "The project that opened these settings is no longer available. " +
                "Close settings and reopen them from that project.",
        )

    private fun reason(result: StudioBootstrapResult): String =
        when (result) {
            is StudioBootstrapResult.LegacyReadOnlyRecovery ->
                "Your previous settings could not be imported. The original settings are preserved. " +
                    "Retry to finish importing them."
            is StudioBootstrapResult.Recovering ->
                "A previous appearance change needs to finish before settings can open. Retry to safely finish it."
            is StudioBootstrapResult.ReadOnly ->
                "The saved settings could not be opened. The existing files are preserved. Retry to open them again."
            is StudioBootstrapResult.Ready -> "Ayu Islands settings are ready. Retry to open them."
        }
}
