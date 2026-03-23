package dev.ayuislands.rotation

enum class AccentRotationMode {
    PRESET,
    RANDOM,
    ;

    companion object {
        fun fromName(name: String?): AccentRotationMode = entries.firstOrNull { it.name == name } ?: PRESET
    }
}
