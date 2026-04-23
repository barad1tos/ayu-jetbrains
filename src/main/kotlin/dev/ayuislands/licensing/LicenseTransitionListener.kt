package dev.ayuislands.licensing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.LicensingFacade
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings

/**
 * Listens for license-state transitions and reacts on two axes:
 *
 *  1. **Premium wizard re-arm** — on unlicensed → licensed transitions only, resets
 *     [dev.ayuislands.settings.AyuIslandsState.premiumOnboardingShown] to `false` so
 *     the next IDE startup surfaces the premium wizard via OnboardingOrchestrator.
 *     No mid-session UI is shown — the re-armed wizard appears at next launch only.
 *
 *  2. **Chrome accent re-apply** — on EITHER transition direction (licensed↔unlicensed),
 *     re-applies the accent so chrome tinting picks up the fresh license state.
 *     [dev.ayuislands.accent.AccentResolver] short-circuits overrides when unlicensed
 *     (chrome falls back to global accent) and honors them when licensed, so any
 *     license flip changes the resolved color the chrome renderer uses.
 *
 * Tracks the previous license state to only fire on an actual transition. The first
 * call (initial notification, `previous == null`) just records state without firing
 * either action — otherwise a routine startup refresh would re-trigger the wizard
 * and cause a spurious redraw on every restart.
 *
 * State mutation and [AccentApplicator.applyForFocusedProject] (which is `@RequiresEdt`)
 * are dispatched to EDT via `invokeLater` because Topic listeners may fire on
 * arbitrary threads.
 */
internal class LicenseTransitionListener : LicensingFacade.LicenseStateListener {
    @Volatile
    private var wasLicensed: Boolean? = null

    override fun licenseStateChanged(facade: LicensingFacade?) {
        try {
            val isNowLicensed = LicenseChecker.isLicensed() == true
            val previous = wasLicensed
            wasLicensed = isNowLicensed

            // Re-arm premium wizard only on unlicensed → licensed transition.
            // Skip initial notification (previous == null) and licensed → licensed refreshes.
            if (previous == false && isNowLicensed) {
                val state = AyuIslandsSettings.getInstance().state
                if (state.premiumOnboardingShown) {
                    ApplicationManager.getApplication().invokeLater {
                        state.premiumOnboardingShown = false
                        LOG.info("Ayu license: unlicensed->licensed transition; premium wizard re-armed")
                    }
                }
            }

            // Re-apply accent on either transition direction so chrome picks up the fresh
            // license state. Resolver short-circuits overrides when unlicensed (chrome falls
            // back to global accent) and honors them when licensed. `applyForFocusedProject`
            // is @RequiresEdt — dispatch via invokeLater. AyuVariant.detect() may return null
            // if a non-Ayu theme is active; skip silently in that case.
            if (previous != null && previous != isNowLicensed) {
                ApplicationManager.getApplication().invokeLater {
                    val variant = AyuVariant.detect() ?: return@invokeLater
                    AccentApplicator.applyForFocusedProject(variant)
                    LOG.info("Ayu license transition: re-applied accent for chrome refresh")
                }
            }
        } catch (exception: RuntimeException) {
            LOG.error("Ayu license: failed to handle license state change", exception)
        }
    }

    private companion object {
        private val LOG = logger<LicenseTransitionListener>()
    }
}
