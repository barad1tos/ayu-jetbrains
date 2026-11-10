package dev.ayuislands.accent.toolbar

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.ToggleSwitch
import dev.ayuislands.accent.toolbar.popup.ToggleTile
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.studio.persistence.AyuSettingsView
import dev.ayuislands.studio.session.StudioQuickCommand
import java.awt.GridLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/** Preserves the native tile order and reads each toggle from accepted Studio choices. */
internal class QuickSwitcherRelatedTogglesSection(
    context: AccentContext? = AccentContext.detectQuickSwitcher(),
    showPremiumToggles: Boolean = true,
    private val commands: QuickSwitcherCommands? = QuickSwitcherCommands.capture(context = context),
) {
    val component: JComponent =
        JPanel(
            GridLayout(
                if (showPremiumToggles) 2 else 1,
                if (showPremiumToggles) 2 else 1,
                JBUI.scale(Density.TILE_GAP),
                JBUI.scale(Density.TILE_GAP),
            ),
        ).apply {
            isOpaque = false
            if (showPremiumToggles) {
                add(tile(AllIcons.General.Layout, "Chrome tinting", QuickSwitcherToggle.CHROME))
                add(tile(AllIcons.General.Note, "Glow", QuickSwitcherToggle.GLOW))
                add(tile(AllIcons.Actions.Refresh, "Accent rotation", QuickSwitcherToggle.ROTATION))
            }
            add(tile(AllIcons.General.Settings, "Follow system accent", QuickSwitcherToggle.FOLLOW_SYSTEM))
        }

    private fun tile(
        icon: Icon,
        label: String,
        toggle: QuickSwitcherToggle,
    ): ToggleTile {
        lateinit var control: ToggleSwitch
        control =
            ToggleSwitch(
                selectedSupplier = { commands?.view?.settings?.let(toggle::selected) == true },
                accentSupplier = { commands?.accent() },
                listener = { selected ->
                    if (toggle == QuickSwitcherToggle.FOLLOW_SYSTEM || LicenseChecker.isLicensedOrGrace()) {
                        commands?.submit(toggle.command(selected)) { control.repaint() }
                    }
                },
            )
        return ToggleTile(icon, label, control)
    }
}

private enum class QuickSwitcherToggle {
    CHROME,
    GLOW,
    ROTATION,
    FOLLOW_SYSTEM,
    ;

    fun selected(settings: AyuSettingsView): Boolean =
        when (this) {
            CHROME -> settings.chromeTintingEnabled && settings.hasChromeTintingSurfaceEnabled()
            GLOW -> settings.glowEnabled
            ROTATION -> settings.accentRotationEnabled
            FOLLOW_SYSTEM -> settings.followSystemAccent
        }

    fun command(selected: Boolean): StudioQuickCommand =
        when (this) {
            CHROME -> StudioQuickCommand.Accent(AccentPanelEvent.ChromeEnabled(selected))
            GLOW -> StudioQuickCommand.GlowEnabled(selected)
            ROTATION -> StudioQuickCommand.Accent(AccentPanelEvent.RotationEnabled(selected))
            FOLLOW_SYSTEM -> StudioQuickCommand.Accent(AccentPanelEvent.FollowSystem(selected))
        }
}
