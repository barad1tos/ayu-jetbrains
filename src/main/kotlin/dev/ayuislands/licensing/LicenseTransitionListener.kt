package dev.ayuislands.licensing

import com.intellij.ui.LicensingFacade
import dev.ayuislands.studio.StudioService

/** Marketplace callbacks enter the same admitted metadata and native-effect lane as startup. */
internal class LicenseTransitionListener : LicensingFacade.LicenseStateListener {
    override fun licenseStateChanged(facade: LicensingFacade?) {
        StudioService.getInstance().refreshEntitlement()
    }
}
