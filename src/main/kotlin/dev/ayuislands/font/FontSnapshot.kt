package dev.ayuislands.font

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl

/** Independent preference owners; inherited console fonts continue following the editor. */
internal enum class FontSurface {
    EDITOR,
    CONSOLE,
    ;

    fun isAvailable(scheme: EditorColorsScheme): Boolean {
        if (this == EDITOR) return true
        // Inheritance is observable even when global preferences mask the scheme's console getter.
        if (scheme.isUseEditorFontPreferencesInConsole) return true
        val native = scheme as? AbstractColorsScheme ?: return false
        val capture = ConsolePreferencesCapture()
        native.copyTo(capture)
        // The public getter may expose global preferences while its setter targets the scheme.
        return capture.capturedPreferences === scheme.consoleFontPreferences
    }

    fun preferences(scheme: EditorColorsScheme): FontPreferences =
        when (this) {
            EDITOR -> scheme.fontPreferences
            CONSOLE -> scheme.consoleFontPreferences
        }

    fun capture(scheme: EditorColorsScheme): FontSnapshot {
        val inherited =
            when (this) {
                EDITOR -> scheme.isUseAppFontPreferencesInEditor
                CONSOLE -> scheme.isUseEditorFontPreferencesInConsole
            }
        return if (inherited) FontSnapshot.Inherited else FontSnapshot.Explicit(FontData.capture(preferences(scheme)))
    }

    fun write(
        scheme: EditorColorsScheme,
        snapshot: FontSnapshot,
    ) {
        when (snapshot) {
            FontSnapshot.Inherited ->
                when (this) {
                    EDITOR -> scheme.setUseAppFontPreferencesInEditor()
                    CONSOLE -> scheme.setUseEditorFontPreferencesInConsole()
                }
            is FontSnapshot.Explicit ->
                when (this) {
                    EDITOR -> scheme.fontPreferences = snapshot.preferences.toPreferences()
                    CONSOLE -> scheme.consoleFontPreferences = snapshot.preferences.toPreferences()
                }
        }
    }
}

/** Public copyTo callback exposes the local owner without querying internal application services. */
private class ConsolePreferencesCapture : EditorColorsSchemeImpl(null) {
    var capturedPreferences: FontPreferences? = null
        private set

    override fun setConsoleFontPreferences(preferences: FontPreferences) {
        capturedPreferences = preferences
    }
}

/** Inheritance is a live user choice, not a copy of the current global font values. */
internal sealed interface FontSnapshot {
    data object Inherited : FontSnapshot

    data class Explicit(
        val preferences: FontData,
    ) : FontSnapshot
}

internal data class FontFamily(
    val name: String,
    val size: Float?,
)

/** Exact ordered native preference data, including unavailable families and absent sizes. */
internal data class FontData(
    val effectiveFamilies: List<String>,
    val families: List<FontFamily>,
    val templateSize: Float,
    val lineSpacing: Float,
    val ligatures: Boolean,
    val regularSubFamily: String?,
    val boldSubFamily: String?,
) {
    fun toPreferences(): FontPreferencesImpl =
        FontPreferencesImpl().apply {
            setEffectiveFontFamilies(this@FontData.effectiveFamilies)
            setRealFontFamilies(families.map(FontFamily::name))
            setTemplateFontSize(templateSize)
            for ((name, size) in families) {
                size?.let { setFontSize(name, it) }
            }
            lineSpacing = this@FontData.lineSpacing
            setUseLigatures(ligatures)
            regularSubFamily = this@FontData.regularSubFamily
            boldSubFamily = this@FontData.boldSubFamily
        }

    companion object {
        fun capture(preferences: FontPreferences): FontData {
            val captured = CapturedFontPreferences()
            preferences.copyTo(captured)
            return FontData(
                effectiveFamilies = captured.effectiveFontFamilies.toList(),
                families =
                    captured.realFontFamilies.map {
                        FontFamily(
                            it,
                            if (captured.hasSize(it)) captured.getSize2D(it) else null,
                        )
                    },
                templateSize = captured.templateSize,
                lineSpacing = captured.lineSpacing,
                ligatures = captured.useLigatures(),
                regularSubFamily = captured.regularSubFamily,
                boldSubFamily = captured.boldSubFamily,
            )
        }
    }
}

/** Native copyTo supplies the template size through this public setter; no reflective field access. */
private class CapturedFontPreferences : FontPreferencesImpl() {
    var templateSize: Float = DEFAULT_FONT_SIZE.toFloat()
        private set

    override fun setTemplateFontSize(size: Float) {
        templateSize = size
        super.setTemplateFontSize(size)
    }
}
