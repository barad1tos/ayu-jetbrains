package dev.ayuislands.glow

import dev.ayuislands.accent.AccentHex
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.forPlayback
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.settings.loadGlowSettings

/** The runtime owner supplies only confirmed appearance and Accent for this manager. */
internal data class GlowRuntimeInput(
    val settings: GlowSettings,
    val externalAllowed: Boolean,
    val accent: AccentHex,
)

internal class GlowRuntimeSource(
    private val legacyState: () -> AyuIslandsState,
) {
    @Volatile
    var readAccepted: () -> GlowRuntimeInput? = { null }

    fun render(): GlowRenderInput {
        readAccepted()?.let { accepted ->
            return GlowRenderInput(accepted.settings, accepted.externalAllowed, confirmedAccent = accepted.accent)
        }
        val legacy = legacyState()
        val settings = loadGlowSettings(legacy, legacy.glowPreset ?: GlowPreset.WHISPER.name, islandIds)
        return GlowRenderInput(settings, legacy.isExternalGlowAllowed(), cachedAccent = legacy.trustedCachedAccent())
    }

    fun keystrokeAnimation(): GlowAnimation? {
        val accepted = readAccepted()?.settings
        if (accepted != null) return accepted.animation.takeIf { accepted.enabled }
        val legacy = legacyState()
        return if (legacy.glowEnabled) legacy.animation() else null
    }

    fun animation(): GlowAnimation = readAccepted()?.settings?.animation ?: legacyState().animation()

    fun enabledIslands(): (String) -> Boolean =
        readAccepted()?.settings?.let { it::isIslandEnabled } ?: legacyState()::isIslandEnabled

    fun isIslandEnabled(id: String): Boolean =
        readAccepted()?.settings?.isIslandEnabled(id) ?: legacyState().isIslandEnabled(id)

    fun waveform(): WaveformConfig = readAccepted()?.settings?.waveformConfig() ?: resolveWaveformConfig(legacyState())

    private companion object {
        val islandIds = GlowIsland.entries.map(GlowIsland::id)
    }
}

internal class GlowRenderInput(
    val settings: GlowSettings,
    val externalAllowed: Boolean,
    private val confirmedAccent: AccentHex? = null,
    private val cachedAccent: AccentHex? = null,
) {
    fun accentHex(
        requested: AccentHex?,
        fallback: () -> String,
    ): String = confirmedAccent?.value ?: requested?.value ?: cachedAccent?.value ?: fallback()
}

internal fun GlowSettings.isIslandEnabled(id: String): Boolean =
    GlowIsland.fromId(id)?.let { islandToggles[it.id] } == true

private fun AyuIslandsState.animation(): GlowAnimation =
    GlowAnimation.fromName(glowAnimation ?: GlowAnimation.NONE.name)

internal fun GlowSettings.waveformConfig(): WaveformConfig =
    WaveformConfig(
        movement = waveformMovement,
        baseline = waveformBaseline,
        traceDensity = waveformTraceDensity,
        traceLength = waveformTraceLength,
        amplitude = waveformAmplitude,
        intensity = waveformIntensity,
        loopSeconds = waveformLoopSeconds,
    ).forPlayback()
