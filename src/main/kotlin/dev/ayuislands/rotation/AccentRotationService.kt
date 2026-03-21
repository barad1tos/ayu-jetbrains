package dev.ayuislands.rotation

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentColor
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

private const val MS_PER_HOUR = 3_600_000L

/**
 * Returns the next preset index and hex color, wrapping at list end.
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
    private var scheduledFuture: ScheduledFuture<*>? = null

    fun startRotation() {
        stopRotation()
        val state = AyuIslandsSettings.getInstance().state
        if (!state.accentRotationEnabled) return
        if (!LicenseChecker.isLicensedOrGrace()) return

        val intervalHours = state.accentRotationIntervalHours.toLong()
        scheduledFuture =
            AppExecutorUtil
                .getAppScheduledExecutorService()
                .scheduleWithFixedDelay(
                    { rotateAccent() },
                    intervalHours,
                    intervalHours,
                    TimeUnit.HOURS,
                )
        LOG.info("Accent rotation started: interval=${intervalHours}h, mode=${state.accentRotationMode}")
    }

    /**
     * Start rotation with a custom initial delay (used after restart to schedule remaining time).
     */
    fun startRotationWithDelay(initialDelayMs: Long) {
        stopRotation()
        val state = AyuIslandsSettings.getInstance().state
        if (!state.accentRotationEnabled) return
        if (!LicenseChecker.isLicensedOrGrace()) return

        val intervalMs = state.accentRotationIntervalHours * MS_PER_HOUR
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
            "Accent rotation started: initialDelay=${initialDelayMs}ms, interval=${state.accentRotationIntervalHours}h",
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

    private fun rotateAccent() {
        val variant = AyuVariant.detect() ?: return
        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        val mode = AccentRotationMode.fromName(state.accentRotationMode)

        val newHex =
            when (mode) {
                AccentRotationMode.PRESET -> {
                    val (nextIndex, hex) = nextPresetHex(state.accentRotationPresetIndex)
                    state.accentRotationPresetIndex = nextIndex
                    hex
                }
                AccentRotationMode.RANDOM -> ContrastAwareColorGenerator.generate(variant)
            }

        SwingUtilities.invokeLater {
            settings.setAccentForVariant(variant, newHex)
            state.accentRotationLastSwitchMs = System.currentTimeMillis()
            AccentApplicator.apply(newHex)
            LOG.info("Accent rotated: mode=$mode, color=$newHex")
        }
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
