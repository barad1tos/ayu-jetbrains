package dev.ayuislands.licensing

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings

/**
 * Listens for mid-session license state changes and re-arms the premium onboarding wizard.
 *
 * When an unlicensed user purchases a license during an active IDE session, this listener
 * flips [dev.ayuislands.settings.AyuIslandsState.premiumOnboardingShown] back to false so
 * the existing OnboardingOrchestrator surfaces the premium wizard on the next IDE startup.
 *
 * Per D-05, no mid-session UI is shown — the wizard appears at next launch only.
 *
 * The listener body intentionally avoids any Swing, FileEditorManager, or
 * ApplicationManager.invokeLater calls — Topic dispatch may run off-EDT and any UI
 * work here would risk SlowOperations crashes.
 */
internal class LicenseTransitionListener : LicensingFacade.LicenseStateListener {
    override fun licenseStateChanged(facade: LicensingFacade?) {
        val isLicensed = LicenseChecker.isLicensedOrGrace()
        val state = AyuIslandsSettings.getInstance().state
        if (isLicensed && state.premiumOnboardingShown) {
            state.premiumOnboardingShown = false
            LOG.info("Ayu license: detected unlicensed->licensed; premium wizard re-armed for next startup")
        }
    }

    private companion object {
        private val LOG = logger<LicenseTransitionListener>()
    }
}
