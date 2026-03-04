package dev.ayuislands.glow

enum class GlowAnimation(
    val displayName: String,
) {
    NONE("None"),
    PULSE("Pulse"),
    BREATHE("Breathe"),
    REACTIVE("Reactive"),
    ;

    companion object {
        fun fromName(name: String): GlowAnimation = entries.firstOrNull { it.name == name } ?: NONE
    }
}
