package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AyuVariant

/** Read-only provenance for a regenerated canonical resource, never an editable scheme's durable UUID. */
internal class AyuSchemeResource private constructor(
    val variant: AyuVariant,
    val scheme: AbstractColorsScheme,
    val parent: EditorColorsScheme,
    private val editable: AbstractColorsScheme,
    private val resolve: (String) -> EditorColorsScheme?,
) {
    fun isCurrent(): Boolean = isSelected() && hasProvenance() && isSelected()

    private fun isSelected(): Boolean =
        resolve(AyuEditorSchemeBinder.targetSchemeName(variant)) === scheme &&
            resolve(variant.editorSchemeResource) === editable

    private fun hasProvenance(): Boolean =
        editable.original === scheme &&
            scheme.parentScheme === parent &&
            editable.parentScheme === parent &&
            AyuPlugin.ID_STRING == scheme.metaProperties[AbstractColorsScheme.META_INFO_PLUGIN_ID]

    companion object {
        fun capture(
            variant: AyuVariant,
            scheme: AbstractColorsScheme,
            resolve: (String) -> EditorColorsScheme? = { EditorColorsManager.getInstance().getScheme(it) },
        ): AyuSchemeResource? {
            val editable = resolve(variant.editorSchemeResource) as? AbstractColorsScheme ?: return null
            val parent = scheme.parentScheme ?: return null
            return AyuSchemeResource(variant, scheme, parent, editable, resolve).takeIf { it.isCurrent() }
        }
    }
}
