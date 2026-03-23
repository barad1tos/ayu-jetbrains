package dev.ayuislands.font

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.ModifiableFontPreferences
import dev.ayuislands.settings.AyuIslandsSettings
import javax.swing.SwingUtilities

/** Applies and reverts font presets to the IDE editor (and optionally console). */
object FontPresetApplicator {
    private val LOG = logger<FontPresetApplicator>()

    private const val DEFAULT_FONT_FAMILY = "JetBrains Mono"
    private const val DEFAULT_FONT_SIZE = 13f
    private const val DEFAULT_LINE_SPACING = 1.2f

    /** Resolve and apply the font preset from persisted settings state. */
    fun applyFromState() {
        val state = AyuIslandsSettings.getInstance().state
        if (!state.fontPresetEnabled) return
        val preset = FontPreset.fromName(state.fontPresetName)
        val encoded = state.fontPresetCustomizations[preset.name]
        val settings = FontSettings.decode(encoded, preset)
        apply(settings.copy(applyToConsole = state.fontApplyToConsole))
    }

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

            ReadAction.run<Nothing> {
                ApplicationManager
                    .getApplication()
                    .messageBus
                    .syncPublisher(EditorColorsManager.TOPIC)
                    .globalSchemeChange(null)
            }
            LOG.info(
                "Font preset applied: ${settings.preset.displayName} " +
                    "($resolvedFamily, subFamily=$subFamily, " +
                    "${settings.fontSize}pt, ${settings.lineSpacing}x)",
            )
        }
    }

    /** Revert editor and console to IDE default font (JetBrains Mono 13pt). */
    fun revert() {
        ensureEdt {
            val scheme = EditorColorsManager.getInstance().globalScheme

            (scheme.fontPreferences as? ModifiableFontPreferences)?.apply {
                clearFonts()
                addFontFamily(DEFAULT_FONT_FAMILY)
            }
            scheme.setEditorFontSize(DEFAULT_FONT_SIZE)
            scheme.lineSpacing = DEFAULT_LINE_SPACING

            (scheme.consoleFontPreferences as? ModifiableFontPreferences)?.apply {
                clearFonts()
                addFontFamily(DEFAULT_FONT_FAMILY)
            }
            scheme.setConsoleFontSize(DEFAULT_FONT_SIZE)
            scheme.consoleLineSpacing = DEFAULT_LINE_SPACING

            ReadAction.run<Nothing> {
                ApplicationManager
                    .getApplication()
                    .messageBus
                    .syncPublisher(EditorColorsManager.TOPIC)
                    .globalSchemeChange(null)
            }
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
