package dev.ayuislands.licensing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings

/**
 * Listens for mid-session license state changes and re-arms the premium onboarding wizard.
 *
 * When an unlicensed user purchases a license during an active IDE session, this listener
 * resets [dev.ayuislands.settings.AyuIslandsState.premiumOnboardingShown] to `false` so
 * the next IDE startup surfaces the premium wizard via OnboardingOrchestrator. No mid-session
 * UI is shown — the re-armed wizard appears at next launch only.
 *
 * State mutation is dispatched to EDT via `invokeLater` because Topic
 * listeners may fire on arbitrary threads.
 */
internal class LicenseTransitionListener : LicensingFacade.LicenseStateListener {
    override fun licenseStateChanged(facade: LicensingFacade?) {
        try {
            val isLicensed = LicenseChecker.isLicensed() == true
            if (!isLicensed) return

            val state = AyuIslandsSettings.getInstance().state
            if (state.premiumOnboardingShown) {
                ApplicationManager.getApplication().invokeLater {
                    state.premiumOnboardingShown = false
                    LOG.info("Ayu license: detected unlicensed->licensed; premium wizard re-armed for next startup")
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
