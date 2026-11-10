package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme

/** Read-only native selection capture; recovery keeps this object rather than resolving its replacement. */
internal class SchemeTarget private constructor(
    val source: SchemeSource,
    val scheme: AbstractColorsScheme,
    val name: String,
    val parent: EditorColorsScheme?,
    private val resolve: (SchemeSource) -> EditorColorsScheme?,
) {
    fun isSelected(): Boolean = resolve(source) === scheme && scheme.name == name && canRestore()

    /** A rename or active-source change does not transfer ownership of retained local fields. */
    fun canRestore(): Boolean = scheme.parentScheme === parent

    companion object {
        fun capture(
            source: SchemeSource,
            resolve: (SchemeSource) -> EditorColorsScheme? = ::resolveScheme,
        ): SchemeTarget? {
            val scheme = resolve(source) as? AbstractColorsScheme ?: return null
            return SchemeTarget(source, scheme, scheme.name, scheme.parentScheme, resolve).takeIf { it.isSelected() }
        }

        private fun resolveScheme(source: SchemeSource): EditorColorsScheme? {
            val manager = EditorColorsManager.getInstance()
            return when (source) {
                SchemeSource.Active -> manager.globalScheme
                is SchemeSource.Named -> manager.getScheme(source.lookupName)
            }
        }
    }
}
