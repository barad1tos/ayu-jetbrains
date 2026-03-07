package dev.ayuislands.indent

import dev.ayuislands.accent.AyuVariant

private const val HEX_RADIX = 16
private const val HEX_PAD_LENGTH = 2
private const val PAD_CHAR = '0'
private const val INDENT_COUNT = 6
private const val MIN_ALPHA = 1
private const val MAX_ALPHA = 255

data class IndentPalette(
    val errorColor: String,
    val accentColor: String,
) {
    fun toColorStrings(alpha: Int): List<String> {
        val errorAlphaHex = alpha.toString(HEX_RADIX).uppercase().padStart(HEX_PAD_LENGTH, PAD_CHAR)
        val errorStr = errorAlphaHex + errorColor

        val indentColors =
            (1..INDENT_COUNT).map { step ->
                val stepAlpha = (alpha * step / INDENT_COUNT).coerceIn(MIN_ALPHA, MAX_ALPHA)
                val stepAlphaHex = stepAlpha.toString(HEX_RADIX).uppercase().padStart(HEX_PAD_LENGTH, PAD_CHAR)
                stepAlphaHex + accentColor
            }

        return listOf(errorStr) + indentColors
    }

    companion object {
        private val ERROR_COLORS =
            mapOf(
                AyuVariant.MIRAGE to "F27983",
                AyuVariant.DARK to "F26D78",
                AyuVariant.LIGHT to "FF7383",
            )

        fun forAccent(
            accentHex: String,
            variant: AyuVariant,
        ): IndentPalette {
            val accent = accentHex.removePrefix("#")
            val error = ERROR_COLORS[variant] ?: ERROR_COLORS.getValue(AyuVariant.MIRAGE)
            return IndentPalette(errorColor = error, accentColor = accent)
        }
    }
}
