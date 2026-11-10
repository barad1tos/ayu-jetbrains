package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.rotation.RotationTransition
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.runtime.RuntimeAcceptanceRequest
import dev.ayuislands.studio.runtime.RuntimePreparationPlan
import dev.ayuislands.studio.runtime.StudioRuntimeCoordinator
import dev.ayuislands.studio.runtime.StudioRuntimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** Joins existing session I/O and runtime ownership before the shared coordinator journals effects. */
internal class StudioNativeEnvironment(
    private val runtime: StudioRuntimeCoordinator,
    private val source: StudioRuntimeSource,
    private val session: StudioSessionIo,
    private val native: StudioNativeHost,
    private val transitions: (RotationTransition) -> Unit,
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) : StudioApplyEnvironment {
    override suspend fun publish(update: StudioApplyUpdate) {
        session.publish(update)
        withContext(nativeContext) { transitions(runtime.observe(update, source.clock())) }
    }

    override suspend fun flush(record: StudioSessionRecord): DraftWriteResult = session.flush(record)

    override suspend fun observe(
        request: AcceptanceRequest,
        document: StudioDocument,
    ): AcceptanceContext = session.observe(request, document)

    override suspend fun prepareAcceptance(
        request: AcceptanceRequest,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation =
        withContext(nativeContext) {
            val observed = native.observe()
            if (observed.entitlement == LicenseEntitlement.UNKNOWN) return@withContext unconfirmed()
            if (request.iconPin != null && observed.entitlement != LicenseEntitlement.LICENSED) {
                return@withContext StudioAcceptancePreparation.Blocked(ApplyFailure("icon-pin-requires-pro"))
            }
            runtime.observeFocus(observed.focus.runtime)
            val prepared = RuntimeAcceptanceRequest(source.operationId(), request, plan)
            val clock = source.clock()
            val decision = runtime.prepare(prepared, clock)
            val result =
                if (decision is RuntimePreparationPlan.RandomRequired) {
                    runtime.prepare(prepared, clock, source.randomColor(decision.request.variant))
                } else {
                    decision
                }
            when (result) {
                is RuntimePreparationPlan.Ready -> {
                    acquire(
                        result.plan,
                        observed,
                        StudioNativeAdmission.User(request.transactionId),
                    )
                }

                is RuntimePreparationPlan.Blocked -> {
                    StudioAcceptancePreparation.Blocked(result.failure)
                }

                is RuntimePreparationPlan.RandomRequired -> {
                    error("Random preparation did not consume its supplied choice")
                }
            }
        }

    override suspend fun acquireRuntime(
        operationId: UUID,
        snapshot: RotationSnapshot,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation =
        withContext(nativeContext) {
            val observed = native.observe()
            if (observed.entitlement == LicenseEntitlement.UNKNOWN) return@withContext unconfirmed()
            acquire(plan, observed, StudioNativeAdmission.Automatic(operationId, snapshot.inputs.owner))
        }

    override suspend fun resume(journal: PendingJournal): StudioRuntimeLease =
        withContext(nativeContext) {
            recoveredNativeLease(native.recoveryFacts())
        }

    private fun acquire(
        plan: AcceptancePlan.Commit,
        observed: StudioNativeObservation,
        admission: StudioNativeAdmission,
    ): StudioAcceptancePreparation.Prepared {
        val lease = StudioNativeLease(native, observed, admission, runtime, source, transitions, nativeContext)
        lease.acquire()
        return StudioAcceptancePreparation.Prepared(lease, plan)
    }

    private fun unconfirmed() = StudioAcceptancePreparation.Blocked(ApplyFailure("native-entitlement-unconfirmed"))
}
