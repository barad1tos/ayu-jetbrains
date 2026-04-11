package dev.ayuislands.licensing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings

/**
 * Listens for unlicensed→licensed transitions and re-arms the premium onboarding wizard.
 *
 * When an unlicensed user purchases a license during an active IDE session, this listener
 * resets [dev.ayuislands.settings.AyuIslandsState.premiumOnboardingShown] to `false` so
 * the next IDE startup surfaces the premium wizard via OnboardingOrchestrator. No mid-session
 * UI is shown — the re-armed wizard appears at next launch only.
 *
 * Tracks the previous license state to only fire on an actual transition. The first call
 * (and any licensed→licensed notification) just records state without resetting the flag —
 * otherwise a routine refresh event would re-trigger the wizard on every restart.
 *
 * State mutation is dispatched to EDT via `invokeLater` because Topic
 * listeners may fire on arbitrary threads.
 */
internal class LicenseTransitionListener : LicensingFacade.LicenseStateListener {
    @Volatile
    private var wasLicensed: Boolean? = null

    override fun licenseStateChanged(facade: LicensingFacade?) {
        try {
            val isNowLicensed = LicenseChecker.isLicensed() == true
            val previous = wasLicensed
            wasLicensed = isNowLicensed

            // Only re-arm wizard on unlicensed → licensed transition.
            // Skip initial notification (previous == null) and licensed → licensed refreshes.
            if (previous != false || !isNowLicensed) return

            val state = AyuIslandsSettings.getInstance().state
            if (state.premiumOnboardingShown) {
                ApplicationManager.getApplication().invokeLater {
                    state.premiumOnboardingShown = false
                    LOG.info("Ayu license: unlicensed->licensed transition; premium wizard re-armed")
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
