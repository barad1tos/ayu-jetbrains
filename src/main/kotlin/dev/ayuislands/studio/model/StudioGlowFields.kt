package dev.ayuislands.studio.model

import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowStyle

internal data class GlowStyleFields(
    val style: GlowStyle,
    val intensity: String,
    val width: String,
)

internal data class GlowPaneBinding(
    val name: String,
    val field: GlowPaneField,
)

/** Scalar bindings only; eligibility, active-style selection and native consent are policies. */
internal object StudioGlowFields {
    val islands =
        mapOf(
            "glowEditor" to "Editor",
            "glowProject" to "Project",
            "glowTerminal" to "Terminal",
            "glowRun" to "Run",
            "glowDebug" to "Debug",
            "glowGit" to "Git",
            "glowServices" to "Services",
        )
    val styles =
        StudioList(
            listOf(
                GlowStyleFields(GlowStyle.SOFT, "softIntensity", "softWidth"),
                GlowStyleFields(GlowStyle.SHARP_NEON, "sharpNeonIntensity", "sharpNeonWidth"),
                GlowStyleFields(GlowStyle.GRADIENT, "gradientIntensity", "gradientWidth"),
            ),
        )
    val panes =
        StudioList(
            listOf(
                GlowPaneBinding("glowShape", GlowPaneField.SHAPE),
                GlowPaneBinding("waveformDirection", GlowPaneField.MOVEMENT),
                GlowPaneBinding("waveformBaseline", GlowPaneField.BASELINE),
                GlowPaneBinding("waveformAmplitude", GlowPaneField.AMPLITUDE),
                GlowPaneBinding("waveformIntensity", GlowPaneField.WAVEFORM_INTENSITY),
                GlowPaneBinding("waveformLoopSeconds", GlowPaneField.LOOP_SECONDS),
                GlowPaneBinding("waveformTraceDensity", GlowPaneField.TRACE_DENSITY),
                GlowPaneBinding("waveformTraceLength", GlowPaneField.TRACE_LENGTH),
            ),
        )
}
