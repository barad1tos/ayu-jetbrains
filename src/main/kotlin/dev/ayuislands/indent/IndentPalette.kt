package dev.ayuislands.indent

import dev.ayuislands.accent.AyuVariant

private const val HEX_RADIX = 16
private const val HEX_PAD_LENGTH = 2
private const val PAD_CHAR = '0'

data class IndentPalette(
    val errorColor: String,
    val indentColors: List<String>,
) {
    fun toColorStrings(alpha: Int): List<String> {
        val alphaHex = alpha.toString(HEX_RADIX).uppercase().padStart(HEX_PAD_LENGTH, PAD_CHAR)
        return listOf(alphaHex + errorColor) + indentColors.map { alphaHex + it }
    }

    companion object {
        // Warm-cool alternating order: Orange, Blue, Yellow, Purple, Red, Green
        // Error color = Red7 from each variant

        private val MIRAGE =
            IndentPalette(
                errorColor = "F27983",
                indentColors =
                    listOf(
                        "FFA659",
                        "80BFFF",
                        "FFCC66",
                        "DFBFFF",
                        "F27983",
                        "87D96C",
                    ),
            )

        private val DARK =
            IndentPalette(
                errorColor = "F26D78",
                indentColors =
                    listOf(
                        "FF8F40",
                        "73B8FF",
                        "E6B450",
                        "D2A6FF",
                        "F26D78",
                        "70BF56",
                    ),
            )

        private val LIGHT =
            IndentPalette(
                errorColor = "FF7383",
                indentColors =
                    listOf(
                        "FA8532",
                        "478ACC",
                        "F29718",
                        "A37ACC",
                        "FF7383",
                        "6CBF43",
                    ),
            )

        fun forVariant(variant: AyuVariant): IndentPalette =
            when (variant) {
                AyuVariant.MIRAGE -> MIRAGE
                AyuVariant.DARK -> DARK
                AyuVariant.LIGHT -> LIGHT
            }
    }
}
