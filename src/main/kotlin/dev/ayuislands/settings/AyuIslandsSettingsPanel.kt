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
}
