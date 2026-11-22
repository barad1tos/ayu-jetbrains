package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowFocusAppearance
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.sameGlowColor
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.StudioGlowField
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioGlowStyleSlots
import java.awt.Color

/** Projects accepted intent onto installed Glow without reading or updating persistent preferences. */
internal object StudioGlowPlanning {
    /** Panes use their project's accent; focus may use the confirmed accent of a different project. */
    data class Accents(
        val pane: Color?,
        val focus: Color?,
    )

    private data class FocusPatch(
        val style: GlowStyle?,
        val intensity: Int?,
        val enabled: Boolean?,
        val color: Color?,
    )

    fun target(
        manager: GlowOverlayManager,
        selection: StudioGlowSelection,
        settings: GlowSettings,
        accents: Accents,
        available: Boolean,
    ): StudioGlowTarget {
        val membership = StudioGlowMembershipPlanning.select(selection, settings, available)
        return StudioGlowTarget(
            manager,
            focusEdit(selection, settings, accents.focus, available),
            membership?.let { StudioGlowMembership.Request(it, settings, accents.pane) },
        ) { isEditor, actual -> StudioGlowPanePlanning.edit(selection, settings, accents.pane, isEditor, actual) }
    }

    /** An absent edit lets pane-only work leave focus callbacks running until it needs a pane hold. */
    fun focusEdit(
        selection: StudioGlowSelection,
        settings: GlowSettings,
        confirmedAccent: Color?,
        available: Boolean,
    ): ((GlowFocusAppearance) -> GlowFocusAppearance)? {
        val keys = selection.selectedSettingKeys()
        val style = settings.style.takeIf { StudioGlowField.STYLE.key in keys }
        val selectsIntensity = style != null || StudioGlowStyleSlots.forStyle(settings.style).intensity.key in keys
        val intensity = if (selectsIntensity) settings.intensity.getValue(settings.style) else null
        val selectsEnabled =
            StudioGlowField.ENABLED.key in keys ||
                StudioGlowField.FOCUS_RING.key in keys ||
                GlowDependency.AVAILABILITY in selection.dependencies
        val enabled = if (selectsEnabled) available && settings.enabled && settings.focusRing else null
        val color = confirmedAccent.takeIf { GlowDependency.ACCENT in selection.dependencies }
        val changesAppearance = style != null || intensity != null || color != null
        if (!changesAppearance && enabled == null) return null

        val patch = FocusPatch(style, intensity, enabled, color)
        return { actual -> mergeFocus(actual, patch, settings.style) }
    }

    private fun mergeFocus(
        actual: GlowFocusAppearance,
        patch: FocusPatch,
        configuredStyle: GlowStyle,
    ): GlowFocusAppearance {
        val changesIntensity = patch.intensity != null && patch.intensity != actual.intensity
        check(!changesIntensity || patch.style != null || actual.style == configuredStyle) {
            "Glow focus style changed before applying its intensity"
        }
        val planned =
            actual.copy(
                color = patch.color?.takeUnless { sameGlowColor(actual.color, it) } ?: actual.color,
                style = patch.style ?: actual.style,
                intensity = patch.intensity ?: actual.intensity,
                enabled = patch.enabled ?: actual.enabled,
            )
        return if (actual.matches(planned)) actual else planned
    }
}
