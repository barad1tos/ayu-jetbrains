package dev.ayuislands.studio.model

internal enum class EffectOwner { SYSTEM, ACCENT, FONT, SYNTAX, VCS, GLOW, WORKSPACE, PLUGINS }

internal enum class LimitationReason { PRO_REQUIRED, EXTERNAL_THEME, UNSUPPORTED_PLATFORM, MISSING_PLUGIN }

internal data class EffectLimitation(
    val owner: EffectOwner,
    val reason: LimitationReason,
    /** An unavailable enhancement may still require its supported owner to restore managed native state. */
    val blocksEffect: Boolean = true,
)
