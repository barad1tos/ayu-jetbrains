package dev.ayuislands.font

/** Resolved font settings ready for application. */
data class FontSettings(
    val preset: FontPreset,
    val fontFamily: String,
    val fontSize: Float,
    val lineSpacing: Float,
    val enableLigatures: Boolean,
    val weight: FontWeight,
    val applyToConsole: Boolean,
) {
    /** Encode customizable fields (excluding preset and console) for state persistence. */
    fun encode(): String {
        val base = "${fontSize.toInt()}|$lineSpacing|$enableLigatures|${weight.name}"
        if (!preset.isCurated) return "$base|$fontFamily"
        return base
    }

    companion object {
        private const val IDX_SIZE = 0
        private const val IDX_SPACING = 1
        private const val IDX_LIGATURES = 2
        private const val IDX_WEIGHT = 3
        private const val IDX_FONT_FAMILY = 4

        /** Decode per-preset custom settings, falling back to preset defaults. */
        fun decode(
            encoded: String?,
            preset: FontPreset,
        ): FontSettings {
            if (encoded == null) return fromPreset(preset)
            val parts = encoded.split("|")
            return FontSettings(
                preset = preset,
                fontFamily =
                    parts.getOrNull(IDX_FONT_FAMILY)?.takeIf { it.isNotBlank() }
                        ?: preset.fontFamily,
                fontSize = parts.getOrNull(IDX_SIZE)?.toFloatOrNull() ?: preset.fontSize,
                lineSpacing = parts.getOrNull(IDX_SPACING)?.toFloatOrNull() ?: preset.lineSpacing,
                enableLigatures = parts.getOrNull(IDX_LIGATURES)?.toBooleanStrictOrNull() ?: preset.enableLigatures,
                weight = parts.getOrNull(IDX_WEIGHT)?.let { FontWeight.fromName(it) } ?: preset.defaultWeight,
                applyToConsole = false,
            )
        }

        /** Create settings from preset defaults. */
        fun fromPreset(preset: FontPreset): FontSettings =
            FontSettings(
                preset = preset,
                fontFamily = preset.fontFamily,
                fontSize = preset.fontSize,
                lineSpacing = preset.lineSpacing,
                enableLigatures = preset.enableLigatures,
                weight = preset.defaultWeight,
                applyToConsole = false,
            )
    }
}
