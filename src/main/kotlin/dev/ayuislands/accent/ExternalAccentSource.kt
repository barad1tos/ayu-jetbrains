package dev.ayuislands.accent

enum class ExternalAccentSource(
    val displayName: String,
) {
    AUTOMATIC("Automatic"),
    MANUAL("Manual"),
    ;

    companion object {
        fun fromName(name: String?): ExternalAccentSource = entries.firstOrNull { it.name == name } ?: AUTOMATIC
    }
}
