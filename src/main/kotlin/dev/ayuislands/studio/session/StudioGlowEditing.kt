package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.settings.GlowPanelEvent
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.settings.WaveformPanelEvent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.GlowStyleFields
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioGlowFields

/** Selects exact raw fields for explicit Glow edits; decoded dormant choices are never written back. */
internal object StudioGlowEditing {
    fun edits(
        draft: DraftRecord,
        event: GlowPanelEvent,
    ): List<SessionIntent> {
        val settings = StudioGlowProjection.settings(draft.appearance)
        val changes = changes(settings, event)
        return changes.map { (name, value) ->
            val field =
                draft.appearance.glow.records
                    .single { it.key.name == name }
            SessionIntent.EditField(draft.id, FieldAddress(field.key), FieldValue.Present(value))
        }
    }

    private fun changes(
        settings: GlowSettings,
        event: GlowPanelEvent,
    ): Map<String, RawValue> =
        when (event) {
            is GlowPanelEvent.Enable -> mapOf("glowEnabled" to RawValue.BooleanValue(event.value))
            is GlowPanelEvent.Preset -> presetChanges(settings, event.value)
            is WaveformPanelEvent -> waveformChange(event)
            GlowPanelEvent.Reset -> defaultChanges(settings)
            else -> solidChanges(settings, event)
        }

    private fun solidChanges(
        settings: GlowSettings,
        event: GlowPanelEvent,
    ): Map<String, RawValue> =
        when (event) {
            is GlowPanelEvent.Style -> {
                custom("glowStyle", RawValue.Text(event.value.name))
            }

            is GlowPanelEvent.Intensity -> {
                custom(styleField(settings.style).intensity, RawValue.Integer(event.value))
            }

            is GlowPanelEvent.Width -> {
                custom(styleField(settings.style).width, RawValue.Integer(event.value))
            }

            is GlowPanelEvent.Animation -> {
                custom("glowAnimation", RawValue.Text(event.value.name))
            }

            is GlowPanelEvent.EditorPlacement -> {
                mapOf("glowEditorPlacement" to RawValue.Text(event.value.name))
            }

            is GlowPanelEvent.ToolWindowPlacement -> {
                mapOf("glowToolWindowPlacement" to RawValue.Text(event.value.name))
            }

            is GlowPanelEvent.Island -> {
                mapOf(
                    StudioGlowFields.islands.entries
                        .single { it.value == event.id }
                        .key to
                        RawValue.BooleanValue(event.value),
                )
            }

            is GlowPanelEvent.AllIslands -> {
                StudioGlowFields.islands.keys.associateWith { RawValue.BooleanValue(event.value) }
            }

            else -> {
                emptyMap()
            }
        }

    private fun custom(
        name: String,
        value: RawValue,
    ): Map<String, RawValue> = mapOf(name to value, "glowPreset" to RawValue.Text(GlowPreset.CUSTOM.name))

    private fun styleField(style: GlowStyle): GlowStyleFields = StudioGlowFields.styles.single { it.style == style }

    private fun presetChanges(
        settings: GlowSettings,
        preset: GlowPreset,
    ): Map<String, RawValue> {
        val selection = mapOf("glowPreset" to RawValue.Text(preset.name))
        if (preset == GlowPreset.CUSTOM) return selection
        val chosen = settings.withPresetValues(preset)
        val fields = styleField(chosen.style)
        return selection +
            mapOf(
                "glowStyle" to RawValue.Text(chosen.style.name),
                fields.intensity to RawValue.Integer(chosen.intensity.getValue(chosen.style)),
                fields.width to RawValue.Integer(chosen.width.getValue(chosen.style)),
                "glowAnimation" to RawValue.Text(chosen.animation.name),
            )
    }

    private fun waveformChange(event: WaveformPanelEvent): Map<String, RawValue> =
        mapOf(
            when (event) {
                is GlowPanelEvent.Shape -> "glowShape" to RawValue.Text(event.value.name)
                is GlowPanelEvent.Movement -> "waveformDirection" to RawValue.Text(event.value.name)
                is GlowPanelEvent.Baseline -> "waveformBaseline" to RawValue.Text(event.value.name)
                is GlowPanelEvent.TraceDensity -> "waveformTraceDensity" to RawValue.Integer(event.value)
                is GlowPanelEvent.TraceLength -> "waveformTraceLength" to RawValue.Integer(event.value)
                is GlowPanelEvent.Amplitude -> "waveformAmplitude" to RawValue.Integer(event.value)
                is GlowPanelEvent.WaveformIntensity -> "waveformIntensity" to RawValue.Integer(event.value)
                is GlowPanelEvent.LoopSeconds -> "waveformLoopSeconds" to RawValue.FloatBits(event.value.toRawBits())
            },
        )

    private fun defaultChanges(settings: GlowSettings): Map<String, RawValue> {
        val defaults = settings.withDefaults()
        return presetChanges(settings, defaults.preset) +
            mapOf(
                "glowShape" to RawValue.Text(defaults.shape.name),
                "waveformDirection" to RawValue.Text(defaults.waveformMovement.name),
                "waveformBaseline" to RawValue.Text(defaults.waveformBaseline.name),
                "waveformTraceDensity" to RawValue.Integer(defaults.waveformTraceDensity),
                "waveformTraceLength" to RawValue.Integer(defaults.waveformTraceLength),
                "waveformAmplitude" to RawValue.Integer(defaults.waveformAmplitude),
                "waveformIntensity" to RawValue.Integer(defaults.waveformIntensity),
                "waveformLoopSeconds" to RawValue.FloatBits(defaults.waveformLoopSeconds.toRawBits()),
            )
    }
}
