package dev.ayuislands.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAccentProvider
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.persistence.AyuSettingsView

/** Accepted read projection. Legacy XML is imported by Studio and is never written by this service. */
@Service
internal class AyuIslandsSettings {
    val state: AyuSettingsView? get() = StudioService.getInstance().acceptedView?.settings

    fun getAccentForVariant(variant: AyuVariant): String? {
        val accepted = state ?: return null
        if (accepted.effectiveFollowSystemAccent()) SystemAccentProvider.resolve()?.let { return it }
        return when (variant) {
            AyuVariant.MIRAGE -> accepted.mirageAccent
            AyuVariant.DARK -> accepted.darkAccent
            AyuVariant.LIGHT -> accepted.lightAccent
        } ?: variant.defaultAccent
    }

    companion object {
        fun getInstance(): AyuIslandsSettings = service()
    }
}

internal fun freshSettingsState(): AyuIslandsState {
    val waveform = WaveformConfig()
    return AyuIslandsState().apply {
        waveformDirection = waveform.movement.name
        waveformBaseline = waveform.baseline.name
        waveformTraceDensity = waveform.traceDensity
        waveformTraceLength = waveform.traceLength
        waveformAmplitude = waveform.amplitude
        waveformIntensity = waveform.intensity
        waveformLoopSeconds = waveform.loopSeconds
    }
}
