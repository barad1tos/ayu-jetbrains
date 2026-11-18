package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioGlowEdit
import dev.ayuislands.studio.model.StudioGlowField
import dev.ayuislands.studio.model.StudioGlowStyleSlots

internal object StudioGlowEditing {
    fun edit(
        draft: DraftRecord,
        edit: StudioGlowEdit,
    ): DraftRecord {
        val settings = StudioGlowProjection.settings(draft.appearance)
        val storedPreset = StudioAppearanceFields.field(draft.appearance, StudioGlowField.PRESET.key).value
        val markCustom = settings.preset != GlowPreset.CUSTOM || storedPreset == RawValue.Null
        return changes(settings, edit, markCustom).entries.fold(draft) { pending, (field, value) ->
            StudioDraftPolicy.edit(pending, FieldAddress(field.key), FieldValue.Present(value))
        }
    }

    private fun changes(
        settings: GlowSettings,
        edit: StudioGlowEdit,
        markCustom: Boolean,
    ): Map<StudioGlowField, RawValue> =
        when (edit) {
            is StudioGlowEdit.Enable -> {
                mapOf(StudioGlowField.ENABLED to RawValue.BooleanValue(edit.enabled))
            }

            is StudioGlowEdit.Preset -> {
                presetValues(settings, edit.value)
            }

            is StudioGlowEdit.Style -> {
                manualSolidValue(StudioGlowField.STYLE, RawValue.Text(edit.value.name), markCustom)
            }

            is StudioGlowEdit.Intensity -> {
                val slots = StudioGlowStyleSlots.forStyle(settings.style)
                manualSolidValue(slots.intensity, RawValue.Integer(edit.value), markCustom)
            }

            is StudioGlowEdit.Width -> {
                val slots = StudioGlowStyleSlots.forStyle(settings.style)
                manualSolidValue(slots.width, RawValue.Integer(edit.value), markCustom)
            }

            is StudioGlowEdit.Animation -> {
                manualSolidValue(StudioGlowField.ANIMATION, RawValue.Text(edit.value.name), markCustom)
            }

            is StudioGlowEdit.EditorPlacement -> {
                mapOf(StudioGlowField.EDITOR_PLACEMENT to RawValue.Text(edit.value.name))
            }

            is StudioGlowEdit.ToolWindowPlacement -> {
                mapOf(StudioGlowField.TOOL_WINDOW_PLACEMENT to RawValue.Text(edit.value.name))
            }

            is StudioGlowEdit.Targets -> {
                edit.targets.associate { target -> target.field to RawValue.BooleanValue(edit.enabled) }
            }

            is StudioGlowEdit.Waveform -> {
                mapOf(waveformValue(edit))
            }

            StudioGlowEdit.Reset -> {
                defaultValues(settings.withDefaults())
            }
        }

    private fun manualSolidValue(
        field: StudioGlowField,
        value: RawValue,
        markCustom: Boolean,
    ): Map<StudioGlowField, RawValue> =
        buildMap {
            put(field, value)
            if (markCustom) {
                put(StudioGlowField.PRESET, RawValue.Text(GlowPreset.CUSTOM.name))
            }
        }

    private fun presetValues(
        settings: GlowSettings,
        preset: GlowPreset,
    ): Map<StudioGlowField, RawValue> =
        if (preset == GlowPreset.CUSTOM) {
            mapOf(StudioGlowField.PRESET to RawValue.Text(preset.name))
        } else {
            solidValues(settings.withPresetValues(preset).copy(preset = preset))
        }

    private fun solidValues(settings: GlowSettings): Map<StudioGlowField, RawValue> {
        val slots = StudioGlowStyleSlots.forStyle(settings.style)
        return mapOf(
            StudioGlowField.PRESET to RawValue.Text(settings.preset.name),
            StudioGlowField.STYLE to RawValue.Text(settings.style.name),
            slots.intensity to RawValue.Integer(settings.intensity.getValue(settings.style)),
            slots.width to RawValue.Integer(settings.width.getValue(settings.style)),
            StudioGlowField.ANIMATION to RawValue.Text(settings.animation.name),
        )
    }

    private fun waveformValue(edit: StudioGlowEdit.Waveform): Pair<StudioGlowField, RawValue> =
        when (edit) {
            is StudioGlowEdit.Shape -> StudioGlowField.SHAPE to RawValue.Text(edit.value.name)
            is StudioGlowEdit.Movement -> StudioGlowField.WAVEFORM_MOVEMENT to RawValue.Text(edit.value.name)
            is StudioGlowEdit.Baseline -> StudioGlowField.WAVEFORM_BASELINE to RawValue.Text(edit.value.name)
            is StudioGlowEdit.TraceDensity -> StudioGlowField.TRACE_DENSITY to RawValue.Integer(edit.value)
            is StudioGlowEdit.TraceLength -> StudioGlowField.TRACE_LENGTH to RawValue.Integer(edit.value)
            is StudioGlowEdit.Amplitude -> StudioGlowField.AMPLITUDE to RawValue.Integer(edit.value)
            is StudioGlowEdit.WaveformIntensity -> StudioGlowField.WAVEFORM_INTENSITY to RawValue.Integer(edit.value)
            is StudioGlowEdit.LoopSeconds -> StudioGlowField.LOOP_SECONDS to RawValue.FloatBits(edit.value.toRawBits())
        }

    private fun defaultValues(settings: GlowSettings): Map<StudioGlowField, RawValue> =
        solidValues(settings) +
            mapOf(
                StudioGlowField.SHAPE to RawValue.Text(settings.shape.name),
                StudioGlowField.WAVEFORM_MOVEMENT to RawValue.Text(settings.waveformMovement.name),
                StudioGlowField.WAVEFORM_BASELINE to RawValue.Text(settings.waveformBaseline.name),
                StudioGlowField.TRACE_DENSITY to RawValue.Integer(settings.waveformTraceDensity),
                StudioGlowField.TRACE_LENGTH to RawValue.Integer(settings.waveformTraceLength),
                StudioGlowField.AMPLITUDE to RawValue.Integer(settings.waveformAmplitude),
                StudioGlowField.WAVEFORM_INTENSITY to RawValue.Integer(settings.waveformIntensity),
                StudioGlowField.LOOP_SECONDS to RawValue.FloatBits(settings.waveformLoopSeconds.toRawBits()),
            )
}
