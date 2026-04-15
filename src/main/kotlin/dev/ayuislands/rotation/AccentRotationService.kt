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
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentColor
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
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
        // Reset the circuit-breaker budget BEFORE cancelling the future so a throw from
        // cancel (CancellationException on double-cancel, RejectedExecutionException during
        // executor shutdown) cannot leave the counter stuck. Every teardown — user toggle,
        // breaker trip, service dispose — must start re-enables with a full failure budget.
        consecutiveFailures = 0
        val future = scheduledFuture
        scheduledFuture = null
        try {
            future?.cancel(false)
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to cancel scheduled rotation future", exception)
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
                AccentRotationMode.RANDOM ->
                    -1 to
                        ContrastAwareColorGenerator
                            .generate(variant)
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
                val applyFailure = runApplyStage(mode, newHex, variant, state, settings)
                val glowFailure = runGlowStage()

                if (applyFailure != null || glowFailure != null) {
                    onRotationFailure(applyFailure ?: glowFailure!!)
                } else {
                    consecutiveFailures = 0
                }
            },
            ModalityState.nonModal(),
            disposed,
        )
    }

    /**
     * Commit the new preset index + global accent hex, then re-apply the resolver output for
     * the focused project so overrides stay sticky. Returns a failure descriptor on exception.
     */
    private fun runApplyStage(
        mode: AccentRotationMode,
        newHex: Pair<Int, String>,
        variant: AyuVariant,
        state: dev.ayuislands.settings.AyuIslandsState,
        settings: AyuIslandsSettings,
    ): RotationFailure? =
        try {
            if (mode == AccentRotationMode.PRESET) {
                state.accentRotationPresetIndex = newHex.first
            }
            settings.setAccentForVariant(variant, newHex.second)
            state.accentRotationLastSwitchMs = System.currentTimeMillis()
            // Rotation updates the GLOBAL accent layer only. For the currently focused project we
            // must apply the RESOLVED color so per-project and per-language overrides keep winning
            // during rotation ticks.
            val resolvedHex = AccentApplicator.applyForFocusedProject(variant)
            LOG.info("Accent rotated: mode=$mode, global=${newHex.second}, applied=$resolvedHex")
            null
        } catch (exception: RuntimeException) {
            LOG.error(
                "Accent rotation stage=apply failed (mode=$mode, color=${newHex.second})",
                exception,
            )
            RotationFailure(stage = Stage.APPLY, exception = exception)
        }

    /**
     * Sync glow overlays with the new accent. Already ran in its own try/catch pre-refactor;
     * what this commit series adds is feeding a shared circuit-breaker counter through the
     * returned [RotationFailure].
     */
    private fun runGlowStage(): RotationFailure? =
        try {
            GlowOverlayManager.syncGlowForAllProjects()
            null
        } catch (exception: RuntimeException) {
            LOG.error("Accent rotation stage=glow-sync failed", exception)
            RotationFailure(stage = Stage.GLOW_SYNC, exception = exception)
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
        // stopRotation resets consecutiveFailures as part of teardown — no redundant assignment.
        stopRotation()
        val notification =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    "Ayu Islands: accent rotation stopped",
                    "Rotation failed $MAX_CONSECUTIVE_FAILURES times in a row (last stage: " +
                        "${failure.stage.logLabel}). Re-enable it from Settings > Accent > " +
                        "Rotation after checking the logs.",
                    NotificationType.WARNING,
                )
        notification.notify(null)
    }

    /**
     * Pipeline stages the circuit breaker tracks. Previously stringly-typed (`"apply"` /
     * `"glow-sync"`), which let a typo-prone caller silently write the wrong label into logs
     * and notifications. Closed enum so the compiler catches label drift.
     */
    private enum class Stage(
        val logLabel: String,
    ) {
        APPLY("apply"),
        GLOW_SYNC("glow-sync"),
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
