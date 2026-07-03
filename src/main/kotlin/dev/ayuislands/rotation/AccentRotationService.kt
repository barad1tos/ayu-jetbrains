package dev.ayuislands.rotation

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentColor
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.reapply.ReapplyReason
import dev.ayuislands.reapply.ReapplyResult
import dev.ayuislands.reapply.ReapplyStep
import dev.ayuislands.reapply.ThemeReapplication
import dev.ayuislands.settings.AyuIslandsSettings
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val MS_PER_HOUR = 3_600_000L
private const val MIN_INTERVAL_HOURS = 1L

/**
 * Returns the next preset index and hex color, wrapping at the list end.
 * Extracted as internal for unit testing without IDE singletons.
 */
internal fun nextPresetHex(
    currentIndex: Int,
    presets: List<AccentColor> = AYU_ACCENT_PRESETS,
): Pair<Int, String> {
    val nextIndex = (currentIndex + 1) % presets.size
    return nextIndex to presets[nextIndex].hex
}

@Service
class AccentRotationService : Disposable {
    private val checkedDisposable: CheckedDisposable =
        Disposer.newCheckedDisposable(this)
    private val disposed =
        Condition<Any?> {
            checkedDisposable.isDisposed
        }

    @Volatile
    private var scheduledFuture: ScheduledFuture<*>? = null

    /**
     * Circuit-breaker counter. When a rotation tick fails, this increments; on
     * [MAX_CONSECUTIVE_FAILURES] consecutive failures we stop the scheduler and notify the user.
     * Reset to zero on every successful tick. This stops a broken rotation from hammering
     * idea.log every hour indefinitely when the root cause is persistent (corrupted state,
     * stuck UIManager, missing variant).
     */
    @Volatile
    private var consecutiveFailures: Int = 0

    fun startRotation() {
        stopRotation()
        if (!canRotate()) return

        val state = AyuIslandsSettings.getInstance().state
        val intervalHours =
            state.accentRotationIntervalHours
                .toLong()
                .coerceAtLeast(MIN_INTERVAL_HOURS)
        scheduledFuture =
            AppExecutorUtil
                .getAppScheduledExecutorService()
                .scheduleWithFixedDelay(
                    { rotateAccent() },
                    intervalHours,
                    intervalHours,
                    TimeUnit.HOURS,
                )
        LOG.info(
            "Accent rotation started: " +
                "interval=${intervalHours}h, " +
                "mode=${state.accentRotationMode}",
        )
    }

    fun startRotationWithDelay(initialDelayMs: Long) {
        stopRotation()
        if (!canRotate()) return

        val state = AyuIslandsSettings.getInstance().state
        val intervalMs =
            state.accentRotationIntervalHours
                .toLong()
                .coerceAtLeast(MIN_INTERVAL_HOURS) * MS_PER_HOUR
        scheduledFuture =
            AppExecutorUtil
                .getAppScheduledExecutorService()
                .scheduleWithFixedDelay(
                    { rotateAccent() },
                    initialDelayMs,
                    intervalMs,
                    TimeUnit.MILLISECONDS,
                )
        LOG.info(
            "Accent rotation started: " +
                "initialDelay=${initialDelayMs}ms, " +
                "interval=${state.accentRotationIntervalHours}h",
        )
    }

    fun rotateNow() {
        rotateAccent()
        startRotation()
    }

    fun stopRotation() {
        // Reset the circuit-breaker budget BEFORE cancelling the future. The JDK's
        // `ScheduledFuture.cancel(boolean)` contract returns a boolean rather than throwing,
        // and `AppExecutorUtil.getAppScheduledExecutorService()` as of platformVersion 2025.1
        // composes an executor whose wrapper does not throw either — so the catch below is
        // cheap defensive insurance rather than a known failure path. Reset-first ordering
        // guarantees that if the JDK / platform contract ever tightens (disposed-pool lookup
        // throws, lifecycle-checking wrapper added), the counter stays in sync and every
        // teardown (user toggle, breaker trip, service dispose) starts re-enables with a
        // full failure budget. The warn message below names the JDK contract so triage sees
        // signal value: if it ever fires, it's a regression worth investigating.
        consecutiveFailures = 0
        val future = scheduledFuture
        scheduledFuture = null
        try {
            future?.cancel(false)
        } catch (exception: RuntimeException) {
            LOG.warn(
                "Failed to cancel scheduled rotation future " +
                    "(unexpected — JDK's Future.cancel contract returns a boolean without throwing)",
                exception,
            )
        }
    }

    @TestOnly
    internal fun canRotate(): Boolean {
        val state = AyuIslandsSettings.getInstance().state
        if (!state.accentRotationEnabled) {
            LOG.debug("Rotation skipped: disabled")
            return false
        }
        if (!LicenseChecker.isLicensedOrGrace()) {
            LOG.debug("Rotation skipped: no license")
            return false
        }
        return true
    }

