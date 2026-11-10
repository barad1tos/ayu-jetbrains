package dev.ayuislands.font

/** Declaration order is the persisted customization order; unknown later slots remain opaque. */
internal enum class FontSettingSlot {
    SIZE,
    SPACING,
    LIGATURES,
    WEIGHT,
    FAMILY,
    ;

    val index: Int get() = ordinal
}

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
        val base = "$fontSize|$lineSpacing|$enableLigatures|${weight.name}"
        if (!preset.isCurated) return "$base|$fontFamily"
        return base
    }

    companion object {
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
                    parts.getOrNull(FontSettingSlot.FAMILY.index)?.takeIf { it.isNotBlank() }
                        ?: preset.fontFamily,
                fontSize = parts.getOrNull(FontSettingSlot.SIZE.index)?.toFloatOrNull() ?: preset.fontSize,
                lineSpacing = parts.getOrNull(FontSettingSlot.SPACING.index)?.toFloatOrNull() ?: preset.lineSpacing,
                enableLigatures =
                    parts.getOrNull(FontSettingSlot.LIGATURES.index)?.toBooleanStrictOrNull()
                        ?: preset.enableLigatures,
                weight =
                    parts.getOrNull(FontSettingSlot.WEIGHT.index)?.let { FontWeight.fromName(it) }
                        ?: preset.defaultWeight,
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
