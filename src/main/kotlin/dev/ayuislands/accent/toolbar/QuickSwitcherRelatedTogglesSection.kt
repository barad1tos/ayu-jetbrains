package dev.ayuislands.accent.toolbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentDefaults
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.ToggleSwitch
import dev.ayuislands.accent.toolbar.popup.ToggleTile
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.GridLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Grid of [ToggleTile] composites (icon + label + [ToggleSwitch]). Premium
 * mode uses the full 2-column × 2-row layout; free mode keeps Follow System
 * Accent as a single tile.
 *
 * The popup is an immediate command surface, not a Settings form. Each tile
 * writes the persistent state field and then runs the matching runtime
 * side-effect so the visible IDE changes without waiting for Settings Apply.
 *
 * "Chrome tinting" is intentionally a master toggle here: Settings remains the
 * granular 5-surface editor, while the compact popup answers the user's
 * expectation that the named feature turns on or off as one unit.
 *
 * Pattern A — mouse events already run on EDT.
 */
internal class QuickSwitcherRelatedTogglesSection(
    private val context: AccentContext? = AccentContext.detectQuickSwitcher(),
    private val showPremiumToggles: Boolean = true,
) {
    val component: JComponent

    init {
        val accentSupplier: () -> String = {
            val activeContext = context ?: AccentContext.detectQuickSwitcher()
            try {
                if (activeContext == null) {
                    AccentDefaults.MIRAGE_HEX
                } else {
                    AccentResolver.resolve(AccentApplicator.resolveFocusedProject(), activeContext)
                }
            } catch (exception: RuntimeException) {
                LOG.warn("Toggles section accent resolve failed", exception)
                AccentDefaults.MIRAGE_HEX
            }
        }

        val followTile =
            buildTile(
                AllIcons.General.Settings,
                "Follow system accent",
                QuickSwitcherToggle.FOLLOW_SYSTEM,
                accentSupplier,
            )

        val rows = if (showPremiumToggles) PREMIUM_GRID_ROWS else FREE_GRID_ROWS
        val columns = if (showPremiumToggles) PREMIUM_GRID_COLUMNS else FREE_GRID_COLUMNS
        component =
            JPanel(
                GridLayout(
                    rows,
                    columns,
                    JBUI.scale(Density.TILE_GAP),
                    JBUI.scale(Density.TILE_GAP),
                ),
            ).apply {
                isOpaque = false
                if (showPremiumToggles) {
                    add(
                        buildTile(
                            AllIcons.General.Layout,
                            "Chrome tinting",
                            QuickSwitcherToggle.CHROME,
                            accentSupplier,
                        ),
                    )
                    add(buildTile(AllIcons.General.Note, "Glow", QuickSwitcherToggle.GLOW, accentSupplier))
                    add(
                        buildTile(
                            AllIcons.Actions.Refresh,
                            "Accent rotation",
                            QuickSwitcherToggle.ROTATION,
                            accentSupplier,
                        ),
                    )
                }
                add(followTile)
            }
    }

    private fun buildTile(
        icon: Icon,
        label: String,
        toggle: QuickSwitcherToggle,
        accentSupplier: () -> String,
    ): ToggleTile {
        val switch =
            ToggleSwitch(
                initialSelected = toggle.isSelected(),
                accentSupplier = accentSupplier,
                listener = { newValue ->
                    toggle.setSelected(newValue, context)
                },
            )
        return ToggleTile(icon, label, switch)
    }

    private companion object {
        const val PREMIUM_GRID_ROWS = 2
        const val PREMIUM_GRID_COLUMNS = 2
        const val FREE_GRID_ROWS = 1
        const val FREE_GRID_COLUMNS = 1
        val LOG = logger<QuickSwitcherRelatedTogglesSection>()
    }
}

private enum class QuickSwitcherToggle {
    CHROME,
    GLOW,
    ROTATION,
    FOLLOW_SYSTEM,
}

private fun QuickSwitcherToggle.isSelected(): Boolean {
    val state = AyuIslandsSettings.getInstance().state
    return when (this) {
        QuickSwitcherToggle.CHROME ->
            state.chromeTintingEnabled && state.hasChromeTintingSurfaceEnabled()
        QuickSwitcherToggle.GLOW -> state.glowEnabled
        QuickSwitcherToggle.ROTATION -> state.accentRotationEnabled
        QuickSwitcherToggle.FOLLOW_SYSTEM -> state.followSystemAccent
    }
}

private fun QuickSwitcherToggle.setSelected(
    selected: Boolean,
    context: AccentContext?,
) {
    if (this != QuickSwitcherToggle.FOLLOW_SYSTEM && !LicenseChecker.isLicensedOrGrace()) return
    val state = AyuIslandsSettings.getInstance().state
    when (this) {
        QuickSwitcherToggle.CHROME -> {
            state.chromeTintingEnabled = selected
            applyFocusedAccent(context)
        }
        QuickSwitcherToggle.GLOW -> {
            state.glowEnabled = selected
            syncGlowOverlays(selected)
        }
        QuickSwitcherToggle.ROTATION -> {
            state.accentRotationEnabled = selected
            val service = AccentRotationService.getInstance()
            if (selected) {
                if (!state.followSystemAccent) {
                    service.startRotation()
                }
            } else {
                service.stopRotation()
            }
        }
        QuickSwitcherToggle.FOLLOW_SYSTEM -> {
            state.followSystemAccent = selected
            if (state.accentRotationEnabled) {
                val service = AccentRotationService.getInstance()
                if (selected) {
                    service.stopRotation()
                } else if (LicenseChecker.isLicensedOrGrace()) {
                    service.startRotation()
                }
            }
            applyFocusedAccent(context)
        }
    }
}

private fun applyFocusedAccent(context: AccentContext?) {
    val activeContext = context ?: AccentContext.detectQuickSwitcher() ?: AccentContext.detect() ?: return
    try {
        AccentApplicator.applyForFocusedProject(activeContext)
    } catch (exception: RuntimeException) {
        logger<QuickSwitcherRelatedTogglesSection>().warn(
            "Quick switcher toggle failed to reapply focused accent",
            exception,
        )
    }
}

private fun syncGlowOverlays(glowEnabled: Boolean) {
    try {
        GlowOverlayManager.syncGlowForAllProjects()
    } catch (exception: RuntimeException) {
        logger<QuickSwitcherRelatedTogglesSection>().warn(
            "Quick switcher toggle failed to sync glow overlays (enabled=$glowEnabled)",
            exception,
        )
    }
}
