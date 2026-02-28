package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker

/** Buy button footer shown at the bottom of settings when unlicensed. */
class AyuIslandsLicenseFooter : AyuIslandsSettingsPanel() {

    override fun buildPanel(panel: Panel, variant: AyuVariant) {
        if (LicenseChecker.isLicensedOrGrace()) return

        panel.separator()
        panel.row {
            comment("Some features require a license.")
        }
        panel.row {
            button("Get Ayu Islands Pro") {
                LicenseChecker.requestLicense(
                    "Unlock custom colors, per-element toggles, and neon glow effects"
                )
            }
        }
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}
}
