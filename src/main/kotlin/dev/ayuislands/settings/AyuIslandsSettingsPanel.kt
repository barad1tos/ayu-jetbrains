package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AyuVariant

/**
 * Base class for settings panel sections.
 *
 * Each section contributes UI to the Ayu Islands settings page via buildPanel().
 * Phases 8-10 add new sections by extending this class and registering in
 * AyuIslandsConfigurable.panels.
 */
abstract class AyuIslandsSettingsPanel {

    /** Build this section's UI into the given DSL panel. */
    abstract fun buildPanel(panel: Panel, variant: AyuVariant)

    /** Return true if this section has unsaved changes. */
    abstract fun isModified(): Boolean

    /** Persist changes and apply them at runtime. */
    abstract fun apply()

    /** Discard pending changes and reset UI to persisted state. */
    abstract fun reset()
}
