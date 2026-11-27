package dev.ayuislands.font

/** Declaration order matches the persisted per-preset customization. */
internal enum class FontSettingSlot {
    SIZE,
    SPACING,
    LIGATURES,
    WEIGHT,
    FAMILY,
    ;

    val index: Int get() = ordinal
}

internal object FontEditorLimits {
    const val MIN_SIZE = 8
    const val MAX_SIZE = 30
    const val MIN_SPACING = 0.8
    const val MAX_SPACING = 2.0
    const val SPACING_STEP = 0.1
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
