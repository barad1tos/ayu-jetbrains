package dev.ayuislands.glow

data class GlowPreset(
    val name: String,
    val style: GlowStyle,
    val intensity: Int,
    val width: Int,
    val animation: GlowAnimation,
    val enabledIslands: Set<String>,
    val isBuiltIn: Boolean = false,
) {
    companion object {
        val SUBTLE = GlowPreset(
            name = "Subtle",
            style = GlowStyle.SOFT,
            intensity = 25,
            width = 6,
            animation = GlowAnimation.NONE,
            enabledIslands = setOf("Editor"),
            isBuiltIn = true,
        )

        val BALANCED = GlowPreset(
            name = "Balanced",
            style = GlowStyle.SOFT,
            intensity = 40,
            width = 10,
            animation = GlowAnimation.BREATHE,
            enabledIslands = setOf("Editor"),
            isBuiltIn = true,
        )

        val NEON = GlowPreset(
            name = "Neon",
            style = GlowStyle.SHARP_NEON,
            intensity = 85,
            width = 20,
            animation = GlowAnimation.NONE,
            enabledIslands = setOf("Editor"),
            isBuiltIn = true,
        )

        val BUILT_IN = listOf(SUBTLE, BALANCED, NEON)

        fun serializePresets(presets: List<GlowPreset>): String {
            return presets.joinToString(";") { preset ->
                "${preset.name}|${preset.style.name}|${preset.intensity}|${preset.width}|${preset.animation.name}|${preset.enabledIslands.joinToString(",")}"
            }
        }

        fun deserializePresets(serialized: String): List<GlowPreset> {
            if (serialized.isBlank()) return emptyList()
            return serialized.split(";").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size < 6) return@mapNotNull null
                GlowPreset(
                    name = parts[0],
                    style = GlowStyle.fromName(parts[1]),
                    intensity = parts[2].toIntOrNull() ?: 40,
                    width = parts[3].toIntOrNull() ?: 10,
                    animation = GlowAnimation.fromName(parts[4]),
                    enabledIslands = parts[5].split(",").filter { it.isNotBlank() }.toSet(),
                )
            }
        }
    }
}
