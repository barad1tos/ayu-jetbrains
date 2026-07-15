package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.DEFAULT_TRACE_DENSITY
import dev.ayuislands.glow.waveform.DEFAULT_WAVEFORM_AMPLITUDE
import dev.ayuislands.glow.waveform.DEFAULT_WAVEFORM_INTENSITY
import dev.ayuislands.glow.waveform.DEFAULT_WAVEFORM_LOOP_SECONDS
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformDirection
import dev.ayuislands.glow.waveform.WaveformMotion

internal data class GlowSettings(
    val enabled: Boolean = false,
    val shape: GlowShape = GlowShape.SOLID,
    val preset: GlowPreset = GlowPreset.WHISPER,
    val style: GlowStyle = GlowStyle.SOFT,
    val intensity: Map<GlowStyle, Int> = emptyMap(),
    val width: Map<GlowStyle, Int> = emptyMap(),
    val animation: GlowAnimation = GlowAnimation.NONE,
    val islandToggles: Map<String, Boolean> = emptyMap(),
    val editorPlacement: GlowPlacement = GlowPlacement.ISLAND,
    val toolWindowPlacement: GlowPlacement = GlowPlacement.ISLAND,
    val waveformMotion: WaveformMotion = WaveformMotion.MONITOR,
    val waveformDirection: WaveformDirection = WaveformDirection.CLOCKWISE,
    val waveformBaseline: WaveformBaseline = WaveformBaseline.OUTSIDE,
    val waveformTraceDensity: Int = DEFAULT_TRACE_DENSITY,
    val waveformAmplitude: Int = DEFAULT_WAVEFORM_AMPLITUDE,
    val waveformIntensity: Int = DEFAULT_WAVEFORM_INTENSITY,
    val waveformLoopSeconds: Float = DEFAULT_WAVEFORM_LOOP_SECONDS,
) {
    fun withPresetValues(preset: GlowPreset): GlowSettings {
        val presetStyle = preset.style ?: return this
        val presetIntensity = preset.intensity ?: return this
        val presetWidth = preset.width ?: return this
        val presetAnimation = preset.animation ?: return this
        return copy(
            style = presetStyle,
            intensity = intensity + (presetStyle to presetIntensity),
            width = width + (presetStyle to presetWidth),
            animation = presetAnimation,
        )
    }

    fun waveformValue(): WaveformSettingsValue =
        WaveformSettingsValue(
            shape = shape,
            motion = waveformMotion,
            direction = waveformDirection,
            baseline = waveformBaseline,
            traceDensity = waveformTraceDensity,
            amplitude = waveformAmplitude,
            intensity = waveformIntensity,
            loopSeconds = waveformLoopSeconds,
        )

    fun withDefaults(): GlowSettings =
        withPresetValues(GlowPreset.WHISPER).copy(
            shape = GlowShape.SOLID,
            preset = GlowPreset.WHISPER,
            waveformMotion = WaveformMotion.MONITOR,
            waveformDirection = WaveformDirection.CLOCKWISE,
            waveformBaseline = WaveformBaseline.OUTSIDE,
            waveformTraceDensity = DEFAULT_TRACE_DENSITY,
            waveformAmplitude = DEFAULT_WAVEFORM_AMPLITUDE,
            waveformIntensity = DEFAULT_WAVEFORM_INTENSITY,
            waveformLoopSeconds = DEFAULT_WAVEFORM_LOOP_SECONDS,
        )
}

internal fun loadGlowSettings(
    state: AyuIslandsState,
    presetName: String,
    islandIds: List<String>,
): GlowSettings =
    GlowSettings(
        enabled = state.glowEnabled,
        shape = GlowShape.fromName(state.glowShape),
        preset = GlowPreset.fromName(presetName),
        style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name),
        intensity = GlowStyle.entries.associateWith { state.getIntensityForStyle(it) },
        width = GlowStyle.entries.associateWith { state.getWidthForStyle(it) },
        animation = GlowAnimation.fromName(state.glowAnimation ?: GlowAnimation.NONE.name),
        islandToggles = islandIds.associateWith { state.isIslandEnabled(it) },
        editorPlacement = GlowPlacement.fromName(state.glowEditorPlacement),
        toolWindowPlacement = GlowPlacement.fromName(state.glowToolWindowPlacement),
        waveformMotion = WaveformMotion.fromName(state.waveformMotion),
        waveformDirection = WaveformDirection.fromName(state.waveformDirection),
        waveformBaseline = WaveformBaseline.fromName(state.waveformBaseline),
        waveformTraceDensity = state.waveformTraceDensity,
        waveformAmplitude = state.waveformAmplitude,
        waveformIntensity = state.waveformIntensity,
        waveformLoopSeconds = state.waveformLoopSeconds,
    )

internal class GlowVisibility {
    val solidShape = AtomicBooleanProperty(true)
    val solidControls = AtomicBooleanProperty(false)
    val waveform = AtomicBooleanProperty(false)
    val direction = AtomicBooleanProperty(false)
    val loopDuration = AtomicBooleanProperty(false)
    val placement = AtomicBooleanProperty(true)
    val targets = AtomicBooleanProperty(false)

    fun refresh(
        settings: GlowSettings,
        licensed: Boolean,
    ) {
        val isWaveform = settings.shape == GlowShape.WAVEFORM
        solidShape.set(!isWaveform)
        waveform.set(isWaveform)
        direction.set(isWaveform && settings.waveformMotion == WaveformMotion.MONITOR)
        loopDuration.set(isWaveform && settings.waveformMotion == WaveformMotion.MONITOR)
        solidControls.set(!isWaveform && (!licensed || settings.preset == GlowPreset.CUSTOM))
        placement.set(!isWaveform)
        targets.set(isWaveform || !licensed || settings.preset == GlowPreset.CUSTOM)
    }
}