    @TestOnly
    internal fun rotateAccent() {
        if (!canRotate()) return
        if (checkedDisposable.isDisposed) {
            LOG.debug("Rotation skipped: service disposed")
            return
        }

        val variant = AyuVariant.detect()
        if (variant == null) {
            LOG.debug("Rotation skipped: non-Ayu theme")
            return
        }

        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        val mode =
            AccentRotationMode.fromName(
                state.accentRotationMode,
            )

        val newHex =
            when (mode) {
                AccentRotationMode.PRESET -> {
                    val (nextIndex, hex) =
                        nextPresetHex(
                            state.accentRotationPresetIndex,
                        )
                    nextIndex to hex
                }

                AccentRotationMode.RANDOM -> {
                    -1 to
                        ContrastAwareColorGenerator
                            .generate(variant)
                }
            }

        val app =
            ApplicationManager.getApplication()
                ?: run {
                    LOG.debug(
                        "Rotation skipped: app shutting down",
                    )
                    return
                }
        app.invokeLater(
            {
                commitRotationState(mode, newHex, variant, state, settings)
                ThemeReapplication.reapply(ReapplyReason.RotationTick(variant)) { result ->
                    if (result.isClean) {
                        consecutiveFailures = 0
                    } else {
                        onRotationFailure(rotationFailureFrom(result))
                    }
                }
            },
            ModalityState.nonModal(),
            disposed,
        )
    }

    /**
     * Commit the new preset index + global accent hex, plus the last-switch timestamp. State
     * only — [ThemeReapplication.reapply] performs the actual apply (and glow sync) against the
     * resolver output, so per-project and per-language overrides keep winning during rotation.
     */
    private fun commitRotationState(
        mode: AccentRotationMode,
        newHex: Pair<Int, String>,
        variant: AyuVariant,
        state: dev.ayuislands.settings.AyuIslandsState,
        settings: AyuIslandsSettings,
    ) {
        if (mode == AccentRotationMode.PRESET) {
            state.accentRotationPresetIndex = newHex.first
        }
        settings.setAccentForVariant(variant, newHex.second)
        state.accentRotationLastSwitchMs = System.currentTimeMillis()
        LOG.info("Accent rotated: mode=$mode, global=${newHex.second}")
    }

    /**
     * Map a failed [ReapplyResult] to the circuit breaker's [RotationFailure], logging every
     * failed step for idea.log forensics. When both the accent apply and the glow sync fail,
     * apply wins priority — mirrors the pre-seam behaviour where the apply-stage catch block
     * ran (and reported) before the glow-stage catch block. Falls back to the first recorded
     * failure (defaulting to [Stage.APPLY]) if neither of those two steps is the one that
     * failed — this runs inside an EDT `invokeLater` callback, so it must never throw: a
     * `RotationTick` plan gaining a new step must degrade gracefully here, not crash the EDT.
     * [result.failures] is guaranteed non-empty by [ReapplyResult.isClean]'s contract, so
     * `first()` is safe.
     */
    private fun rotationFailureFrom(result: ReapplyResult): RotationFailure {
        result.failures.forEach { failure ->
            LOG.error("Accent rotation step=${failure.step} failed", failure.error)
        }
        val applyError = result.failures.firstOrNull { it.step == ReapplyStep.ApplyResolvedAccent }?.error
        val glowError = result.failures.firstOrNull { it.step == ReapplyStep.Glow }?.error
        val exception = applyError ?: glowError ?: result.failures.first().error
        val stage =
            when {
                applyError != null -> Stage.APPLY
                glowError != null -> Stage.GLOW_SYNC
                else -> Stage.APPLY
            }
        return RotationFailure(stage = stage, exception = exception)
    }

    /**
     * Track the failure. Once [MAX_CONSECUTIVE_FAILURES] is reached, stop the scheduler and
     * surface a notification so the user discovers the problem instead of silently losing the
     * rotation feature. The user can re-enable it from Settings > Accent > Rotation after
     * investigating.
     */
    private fun onRotationFailure(failure: RotationFailure) {
        val count = ++consecutiveFailures
        if (count < MAX_CONSECUTIVE_FAILURES) return
        stopRotation() // resets consecutiveFailures as part of teardown
        val notification =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    "Ayu Islands: accent rotation stopped",
                    "Rotation failed $MAX_CONSECUTIVE_FAILURES times in a row (last stage: " +
                        "${failure.stage}). Re-enable it from Settings > Accent > Rotation " +
                        "after checking the logs.",
                    NotificationType.WARNING,
                )
        notification.notify(null)
    }

    /**
     * Pipeline stages the circuit breaker tracks. Previously stringly-typed (`"apply"` /
     * `"glow-sync"`), which let a typo-prone caller silently write the wrong label into logs
     * and notifications. Closed enum so the compiler catches label drift; [toString] returns
     * the wire-compatible label so both `idea.log` messages and user notifications reference
     * the single authoritative source (`"$stage"`), eliminating the hardcoded-vs-enum drift
     * risk between the two sites.
     */
    private enum class Stage(
        private val label: String,
    ) {
        APPLY("apply"),
        GLOW_SYNC("glow-sync"),
        ;

        override fun toString(): String = label
    }

    private data class RotationFailure(
        val stage: Stage,
        val exception: Throwable,
    )

    override fun dispose() {
        stopRotation()
    }

    companion object {
        private val LOG = logger<AccentRotationService>()
        private const val NOTIFICATION_GROUP_ID = "Ayu Islands"

        /**
         * Trip the circuit breaker and notify the user after this many consecutive rotation
         * failures. Lower threshold risks spurious notifications on transient hiccups; higher
         * threshold lets the log spam accumulate across more rotation cycles before the user
         * discovers rotation has been silently broken.
         */
        private const val MAX_CONSECUTIVE_FAILURES = 3

        fun getInstance(): AccentRotationService =
            ApplicationManager
                .getApplication()
                .getService(AccentRotationService::class.java)
    }
}
