package dev.ayuislands.accent

data class AccentColor(
    val hex: String,
    val name: String,
)

val AYU_ACCENT_PRESETS: List<AccentColor> =
    listOf(
        AccentColor("#F28779", "Coral"),
        AccentColor("#F29E74", "Amber"),
        AccentColor("#FFA659", "Orange"),
        AccentColor("#FFCD66", "Gold"),
        AccentColor("#D9BE98", "Sand"),
        AccentColor("#DFBFFF", "Lavender"),
        AccentColor("#D5FF80", "Lime"),
        AccentColor("#95E6CB", "Mint"),
        AccentColor("#73D0FF", "Sky"),
        AccentColor("#5CCFE6", "Cyan"),
        AccentColor("#F27983", "Rose"),
        AccentColor("#8A9199", "Slate"),
    )
