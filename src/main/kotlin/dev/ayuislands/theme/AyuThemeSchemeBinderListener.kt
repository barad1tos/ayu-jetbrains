package dev.ayuislands.theme

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings

/**
 * Adapter that wires `LafManagerListener.lookAndFeelChanged` to
 * [AyuEditorSchemeBinder]. Single responsibility: gate on the
 * `syncEditorScheme` settings flag and the [AyuVariant.isAyuActive] predicate
 * (Pattern J), then delegate. Domain logic lives in the binder.
 *
 * Registered via `<applicationListeners>` in `plugin.xml`. The platform owns
 * lifecycle — no Disposable parent needed (Pattern E exception: declarative
 * listeners are managed by the IDE).
 */
internal class AyuThemeSchemeBinderListener : LafManagerListener {
    override fun lookAndFeelChanged(source: LafManager) {
        val settings = AyuIslandsSettings.getInstance()
        if (!settings.state.syncEditorScheme) {
            log.debug("syncEditorScheme=false — skipping editor scheme bind")
            return
        }
        // Pattern J: only bind when an Ayu LAF is active. Switching AWAY from
        // Ayu is intentionally NOT handled here (see AyuEditorSchemeBinder
        // Pattern G note — revert path is a separate feature).
        val variant = AyuVariant.detect() ?: return
        AyuEditorSchemeBinder.bindForVariant(variant)
    }

    companion object {
        private val log = logger<AyuThemeSchemeBinderListener>()
    }
}
