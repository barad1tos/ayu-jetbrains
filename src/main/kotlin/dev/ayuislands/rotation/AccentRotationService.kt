package dev.ayuislands.rotation

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
    private val disposed = Condition<Any?> {
        checkedDisposable.isDisposed
    }

    @Volatile
    private var scheduledFuture: ScheduledFuture<*>? = null

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
        scheduledFuture?.cancel(false)
        scheduledFuture = null
    }

    private fun canRotate(): Boolean {
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

    private fun rotateAccent() {
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

        val app = ApplicationManager.getApplication()
            ?: run {
                LOG.debug(
                    "Rotation skipped: app shutting down",
                )
                return
            }
        app.invokeLater(
            {
                try {
                    if (mode == AccentRotationMode.PRESET) {
                        state.accentRotationPresetIndex =
                            newHex.first
                    }
                    settings.setAccentForVariant(
                        variant,
                        newHex.second,
                    )
                    state.accentRotationLastSwitchMs =
                        System.currentTimeMillis()
                    AccentApplicator.apply(newHex.second)
                    LOG.info(
                        "Accent rotated: " +
                            "mode=$mode, color=${newHex.second}",
                    )
                } catch (exception: Exception) {
                    LOG.error(
                        "Accent rotation failed: " +
                            "mode=$mode, color=${newHex.second}",
                        exception,
                    )
                }

                try {
                    GlowOverlayManager.syncGlowForAllProjects()
                } catch (exception: RuntimeException) {
                    LOG.warn(
                        "Glow sync after accent rotation failed",
                        exception,
                    )
                }
            },
            ModalityState.nonModal(),
            disposed,
        )
    }

    override fun dispose() {
        stopRotation()
    }

    companion object {
        private val LOG = logger<AccentRotationService>()

        fun getInstance(): AccentRotationService =
            ApplicationManager
                .getApplication()
                .getService(AccentRotationService::class.java)
    }
}
