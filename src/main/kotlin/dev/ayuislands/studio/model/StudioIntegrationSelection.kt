package dev.ayuislands.studio.model

/** Explicit enable transitions; ordinary reapplication never rearms suspended integrations. */
internal data class StudioIntegrationSelection(
    val enablesCodeGlance: Boolean = false,
    val enablesIndentRainbow: Boolean = false,
)
