package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowFocusAppearance
import dev.ayuislands.glow.GlowFocusFields
import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowPaneFields
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.qualifyRouteAppearance
import dev.ayuislands.glow.resolveGlowPlacement
import dev.ayuislands.glow.resolveWaveformConfig
import dev.ayuislands.glow.sameColor
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.StudioGlowFields
import dev.ayuislands.studio.model.StudioGlowSelection
import java.awt.Color

/** Merges explicit intent into captured appearance; unresolved native selector conflicts never become writes. */
internal object StudioGlowMerge {
    fun membershipScope(selection: StudioGlowSelection): Set<String> {
        val names = selection.fields.map { it.field.name }
        return if (selectsAvailability(selection)) {
            StudioGlowFields.islands.values.toSet()
        } else {
            names.mapNotNull(StudioGlowFields.islands::get).toSet()
        }
    }

    fun selectsFocus(selection: StudioGlowSelection): Boolean =
        selectsAvailability(selection) || selection.fields.any { it.field.name == "glowFocusRing" }

    fun selectsAvailability(selection: StudioGlowSelection): Boolean =
        selection.fields.any { it.field.name == "glowEnabled" } || GlowDependency.AVAILABILITY in selection.dependencies

    fun retiresIsland(
        id: String,
        scope: Set<String>,
        settings: GlowSettings,
        isAvailable: Boolean,
    ): Boolean =
        dev.ayuislands.glow.GlowIslandIds
            .canonical(id) in scope &&
            (!isAvailable || !settings.enabled || !settings.isIslandEnabled(id))

    fun mergeAnimation(
        selection: StudioGlowSelection,
        settings: GlowSettings,
        before: GlowAnimation,
        isCold: Boolean,
    ): GlowAnimation =
        if (isCold || selection.fields.any { it.field.name == "glowAnimation" }) settings.animation else before

    /** A missing coordinator is not permission to restart it on an unrelated field edit. */
    fun mergeRoute(
        selection: StudioGlowSelection,
        settings: GlowSettings,
        before: WaveformConfig?,
        appearances: List<GlowPaneAppearance>,
        isCold: Boolean,
    ): WaveformConfig? {
        if (selectsAvailability(selection) && !settings.enabled) return null
        val names = selection.fields.map { it.field.name }
        val selectsMode = "glowShape" in names || "waveformDirection" in names || isCold
        if (before == null && !selectsMode) return null
        val configured = resolveWaveformConfig(settings)
        val merged = if (before == null) configured else mergeWaveform(selection, before, configured)
        val config = appearances.firstOrNull()?.waveform ?: merged
        val fallbackShape = routeShape(names, settings, before, isCold)
        val shape = appearances.firstOrNull()?.shape ?: fallbackShape
        if (selectsMode && (shape != GlowShape.WAVEFORM || config.movement != WaveformMovement.CHAOTIC)) return null
        qualifyRouteAppearance(config, appearances)
        return config
    }

    private fun routeShape(
        names: List<String>,
        settings: GlowSettings,
        before: WaveformConfig?,
        isCold: Boolean,
    ): GlowShape = if ("glowShape" in names || isCold || before == null) settings.shape else GlowShape.WAVEFORM

    private fun mergeWaveform(
        selection: StudioGlowSelection,
        before: WaveformConfig,
        source: WaveformConfig,
    ): WaveformConfig {
        val names = selection.fields.map { it.field.name }
        val fields = StudioGlowFields.panes.filter { it.name in names }.map { it.field }
        return before.copy(
            movement = if (GlowPaneField.MOVEMENT in fields) source.movement else before.movement,
            baseline = if (GlowPaneField.BASELINE in fields) source.baseline else before.baseline,
            traceDensity = if (GlowPaneField.TRACE_DENSITY in fields) source.traceDensity else before.traceDensity,
            traceLength = if (GlowPaneField.TRACE_LENGTH in fields) source.traceLength else before.traceLength,
            amplitude = if (GlowPaneField.AMPLITUDE in fields) source.amplitude else before.amplitude,
            intensity = if (GlowPaneField.WAVEFORM_INTENSITY in fields) source.intensity else before.intensity,
            loopSeconds = if (GlowPaneField.LOOP_SECONDS in fields) source.loopSeconds else before.loopSeconds,
        )
    }

