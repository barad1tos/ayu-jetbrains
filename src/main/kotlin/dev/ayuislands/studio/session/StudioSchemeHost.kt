package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.accent.AyuVariant

/** One native observation, captured without suspension between membership and active-scheme reads. */
internal data class FontSchemeView(
    val schemes: List<EditorColorsScheme>,
    val active: EditorColorsScheme,
    val variant: AyuVariant? = null,
)

/** Shared native I/O; candidate intent and ownership decisions remain with their participants. */
internal interface StudioSchemeHost {
    fun observe(): FontSchemeView

    fun newIdentity(): String

    fun refresh()
}
