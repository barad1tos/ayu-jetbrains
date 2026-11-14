package dev.ayuislands.studio.model

internal data class StudioEffectRoute(
    val owners: StudioList<EffectOwner> = StudioList(emptyList()),
    val workspace: StudioList<FieldAddress> = StudioList(emptyList()),
    val integrations: StudioIntegrationSelection = StudioIntegrationSelection(),
    val glow: StudioGlowSelection = StudioGlowSelection(),
    val syncAppearance: Boolean = false,
)

/** Ordinary reapplication never rearms suspended integrations. */
internal data class StudioIntegrationSelection(
    val enablesCodeGlance: Boolean = false,
    val enablesIndentRainbow: Boolean = false,
)
