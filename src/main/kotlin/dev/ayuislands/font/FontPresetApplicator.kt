package dev.ayuislands.font

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.theme.EditorSchemeChange
import javax.swing.SwingUtilities

/** Applies and reverts font presets to the IDE editor (and optionally console). */
object FontPresetApplicator {
    private val LOG = logger<FontPresetApplicator>()

    /** Resolve and apply the font preset from the persisted settings state. */
    fun applyFromState() {
        ensureEdt {
            val state = AyuIslandsSettings.getInstance().state
            if (!state.fontPresetEnabled) {
                revert()
                return@ensureEdt
            }
            if (state.fontOwnershipVersion != FontOwnership.VERSION) {
                LOG.info(
                    "Preserving existing fonts without a supported ownership snapshot; " +
                        "explicit font Apply can establish ownership",
                )
                return@ensureEdt
            }
            val preset = FontPreset.fromName(state.fontPresetName)
            val settings = FontSettings.decode(state.fontPresetCustomizations[preset.name], preset)
            apply(settings.copy(applyToConsole = state.fontApplyToConsole), FontApplyOrigin.AUTOMATIC)
        }
    }

    /** Apply the given font settings to the editor (and console if opted in). */
    fun apply(settings: FontSettings) = apply(settings, FontApplyOrigin.EXPLICIT)

    private fun apply(
        settings: FontSettings,
        origin: FontApplyOrigin,
    ) {
        ensureEdt {
            val state = AyuIslandsSettings.getInstance().state
            if (state.fontOwnershipVersion !in 0..FontOwnership.VERSION) {
                LOG.warn("Unsupported font ownership version; preserving current fonts")
                return@ensureEdt
            }
            val manager = EditorColorsManager.getInstance()
            val scheme = manager.globalScheme
            // The manager may fall back to a hidden template when no editable copy is available.
            if (manager.allSchemes.none { it === scheme }) return@ensureEdt
            state.fontOwnershipVersion = FontOwnership.VERSION
            if (FontOwnership.apply(scheme, settings, state, origin)) EditorSchemeChange.publish()
        }
    }

    /**
     * Restore available schemes only where current preferences still match our recorded writes.
     * When [family] is supplied by uninstall, retain ownership for unrelated font families.
     */
    fun revert(family: String? = null) {
        ensureEdt {
            val state = AyuIslandsSettings.getInstance().state
            if (state.fontOwnershipSnapshots.isEmpty()) return@ensureEdt
            if (state.fontOwnershipVersion != FontOwnership.VERSION) return@ensureEdt
            val manager = EditorColorsManager.getInstance()
            val schemes = manager.allSchemes
            var changed = false
            for (scheme in schemes) changed = FontOwnership.restore(scheme, state, family) || changed
            if (changed) EditorSchemeChange.publish()
        }
    }

    private fun ensureEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            val application = ApplicationManager.getApplication()
            if (application == null) {
                SwingUtilities.invokeLater(action)
            } else {
                application.invokeLater(action, ModalityState.nonModal())
            }
        }
    }
}
