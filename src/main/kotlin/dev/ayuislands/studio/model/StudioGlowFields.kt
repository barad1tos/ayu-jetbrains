package dev.ayuislands.studio.model

import dev.ayuislands.glow.GlowStyle

internal enum class StudioGlowField(
    legacyName: String,
) {
    ENABLED("glowEnabled"),
    SHAPE("glowShape"),
    PRESET("glowPreset"),
    STYLE("glowStyle"),
    ANIMATION("glowAnimation"),
    SOFT_INTENSITY("softIntensity"),
    SHARP_NEON_INTENSITY("sharpNeonIntensity"),
    GRADIENT_INTENSITY("gradientIntensity"),
    SOFT_WIDTH("softWidth"),
    SHARP_NEON_WIDTH("sharpNeonWidth"),
    GRADIENT_WIDTH("gradientWidth"),
    EDITOR("glowEditor"),
    PROJECT("glowProject"),
    TERMINAL("glowTerminal"),
    RUN("glowRun"),
    DEBUG("glowDebug"),
    GIT("glowGit"),
    SERVICES("glowServices"),
    EDITOR_PLACEMENT("glowEditorPlacement"),
    TOOL_WINDOW_PLACEMENT("glowToolWindowPlacement"),
    WAVEFORM_MOVEMENT("waveformDirection"),
    WAVEFORM_BASELINE("waveformBaseline"),
    TRACE_DENSITY("waveformTraceDensity"),
    TRACE_LENGTH("waveformTraceLength"),
    AMPLITUDE("waveformAmplitude"),
    WAVEFORM_INTENSITY("waveformIntensity"),
    LOOP_SECONDS("waveformLoopSeconds"),
    ;

    val key = LegacyFieldKey(LegacyStore.SETTINGS, legacyName)
}

internal enum class StudioGlowTarget(
    val islandId: String,
    val field: StudioGlowField,
) {
    EDITOR("Editor", StudioGlowField.EDITOR),
    PROJECT("Project", StudioGlowField.PROJECT),
    TERMINAL("Terminal", StudioGlowField.TERMINAL),
    RUN("Run", StudioGlowField.RUN),
    DEBUG("Debug", StudioGlowField.DEBUG),
    GIT("Git", StudioGlowField.GIT),
    SERVICES("Services", StudioGlowField.SERVICES),
}

internal enum class StudioGlowStyleSlots(
    val style: GlowStyle,
    val intensity: StudioGlowField,
    val width: StudioGlowField,
) {
    SOFT(GlowStyle.SOFT, StudioGlowField.SOFT_INTENSITY, StudioGlowField.SOFT_WIDTH),
    SHARP_NEON(GlowStyle.SHARP_NEON, StudioGlowField.SHARP_NEON_INTENSITY, StudioGlowField.SHARP_NEON_WIDTH),
    GRADIENT(GlowStyle.GRADIENT, StudioGlowField.GRADIENT_INTENSITY, StudioGlowField.GRADIENT_WIDTH),
    ;

    companion object {
        fun forStyle(style: GlowStyle): StudioGlowStyleSlots = entries.single { it.style == style }
    }
}
