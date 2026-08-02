package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.accent.AyuVariant

internal object AyuEditorSchemeScope {
    fun activeScheme(): EditorColorsScheme? {
        val variant = AyuVariant.detect() ?: return null
        val scheme = EditorColorsManager.getInstance().globalScheme

        return scheme.takeIf {
            AyuEditorSchemeBinder.isSchemeForVariant(it.name, variant)
        }
    }
}
