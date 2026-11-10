package dev.ayuislands.settings

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset

/** Requested values stay raw, including dormant categories and unknown preset names. */
internal data class VcsPanelSettings(
    val enabled: Boolean,
    val diffPreset: String?,
    val mergePreset: String?,
    val blamePreset: String?,
    val intensities: Map<VcsColorCategory, Int>,
)

internal data class VcsPanelView(
    val settings: VcsPanelSettings,
    val isEditable: Boolean,
    val isLicensed: Boolean,
)

/** Comparison samples are independent from the draft controls and never claim to reproduce a disabled baseline. */
internal data class VcsPreviewInput(
    val variant: AyuVariant,
    val intensities: VcsPreviewIntensities,
    val isEnabled: Boolean,
    val isVisible: Boolean,
)

internal sealed interface VcsPanelEvent {
    data class Enabled(
        val value: Boolean,
    ) : VcsPanelEvent

    data class Preset(
        val section: VcsSection,
        val value: VcsColorPreset,
    ) : VcsPanelEvent

    data class Intensity(
        val category: VcsColorCategory,
        val value: Int,
    ) : VcsPanelEvent

    data class Expanded(
        val section: VcsSection,
        val value: Boolean,
    ) : VcsPanelEvent

    data object Unlock : VcsPanelEvent
}

/** Reuses the legacy premium copy while requiring an explicit entitlement observation. */
internal fun vcsPremiumGate(isLicensed: Boolean): PremiumFeatureGate =
    PremiumFeatureGate(
        featureName = "VCS color customization",
        lockedDescription =
            "VCS color customization is a Pro feature. " +
                "Preview the available diff, file-status, gutter, conflict, and blame controls here.",
        requestMessage = "Unlock VCS color intensity customization",
        isUnlocked = isLicensed,
    )
