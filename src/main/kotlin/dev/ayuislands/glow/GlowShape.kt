package dev.ayuislands.glow

/** Rendering shape for the island glow. Values persist by [Enum.name]. */
enum class GlowShape(
    val displayName: String,
) {
    SOLID("Solid"),
    WAVEFORM("ECG waveform"),
    ;

    companion object {
        fun fromName(name: String?): GlowShape = entries.firstOrNull { it.name == name } ?: SOLID
    }
}
