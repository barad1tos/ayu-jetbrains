package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioGlowField
import dev.ayuislands.studio.model.StudioGlowStyleSlots
import dev.ayuislands.studio.model.StudioGlowTarget
import java.util.Collections

internal object StudioGlowProjection {
    fun settings(appearance: AppearanceSnapshot): GlowSettings {
        val fields = appearance.glow.records.associate { it.key to it.value }

        fun value(field: StudioGlowField): RawValue = fields.getValue(field.key)

        fun text(field: StudioGlowField): String? = (value(field) as? RawValue.Text)?.value

        fun enabled(field: StudioGlowField): Boolean = (value(field) as RawValue.BooleanValue).value

        fun number(field: StudioGlowField): Int = (value(field) as RawValue.Integer).value

        val style = GlowStyle.fromName(text(StudioGlowField.STYLE) ?: GlowStyle.SOFT.name)
        val animation = GlowAnimation.fromName(text(StudioGlowField.ANIMATION) ?: GlowAnimation.NONE.name)
        val intensity = StudioGlowStyleSlots.entries.associate { it.style to number(it.intensity) }
        val width = StudioGlowStyleSlots.entries.associate { it.style to number(it.width) }
        val preset =
            text(StudioGlowField.PRESET)?.let(GlowPreset::fromName)
                ?: GlowPreset.detect(style, intensity.getValue(style), width.getValue(style), animation)
        val targets = StudioGlowTarget.entries.associate { it.islandId to enabled(it.field) }

        return GlowSettings(
            enabled = enabled(StudioGlowField.ENABLED),
            shape = GlowShape.fromName(text(StudioGlowField.SHAPE)),
            preset = preset,
            style = style,
            intensity = Collections.unmodifiableMap(intensity),
            width = Collections.unmodifiableMap(width),
            animation = animation,
            islandToggles = Collections.unmodifiableMap(targets),
            editorPlacement = GlowPlacement.fromName(text(StudioGlowField.EDITOR_PLACEMENT)),
            toolWindowPlacement = GlowPlacement.fromName(text(StudioGlowField.TOOL_WINDOW_PLACEMENT)),
            waveformMovement = WaveformMovement.fromName(text(StudioGlowField.WAVEFORM_MOVEMENT)),
            waveformBaseline = WaveformBaseline.fromName(text(StudioGlowField.WAVEFORM_BASELINE)),
            waveformTraceDensity = number(StudioGlowField.TRACE_DENSITY),
            waveformTraceLength = number(StudioGlowField.TRACE_LENGTH),
            waveformAmplitude = number(StudioGlowField.AMPLITUDE),
            waveformIntensity = number(StudioGlowField.WAVEFORM_INTENSITY),
            waveformLoopSeconds = Float.fromBits((value(StudioGlowField.LOOP_SECONDS) as RawValue.FloatBits).bits),
        )
    }
}
