package dev.ayuislands.accent

import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import javax.swing.SwingUtilities
import kotlin.reflect.KMutableProperty1

internal enum class AccentPreference(
    val property: KMutableProperty1<AyuIslandsState, Boolean>,
) {
    CHROME_TINTING(AyuIslandsState::chromeTintingEnabled),
    FOLLOW_SYSTEM(AyuIslandsState::followSystemAccent),
}

/** Owns immediate accent preference changes and retries their unfinished recovery. */
internal object AccentPreferenceChange {
    private val pending = mutableListOf<PreferenceCheckpoint>()

    /**
     * Returns the actual visual outcome, or null without a context or required license.
     * Only the named preference is written. Rejected or interrupted attempts restore
     * that write while it is still owned; accepted attempts retain their preference.
     */
    @RequiresEdt
    fun apply(
        preference: AccentPreference,
        selected: Boolean,
        context: AccentContext?,
    ): AccentApplyOutcome? {
        check(SwingUtilities.isEventDispatchThread()) { "Accent preferences must change on EDT" }
        check(AccentApplyScope.active == null) { "Accent preference change is already in progress" }
        restoreAccentCheckpoints(pending)
        if (preference == AccentPreference.CHROME_TINTING && !LicenseChecker.isLicensedOrGrace()) return null
        val activeContext = context ?: AccentContext.detectQuickSwitcher() ?: AccentContext.detect() ?: return null
        val checkpoint = PreferenceCheckpoint(preference, selected)
        val participation =
            AccentApplyParticipation(checkpoint) { outcome ->
                outcome.requireClean()
                checkpoint.checkOwnership()
            }
        try {
            val outcome =
                try {
                    checkpoint.write()
                    AccentApplicator.applyForFocusedProject(activeContext, participation)
                } catch (failure: RuntimeException) {
                    var combined: Throwable = failure
                    if (!participation.isAccepted) {
                        try {
                            checkpoint.restore()
                        } catch (recovery: RuntimeException) {
                            combined = combineUiFailures(failure, recovery)
                        }
                    }
                    throw combined
                }
            if (!participation.isAccepted) checkpoint.restore()
            return outcome
        } finally {
            checkpoint.retain()
        }
    }

    /**
     * RECORDING restores only this operation's write; ACCEPTED retries derived
     * runtime work without undoing the preference; DONE ignores further recovery.
     */
    private class PreferenceCheckpoint(
        private val preference: AccentPreference,
        private val selected: Boolean,
    ) : AccentCheckpoint {
        private enum class Phase { RECORDING, ACCEPTED, DONE }

        private val target = AyuIslandsSettings.getInstance().state
        private val previous = preference.property.get(target)
        private var phase = Phase.RECORDING

        override val hasPending: Boolean
            get() = phase != Phase.DONE

        fun write() {
            preference.property.set(target, selected)
        }

        fun checkOwnership() {
            check(phase == Phase.RECORDING && ownsWrite()) { "Accent preference changed during application" }
        }

        private fun ownsWrite(): Boolean {
            if (AyuIslandsSettings.getInstance().state === target &&
                preference.property.get(target) == selected
            ) {
                return true
            }
            phase = Phase.DONE
            return false
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            when (phase) {
                Phase.RECORDING -> {
                    if (!ownsWrite()) return
                    try {
                        preference.property.set(target, previous)
                    } finally {
                        if (preference.property.get(target) == previous) phase = Phase.DONE
                    }
                }
                Phase.ACCEPTED -> releaseResources()
                Phase.DONE -> Unit
            }
        }

        override fun commit() {
            check(phase == Phase.RECORDING)
            phase = Phase.ACCEPTED
        }

        override fun releaseResources() {
            if (phase != Phase.ACCEPTED) return
            if (preference == AccentPreference.FOLLOW_SYSTEM) reconcileRotation()
            phase = Phase.DONE
        }

        override fun retain() {
            if (hasPending && pending.none { it === this }) pending += this
        }
    }

    private fun reconcileRotation() {
        val state = AyuIslandsSettings.getInstance().state
        val service = AccentRotationService.getInstance()
        if (state.accentRotationEnabled && !state.followSystemAccent && LicenseChecker.isLicensedOrGrace()) {
            service.startRotation()
        } else {
            service.stopRotation()
        }
    }
}
