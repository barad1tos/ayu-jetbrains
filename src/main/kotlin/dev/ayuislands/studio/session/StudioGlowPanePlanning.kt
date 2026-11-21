package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowPaneFields
import dev.ayuislands.glow.sameGlowColor
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.forPlayback
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.StudioGlowField
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioGlowStyleSlots
import java.awt.Color

/** Plans pane appearance from accepted intent and the installed values observed at capture. */
internal object StudioGlowPanePlanning {
    private val paneFields =
        mapOf(
            StudioGlowField.SHAPE.key to GlowPaneField.SHAPE,
            StudioGlowField.WAVEFORM_MOVEMENT.key to GlowPaneField.MOVEMENT,
            StudioGlowField.WAVEFORM_BASELINE.key to GlowPaneField.BASELINE,
            StudioGlowField.AMPLITUDE.key to GlowPaneField.AMPLITUDE,
            StudioGlowField.WAVEFORM_INTENSITY.key to GlowPaneField.WAVEFORM_INTENSITY,
            StudioGlowField.LOOP_SECONDS.key to GlowPaneField.LOOP_SECONDS,
            StudioGlowField.TRACE_DENSITY.key to GlowPaneField.TRACE_DENSITY,
            StudioGlowField.TRACE_LENGTH.key to GlowPaneField.TRACE_LENGTH,
        )

    fun edit(
        selection: StudioGlowSelection,
        settings: GlowSettings,
        confirmedAccent: Color?,
        isEditor: Boolean,
        actual: GlowPaneAppearance,
    ): StudioGlowPaneOperation.Edit {
        val color =
            confirmedAccent?.takeIf { candidate ->
                GlowDependency.ACCENT in selection.dependencies && !sameGlowColor(actual.color, candidate)
            }
        val selected = selectFields(selection, settings, isEditor, color != null)
        if (selected.isEmpty()) return StudioGlowPaneOperation.Edit(actual, emptySet())

        val requested = requestedAppearance(actual, settings, selected, isEditor, color)
        val changed = buildSet { selected.filterNotTo(this) { GlowPaneFields.matches(it, actual, requested) } }
        val changesParameters = GlowPaneField.INTENSITY in changed || GlowPaneField.WIDTH in changed
        val ownsStyle = GlowPaneField.STYLE in selected || actual.style == settings.style
        check(!changesParameters || ownsStyle) { "Glow pane style changed before applying its parameters" }
        val planned = changed.fold(actual) { current, field -> GlowPaneFields.replace(field, current, requested) }
        return StudioGlowPaneOperation.Edit(planned, changed)
    }

    private fun selectFields(
        selection: StudioGlowSelection,
        settings: GlowSettings,
        isEditor: Boolean,
        hasAccent: Boolean,
    ): Set<GlowPaneField> {
        val addresses = selection.selectedSettingKeys()
        val style = StudioGlowStyleSlots.forStyle(settings.style)
        val selectsStyle = StudioGlowField.STYLE.key in addresses
        val placement = if (isEditor) StudioGlowField.EDITOR_PLACEMENT else StudioGlowField.TOOL_WINDOW_PLACEMENT
        return buildSet {
            if (hasAccent) add(GlowPaneField.COLOR)
            if (selectsStyle) add(GlowPaneField.STYLE)
            if (selectsStyle || style.intensity.key in addresses) add(GlowPaneField.INTENSITY)
            if (selectsStyle || style.width.key in addresses) add(GlowPaneField.WIDTH)
            if (placement.key in addresses) add(GlowPaneField.PLACEMENT)
            addresses.mapNotNullTo(this, paneFields::get)
        }
    }

    private fun requestedAppearance(
        actual: GlowPaneAppearance,
        settings: GlowSettings,
        selected: Set<GlowPaneField>,
        isEditor: Boolean,
        color: Color?,
    ): GlowPaneAppearance =
        actual.copy(
            color = color ?: actual.color,
            style = settings.style,
            intensity =
                if (GlowPaneField.INTENSITY in selected) {
                    settings.intensity.getValue(settings.style)
                } else {
                    actual.intensity
                },
            width = if (GlowPaneField.WIDTH in selected) settings.width.getValue(settings.style) else actual.width,
            placement = if (isEditor) settings.editorPlacement else settings.toolWindowPlacement,
            shape = settings.shape,
            waveform =
                WaveformConfig(
                    movement = settings.waveformMovement,
                    baseline = settings.waveformBaseline,
                    amplitude = settings.waveformAmplitude,
                    intensity = settings.waveformIntensity,
                    loopSeconds = settings.waveformLoopSeconds,
                    traceDensity = settings.waveformTraceDensity,
                    traceLength = settings.waveformTraceLength,
                ).forPlayback(),
        )
}
