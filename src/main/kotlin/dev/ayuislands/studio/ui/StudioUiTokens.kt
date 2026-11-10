package dev.ayuislands.studio.ui

import com.intellij.util.ui.JBUI

/** Measured gaps, scaled once when the native view is constructed. */
internal data class StudioSpacing(
    val fieldGap: Int,
    val paletteGap: Int,
    val groupGap: Int,
    val pairedGap: Int,
)

internal object StudioUiTokens {
    fun spacing(scale: (Int) -> Int = { JBUI.scale(it) }): StudioSpacing =
        StudioSpacing(
            fieldGap = scale(FIELD_GAP),
            paletteGap = scale(PALETTE_GAP),
            groupGap = scale(GROUP_GAP),
            pairedGap = scale(PAIRED_GAP),
        )

    private const val FIELD_GAP = 6
    private const val PALETTE_GAP = 5
    private const val GROUP_GAP = 14
    private const val PAIRED_GAP = 18
}
