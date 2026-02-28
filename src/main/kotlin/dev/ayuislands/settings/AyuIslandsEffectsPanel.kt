package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import javax.swing.JCheckBox

/** Glow effect toggle with license-aware dimming. */
class AyuIslandsEffectsPanel : AyuIslandsSettingsPanel() {

    private var pendingGlowEnabled: Boolean = true
    private var storedGlowEnabled: Boolean = true
    private var glowCheckbox: JCheckBox? = null
    var onGlowChanged: (() -> Unit)? = null

    override fun buildPanel(panel: Panel, variant: AyuVariant) {
        val state = AyuIslandsSettings.getInstance().state
        val licensed = LicenseChecker.isLicensedOrGrace()

        storedGlowEnabled = state.glowEnabled
        pendingGlowEnabled = storedGlowEnabled

        panel.group("Effects") {
            if (!licensed) {
                row {
                    label("Pro feature").applyToComponent {
                        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    }
                    link("Get Ayu Islands Pro") {
                        LicenseChecker.requestLicense(
                            "Unlock glow effects, per-element toggles, and custom accent colors"
                        )
                    }
                }
            }

            row {
                val cb = checkBox("Glow")
                    .comment("Subtle glow border around the editor area")
                cb.component.isSelected = pendingGlowEnabled
                cb.component.isEnabled = licensed
                cb.component.addActionListener {
                    pendingGlowEnabled = cb.component.isSelected
                    onGlowChanged?.invoke()
                }
                glowCheckbox = cb.component
            }
        }
    }

    fun isGlowEnabled(): Boolean = pendingGlowEnabled

    override fun isModified(): Boolean = pendingGlowEnabled != storedGlowEnabled

    override fun apply() {
        if (!isModified()) return
        AyuIslandsSettings.getInstance().state.glowEnabled = pendingGlowEnabled
        storedGlowEnabled = pendingGlowEnabled
    }

    override fun reset() {
        pendingGlowEnabled = storedGlowEnabled
        glowCheckbox?.isSelected = storedGlowEnabled
    }
}
