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
import dev.ayuislands.studio.model.StudioGlowFields

/** Interprets raw Glow choices without applying a preset or changing the stored appearance. */
internal object StudioGlowProjection {
    fun settings(appearance: AppearanceSnapshot): GlowSettings {
        val fields = appearance.glow.records.associate { it.key.name to it.value }
        val style = GlowStyle.fromName(text(fields.getValue("glowStyle")) ?: GlowStyle.SOFT.name)
        val animation = GlowAnimation.fromName(text(fields.getValue("glowAnimation")) ?: GlowAnimation.NONE.name)
        val intensity =
            StudioGlowFields.styles.associate { it.style to integer(fields.getValue(it.intensity)) }
        val width =
            StudioGlowFields.styles.associate { it.style to integer(fields.getValue(it.width)) }
        val preset =
            text(fields.getValue("glowPreset"))?.let(GlowPreset::fromName)
                ?: GlowPreset.detect(style, intensity.getValue(style), width.getValue(style), animation)
        return GlowSettings(
            enabled = boolean(fields.getValue("glowEnabled")),
            focusRing = boolean(fields.getValue("glowFocusRing")),
            shape = GlowShape.fromName(text(fields.getValue("glowShape"))),
            preset = preset,
            style = style,
            intensity = intensity,
            width = width,
            animation = animation,
            islandToggles =
                StudioGlowFields.islands.entries.associate { (name, id) ->
                    id to
                        boolean(fields.getValue(name))
                },
            editorPlacement = GlowPlacement.fromName(text(fields.getValue("glowEditorPlacement"))),
            toolWindowPlacement = GlowPlacement.fromName(text(fields.getValue("glowToolWindowPlacement"))),
            waveformMovement = WaveformMovement.fromName(text(fields.getValue("waveformDirection"))),
            waveformBaseline = WaveformBaseline.fromName(text(fields.getValue("waveformBaseline"))),
            waveformTraceDensity = integer(fields.getValue("waveformTraceDensity")),
            waveformTraceLength = integer(fields.getValue("waveformTraceLength")),
            waveformAmplitude = integer(fields.getValue("waveformAmplitude")),
            waveformIntensity = integer(fields.getValue("waveformIntensity")),
            waveformLoopSeconds =
                Float.fromBits(
                    requireNotNull(fields.getValue("waveformLoopSeconds") as? RawValue.FloatBits).bits,
                ),
        )
    }

    private fun text(raw: RawValue): String? =
        if (raw == RawValue.Null) null else requireNotNull(raw as? RawValue.Text).value

    private fun boolean(raw: RawValue): Boolean = requireNotNull(raw as? RawValue.BooleanValue).value

    private fun integer(raw: RawValue): Int = requireNotNull(raw as? RawValue.Integer).value
}