    fun mergePane(
        actual: GlowPaneAppearance,
        selection: StudioGlowSelection,
        settings: GlowSettings,
        color: Color,
        isEditor: Boolean,
    ): GlowPaneAppearance {
        val fields = selectPaneFields(selection, settings, isEditor)
        if (fields.isEmpty()) return actual
        val source =
            actual.copy(
                color = if (GlowPaneField.COLOR in fields) resolveColor(actual.color, color) else actual.color,
                style = settings.style,
                intensity = settings.intensity.getValue(settings.style),
                width = settings.width.getValue(settings.style),
                placement = resolveGlowPlacement(isEditor, settings),
                shape = settings.shape,
                waveform = resolveWaveformConfig(settings),
            )
        val planned = fields.fold(actual) { current, field -> GlowPaneFields.replace(field, current, source) }
        val hasParameterChange = actual.intensity != planned.intensity || actual.width != planned.width
        if (hasParameterChange && GlowPaneField.STYLE !in fields) {
            check(actual.style == settings.style) { "Glow style needs a fresh review before changing its parameters" }
        }
        return if (fields.all { GlowPaneFields.matches(it, actual, planned) }) actual else planned
    }

    /** Availability is the effective capability/theme gate; it never replaces stored enabled/focus choices. */
    fun mergeFocus(
        actual: GlowFocusAppearance,
        selection: StudioGlowSelection,
        settings: GlowSettings,
        color: Color,
        isAvailable: Boolean,
    ): GlowFocusAppearance {
        val names = selection.fields.map { it.field.name }
        val active = StudioGlowFields.styles.single { it.style == settings.style }
        val hasStyleChange = "glowStyle" in names
        val hasIntensityChange = hasStyleChange || active.intensity in names
        val plannedColor =
            if (GlowDependency.ACCENT in selection.dependencies) resolveColor(actual.color, color) else actual.color
        val isEnabled =
            if (selectsFocus(selection)) isAvailable && settings.enabled && settings.focusRing else actual.enabled
        val planned =
            actual.copy(
                color = plannedColor,
                style = if (hasStyleChange) settings.style else actual.style,
                intensity =
                    if (hasIntensityChange) settings.intensity.getValue(settings.style) else actual.intensity,
                enabled = isEnabled,
            )
        if (actual.intensity != planned.intensity && !hasStyleChange) {
            check(actual.style == settings.style) { "Glow focus style needs a fresh review before changing intensity" }
        }
        return if (GlowFocusFields.matches(actual, planned)) actual else planned
    }

    private fun selectPaneFields(
        selection: StudioGlowSelection,
        settings: GlowSettings,
        isEditor: Boolean,
    ): Set<GlowPaneField> {
        val names = selection.fields.map { it.field.name }
        val fields =
            StudioGlowFields.panes
                .filter { it.name in names }
                .map { it.field }
                .toMutableSet()
        val active = StudioGlowFields.styles.single { it.style == settings.style }
        val hasStyleChange = "glowStyle" in names
        if (hasStyleChange) fields.add(GlowPaneField.STYLE)
        if (hasStyleChange || active.intensity in names) fields.add(GlowPaneField.INTENSITY)
        if (hasStyleChange || active.width in names) fields.add(GlowPaneField.WIDTH)
        val placement = if (isEditor) "glowEditorPlacement" else "glowToolWindowPlacement"
        if (placement in names) fields.add(GlowPaneField.PLACEMENT)
        if (GlowDependency.ACCENT in selection.dependencies) fields.add(GlowPaneField.COLOR)
        return fields
    }

    private fun resolveColor(
        actual: Color,
        requested: Color,
    ): Color = if (sameColor(actual, requested)) actual else requested
}
