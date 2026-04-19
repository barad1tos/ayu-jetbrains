package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AyuVariant

/** Contract for settings panel sections. */
interface AyuIslandsSettingsPanel {
    fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    )

    fun isModified(): Boolean

    fun apply()

    fun reset()

    /**
     * Release platform resources (MessageBus subscriptions, timers) that
     * the panel acquired during [buildPanel]. Default no-op because most
     * settings panels hold no platform lifecycle state — they own Swing
     * components whose cleanup is already driven by the enclosing
     * Configurable's `disposeUIResources` chain. Override only when the
     * panel acquires something the platform can't reclaim on its own.
     * Called from [AyuIslandsConfigurable.disposeUIResources].
     */
    fun dispose() {
        // Empty by default. Panels with platform-backed resources
        // (e.g. AyuIslandsAccentPanel, which owns OverridesGroupBuilder's
        // detection-Topic MessageBus subscription) override this.
    }
}
