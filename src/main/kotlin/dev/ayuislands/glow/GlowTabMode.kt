package dev.ayuislands.glow

enum class GlowTabMode(
    val displayName: String,
) {
    MINIMAL("Minimal"),
    FULL("Full"),
    OFF("Off"),
    ;

    companion object {
        private val LEGACY_MAP = mapOf("UNDERLINE" to MINIMAL, "FULL_BORDER" to FULL)

        fun fromName(name: String): GlowTabMode =
            entries.firstOrNull { it.name == name }
                ?: LEGACY_MAP[name]
                ?: MINIMAL
    }
}
