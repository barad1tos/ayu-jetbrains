package dev.ayuislands.settings

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.AppearanceSyncService
import dev.ayuislands.accent.AyuVariant

/** System appearance section — visible on macOS only. */
class AyuIslandsAppearancePanel : AyuIslandsSettingsPanel {
    private var pendingFollowAppearance: Boolean = false
    private var storedFollowAppearance: Boolean = false
    private var followAppearanceCheckbox: JBCheckBox? = null

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        val settings = AyuIslandsSettings.getInstance()
        storedFollowAppearance = settings.state.followSystemAppearance
        pendingFollowAppearance = storedFollowAppearance

        if (!SystemInfo.isMac) return

        panel.group("System") {
            row { comment("Automatically switch between Light and Dark variants.") }
            row {
                val checkbox =
                    checkBox("Follow system appearance (Light / Dark)")
                        .component
                checkbox.isSelected = pendingFollowAppearance
                checkbox.addActionListener {
                    pendingFollowAppearance = checkbox.isSelected
                }
                followAppearanceCheckbox = checkbox
            }
        }
    }

    override fun isModified(): Boolean = pendingFollowAppearance != storedFollowAppearance

    override fun apply() {
        if (!isModified()) return
        val settings = AyuIslandsSettings.getInstance()
        settings.state.followSystemAppearance = pendingFollowAppearance
        storedFollowAppearance = pendingFollowAppearance
        if (pendingFollowAppearance) {
            AppearanceSyncService.getInstance().syncIfNeeded()
        }
    }

    override fun reset() {
        pendingFollowAppearance = storedFollowAppearance
        followAppearanceCheckbox?.isSelected = storedFollowAppearance
    }
}
