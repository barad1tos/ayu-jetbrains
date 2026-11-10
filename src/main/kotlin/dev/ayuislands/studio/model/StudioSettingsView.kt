package dev.ayuislands.studio.model

/** Stable native section identities; shared preferences are outside appearance profiles. */
internal enum class StudioSettingsSection(
    val title: String,
    val isShared: Boolean = false,
) {
    ACCENT("Accent"),
    FONT("Font"),
    GLOW("Glow"),
    SYNTAX("Syntax"),
    VCS("VCS"),
    WORKSPACE("Workspace", isShared = true),
    PLUGINS("Plugins", isShared = true),
}

/** Explicit presentation inputs; no accepted settings or UI metadata are written by the shell. */
internal data class StudioSettingsView(
    val profiles: StudioProfileView,
    val section: StudioSettingsSection,
    val authority: AuthorityStatus,
    val acceptedAccentHex: String,
)
