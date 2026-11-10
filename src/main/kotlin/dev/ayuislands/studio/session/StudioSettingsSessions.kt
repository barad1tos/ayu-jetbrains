package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRestore
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.SessionIdentity
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal sealed interface StudioSettingsOpening {
    data class Ready(
        val binding: StudioSessionBinding,
    ) : StudioSettingsOpening

    data class Unavailable(
        val code: String,
    ) : StudioSettingsOpening
}

internal sealed interface StudioDraftChoice {
    data object KeepForLater : StudioDraftChoice

    data class Restore(
        val id: String,
    ) : StudioDraftChoice

    data class Discard(
        val id: String,
    ) : StudioDraftChoice
}

/** Retains one captured Settings opening; bindings and writes remain with the application's existing owner. */
internal class StudioSettingsSessions(
    private val sessions: StudioSessions,
    private val resource: (StudioSessionBinding, FontJob) -> Unit,
    private val ids: () -> String,
    private val chooseRecovery: (List<StudioSessionRecord>) -> StudioDraftChoice,
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) {
    private var pendingRestore: DraftRestore? = null

    suspend fun open(
        target: TargetObservation,
        restoreSource: String? = null,
    ): StudioSettingsOpening =
        withContext(nativeContext) {
            if (target.presence == TargetPresence.UNAVAILABLE ||
                StudioTargets.current(sessions.document, target.target) == null
            ) {
                StudioSettingsOpening.Unavailable("target-unavailable")
            } else if (pendingRestore != null || restoreSource != null) {
                restore(target, pendingRestore?.source ?: requireNotNull(restoreSource))
            } else {
                chooseOpening(target)
            }
        }

    private suspend fun chooseOpening(target: TargetObservation): StudioSettingsOpening {
        var opening: StudioSettingsOpening? = null
        while (opening == null) {
            val offers = sessions.recoveryOffers().filter { it.target == target.target }
            val choice = if (offers.isEmpty()) StudioDraftChoice.KeepForLater else chooseRecovery(offers)
            opening =
                when (choice) {
                    StudioDraftChoice.KeepForLater -> fresh(target)
                    is StudioDraftChoice.Restore -> restore(target, choice.id)
                    is StudioDraftChoice.Discard ->
                        when (val result = sessions.discardRecord(choice.id)) {
                            is DraftWriteResult.Saved -> null
                            is DraftWriteResult.Failed -> StudioSettingsOpening.Unavailable(result.code)
                        }
                }
        }
        return opening
    }

    private suspend fun restore(
        target: TargetObservation,
        source: String,
    ): StudioSettingsOpening {
        val request = pendingRestore ?: DraftRestore(source, ids(), ids(), target).also { pendingRestore = it }
        return when (val result = sessions.restoreRecord(request)) {
            is DraftWriteResult.Failed -> StudioSettingsOpening.Unavailable(result.code)
            is DraftWriteResult.Saved -> {
                val record = sessions.document.sessions.singleOrNull { it.id == request.session }
                if (record == null) {
                    StudioSettingsOpening.Unavailable("restore-unconfirmed")
                } else {
                    pendingRestore = null
                    bind(target, record)
                }
            }
        }
    }

    private suspend fun fresh(target: TargetObservation): StudioSettingsOpening {
        val record =
            StudioSessionRecords.current(
                sessions.document,
                target.target,
                draftId = DraftId(ids()),
                sessionId = ids(),
            ) ?: return StudioSettingsOpening.Unavailable("target-unavailable")
        return bind(target, record)
    }

    private suspend fun bind(
        target: TargetObservation,
        record: StudioSessionRecord,
    ): StudioSettingsOpening.Ready {
        val initial =
            StudioSessionState(SessionIdentity(record.id, record.target, target.generation), null, SessionPhase.Loading)
        lateinit var binding: StudioSessionBinding
        binding = sessions.bind(initial, loadRecord = { record }, resource = { job -> resource(binding, job) })
        binding.load()
        return StudioSettingsOpening.Ready(binding)
    }
}
