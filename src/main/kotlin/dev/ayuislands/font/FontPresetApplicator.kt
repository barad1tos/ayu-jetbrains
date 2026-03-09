package dev.ayuislands.font

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.ModifiableFontPreferences
import javax.swing.SwingUtilities

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
        val base = "${fontSize.toInt()}|$lineSpacing|$enableLigatures|${weight.name}"
        if (!preset.isCurated) return "$base|$fontFamily"
        return base
    }

    companion object {
        private const val IDX_SIZE = 0
        private const val IDX_SPACING = 1
        private const val IDX_LIGATURES = 2
        private const val IDX_WEIGHT = 3
        private const val IDX_FONT_FAMILY = 4

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
                    parts.getOrNull(IDX_FONT_FAMILY)?.takeIf { it.isNotBlank() }
                        ?: preset.fontFamily,
                fontSize = parts.getOrNull(IDX_SIZE)?.toFloatOrNull() ?: preset.fontSize,
                lineSpacing = parts.getOrNull(IDX_SPACING)?.toFloatOrNull() ?: preset.lineSpacing,
                enableLigatures = parts.getOrNull(IDX_LIGATURES)?.toBooleanStrictOrNull() ?: preset.enableLigatures,
                weight = parts.getOrNull(IDX_WEIGHT)?.let { FontWeight.fromName(it) } ?: preset.defaultWeight,
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

/** Applies and reverts font presets to the IDE editor (and optionally console). */
object FontPresetApplicator {
    private val LOG = logger<FontPresetApplicator>()

    private const val DEFAULT_FONT_FAMILY = "JetBrains Mono"
    private const val DEFAULT_FONT_SIZE = 13f
    private const val DEFAULT_LINE_SPACING = 1.2f

    /** Apply the given font settings to the editor (and console if opted in). */
    fun apply(settings: FontSettings) {
        ensureEdt {
            val resolvedFamily =
                if (settings.preset.isCurated) {
                    FontDetector.resolveFamily(settings.preset) ?: settings.preset.fontFamily
                } else {
                    settings.fontFamily
                }
            val subFamily = settings.weight.subFamily
            val scheme = EditorColorsManager.getInstance().globalScheme

            (scheme.fontPreferences as? ModifiableFontPreferences)?.apply {
                clearFonts()
                addFontFamily(resolvedFamily)
                regularSubFamily = subFamily
            }
            scheme.setEditorFontSize(settings.fontSize)
            scheme.lineSpacing = settings.lineSpacing
            scheme.isUseLigatures = settings.enableLigatures

            if (settings.applyToConsole) {
                (scheme.consoleFontPreferences as? ModifiableFontPreferences)?.apply {
                    clearFonts()
                    addFontFamily(resolvedFamily)
                    regularSubFamily = subFamily
                }
                scheme.setConsoleFontSize(settings.fontSize)
                scheme.consoleLineSpacing = settings.lineSpacing
            }

            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
            LOG.info(
                "Font preset applied: ${settings.preset.displayName} " +
                    "($resolvedFamily, subFamily=$subFamily, " +
                    "${settings.fontSize}pt, ${settings.lineSpacing}x)",
            )
        }
    }

    /** Revert to IDE default font (JetBrains Mono 13pt). */
    fun revert() {
        ensureEdt {
            val scheme = EditorColorsManager.getInstance().globalScheme

            (scheme.fontPreferences as? ModifiableFontPreferences)?.apply {
                clearFonts()
                addFontFamily(DEFAULT_FONT_FAMILY)
            }
            scheme.setEditorFontSize(DEFAULT_FONT_SIZE)
            scheme.lineSpacing = DEFAULT_LINE_SPACING

            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
            LOG.info("Font preset reverted to defaults")
        }
    }

    private fun ensureEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater(action)
        }
    }
}
