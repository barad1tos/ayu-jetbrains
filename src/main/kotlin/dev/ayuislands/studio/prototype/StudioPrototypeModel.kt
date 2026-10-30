package dev.ayuislands.studio.prototype

import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.syntax.SyntaxPreset
import java.util.Collections

private const val SAMPLE_SOFT_INTENSITY = 20
private const val SAMPLE_NEON_INTENSITY = 35
private const val SAMPLE_GRADIENT_INTENSITY = 25

/** Editable samples for the isolated design preview; never a persisted settings projection. */
internal data class StudioPrototypeAppearance(
    val accentHex: String = "#FFCD66",
    val fontSize: Float = 14f,
    val glowEnabled: Boolean = true,
    val glowStyle: GlowStyle = GlowStyle.SOFT,
    val glowIntensities: Map<GlowStyle, Int> =
        mapOf(
            GlowStyle.SOFT to SAMPLE_SOFT_INTENSITY,
            GlowStyle.SHARP_NEON to SAMPLE_NEON_INTENSITY,
            GlowStyle.GRADIENT to SAMPLE_GRADIENT_INTENSITY,
        ),
    val syntaxPreset: SyntaxPreset = SyntaxPreset.AMBIENT,
    val vcsDiffIntensity: Int = 33,
)

internal enum class StudioPrototypeSection(
    val displayName: String,
) {
    ACCENT("Accent"),
    FONT("Font"),
    GLOW("Glow"),
    SYNTAX("Syntax"),
    VCS("VCS"),
}

internal data class StudioPrototypeProfile(
    val name: String,
    val includedSections: Set<StudioPrototypeSection>,
    val appearance: StudioPrototypeAppearance,
)

/** Detaches caller-owned maps before a value enters the preview session. */
internal fun StudioPrototypeAppearance.ownedCopy(): StudioPrototypeAppearance =
    copy(glowIntensities = Collections.unmodifiableMap(glowIntensities.toMap()))

internal fun StudioPrototypeAppearance.withSections(
    source: StudioPrototypeAppearance,
    sections: Set<StudioPrototypeSection>,
): StudioPrototypeAppearance =
    copy(
        accentHex = if (StudioPrototypeSection.ACCENT in sections) source.accentHex else accentHex,
        fontSize = if (StudioPrototypeSection.FONT in sections) source.fontSize else fontSize,
        glowEnabled = if (StudioPrototypeSection.GLOW in sections) source.glowEnabled else glowEnabled,
        glowStyle = if (StudioPrototypeSection.GLOW in sections) source.glowStyle else glowStyle,
        glowIntensities = if (StudioPrototypeSection.GLOW in sections) source.glowIntensities else glowIntensities,
        syntaxPreset = if (StudioPrototypeSection.SYNTAX in sections) source.syntaxPreset else syntaxPreset,
        vcsDiffIntensity = if (StudioPrototypeSection.VCS in sections) source.vcsDiffIntensity else vcsDiffIntensity,
    ).ownedCopy()

/** Explicit scenarios affect only the preview's local values. */
internal enum class StudioPrototypeOutcome {
    SUCCESS,
    FONT_UNAVAILABLE,
    REJECTED,
}

internal enum class StudioPrototypeResult(
    val message: String,
) {
    READY("Changes stay in this preview"),
    APPLIED("Applied in preview"),
    PARTIAL("Partially available in preview — Font kept, profile values retained"),
    REJECTED("Preview could not be applied — draft kept"),
    DIRTY_BLOCKED("Review changes before switching profiles"),
    NO_PROJECT("Open a project to activate a profile in preview"),
    UNKNOWN_PROFILE("The selected preview profile is unavailable"),
}

internal enum class StudioPrototypeEditResult(
    val message: String,
) {
    STAGED("Unsaved profile"),
    NAME_REQUIRED("Enter a profile name"),
    NAME_EXISTS("A profile with this name already exists"),
    NO_ACTIVE_PROFILE("Activate a profile before updating it"),
}
