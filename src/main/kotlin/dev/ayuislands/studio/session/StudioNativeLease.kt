package dev.ayuislands.studio.session

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationTransition
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.runtime.StudioRuntimeCoordinator
import dev.ayuislands.studio.runtime.StudioRuntimeSource
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Retains actual native admission through partial acquisition, rollback and accepted owner cleanup. */
internal class StudioNativeLease(
    private val host: StudioNativeHost,
    private val initial: StudioNativeObservation,
    private val admission: StudioNativeAdmission,
    private val runtime: StudioRuntimeCoordinator,
    private val source: StudioRuntimeSource,
    private val transitions: (RotationTransition) -> Unit,
    private val nativeContext: CoroutineContext,
) : StudioRuntimeLease {
    override val glow = StudioGlowHolds(initial.managers)
    override val native = StudioNativeFacts(initial.entitlement == LicenseEntitlement.LICENSED, initial.process)
    override var accent: StudioAccentResult? = initial.accent
        private set
    override var observation: StudioNativeObservation = initial
        private set
    private var acquisitionFailure: RuntimeException? = null
    private var isReleased = false

    /** Even a failed acquisition returns this retained lease for the coordinator's cleanup path. */
    fun acquire() {
        try {
            glow.acquire()
        } catch (failure: RuntimeException) {
            acquisitionFailure = failure
        }
    }

    override suspend fun isValid(): Boolean =
        withContext(nativeContext) {
            if (!ownsAdmission()) return@withContext false
            matches(host.observe())
        }

    override suspend fun confirmAccent(result: StudioAccentResult) =
        withContext(nativeContext) {
            check(isValid()) { "Accent confirmation requires a valid native lease" }
            accent = result
        }

    override suspend fun confirmTheme(transition: StudioThemeTransition) =
        withContext(nativeContext) {
            check(ownsAdmission()) { "Theme confirmation requires active native admission" }
            check(
                observation.lookAndFeel === transition.before.lookAndFeel &&
                    observation.scheme === transition.before.scheme,
            ) { "Theme transition does not start from the held native appearance" }
            val observed = host.observe()
            check(matchesTheme(transition.after, observed) && sameAdmission(observed)) {
                "Theme transition does not match the current native admission"
            }
            observation =
                observation.copy(
                    lookAndFeel = observed.lookAndFeel,
                    scheme = observed.scheme,
                    variant = observed.variant,
                )
            accent = null
        }

    override suspend fun settledRuntime(candidate: StudioDocument): StudioRuntimeState =
        withContext(nativeContext) {
            when (val owner = admission) {
                is StudioNativeAdmission.User -> runtime.settledRuntime(owner.transactionId, candidate, source.clock())
                is StudioNativeAdmission.Automatic -> candidate.runtime
            }
        }

    override suspend fun release(accepted: StudioDocument?) =
        withContext(nativeContext) {
            if (isReleased) return@withContext
            glow.release()
            if (admission is StudioNativeAdmission.User) {
                transitions(runtime.release(admission.transactionId, accepted, source.clock()))
            }
            isReleased = true
        }

    private fun ownsAdmission(): Boolean {
        if (isReleased || acquisitionFailure != null || !glow.isActive) return false
        return ownsRuntime()
    }

    private fun ownsRuntime(): Boolean =
        when (val owner = admission) {
            is StudioNativeAdmission.User -> {
                runtime.isValid(owner.transactionId)
            }

            is StudioNativeAdmission.Automatic -> {
                val pending = runtime.state as? RotationRuntimeState.Applying
                pending?.operation?.operationId == owner.operationId && runtime.isCurrentOwner(owner.owner)
            }
        }

    private fun matches(observed: StudioNativeObservation): Boolean =
        sameAppearance(observed) && sameAdmission(observed)

    private fun sameAdmission(observed: StudioNativeObservation): Boolean {
        if (!sameFocus(observed)) return false
        if (initial.entitlement != observed.entitlement) return false
        if (initial.managers.size != observed.managers.size) return false
        return initial.managers.zip(observed.managers).all { (held, current) ->
            held === current && !held.project.isDisposed
        }
    }

    private fun sameAppearance(observed: StudioNativeObservation): Boolean =
        observation.lookAndFeel === observed.lookAndFeel &&
            observation.scheme === observed.scheme &&
            observation.variant == observed.variant

    private fun matchesTheme(
        expected: StudioThemeSnapshot,
        observed: StudioNativeObservation,
    ): Boolean =
        expected.lookAndFeel === observed.lookAndFeel &&
            expected.scheme === observed.scheme &&
            AyuVariant.fromThemeName(expected.theme?.name.orEmpty()) == observed.variant

    private fun sameFocus(observed: StudioNativeObservation): Boolean =
        initial.focus.runtime == observed.focus.runtime &&
            initial.focus.project === observed.focus.project &&
            initial.focus.project?.isDisposed != true
}

/** Lost handles cannot become live again from matching project names, settings or current native values. */
internal fun recoveredNativeLease(facts: StudioNativeFacts): StudioRuntimeLease =
    object : StudioRuntimeLease {
        override val glow: StudioGlowHolds? = null
        override val accent: StudioAccentResult? = null
        override val observation: StudioNativeObservation? = null
        override val native: StudioNativeFacts = facts

        override suspend fun confirmAccent(result: StudioAccentResult): Unit =
            error("Recovered native handles cannot confirm Accent")

        override suspend fun confirmTheme(transition: StudioThemeTransition): Unit =
            error("Recovered native handles cannot confirm a theme")

        override suspend fun isValid(): Boolean = false

        override suspend fun settledRuntime(candidate: StudioDocument): StudioRuntimeState =
            error("Recovered native handles cannot perform forward effects")

        override suspend fun release(accepted: StudioDocument?) = Unit
    }
