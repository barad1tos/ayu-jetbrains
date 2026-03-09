package dev.ayuislands.font

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.ModifiableFontPreferences

/** Applies and reverts font presets to the IDE editor (and optionally console). */
object FontPresetApplicator {
    private val LOG = logger<FontPresetApplicator>()

    private const val DEFAULT_FONT_FAMILY = "JetBrains Mono"
    private const val DEFAULT_FONT_SIZE = 13f
    private const val DEFAULT_LINE_SPACING = 1.2f

    /** Apply the given font preset to the editor (and console if opted in). */
    fun apply(
        preset: FontPreset,
        applyToConsole: Boolean,
    ) {
        val resolvedFamily = FontDetector.resolveFamily(preset) ?: preset.fontFamily
        val scheme = EditorColorsManager.getInstance().globalScheme

        (scheme.fontPreferences as? ModifiableFontPreferences)?.apply {
            clearFonts()
            addFontFamily(resolvedFamily)
        }
        scheme.setEditorFontSize(preset.fontSize)
        scheme.lineSpacing = preset.lineSpacing
        scheme.isUseLigatures = preset.enableLigatures

        if (applyToConsole) {
            (scheme.consoleFontPreferences as? ModifiableFontPreferences)?.apply {
                clearFonts()
                addFontFamily(resolvedFamily)
            }
            scheme.setConsoleFontSize(preset.fontSize)
            scheme.consoleLineSpacing = preset.lineSpacing
        }

        ApplicationManager
            .getApplication()
            .messageBus
            .syncPublisher(EditorColorsManager.TOPIC)
            .globalSchemeChange(null)
        LOG.info("Font preset applied: ${preset.displayName} ($resolvedFamily)")
    }

    /** Revert to IDE default font (JetBrains Mono 13pt). */
    fun revert() {
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
