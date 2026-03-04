package dev.ayuislands.glow

enum class GlowTabMode(
    val displayName: String,
) {
    UNDERLINE("Underline"),
    FULL_BORDER("Full Border"),
    OFF("Off"),
    ;

    companion object {
        fun fromName(name: String): GlowTabMode = entries.firstOrNull { it.name == name } ?: UNDERLINE
    }
}
