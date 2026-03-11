package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import java.awt.Color

// Checkbox colors live in icons.ColorPalette (SVG color patcher), not in the ui section.
// The SVG renderer bakes palette colors into cached Icon objects at theme load time.
// UIManager.put() has no effect — there is no public API to re-patch the SVG palette at runtime.
// Accent changes take effect on the next theme reload (variant switch or IDE restart).
class CheckboxElement : AccentElement {
    override val id = AccentElementId.CHECKBOXES
    override val displayName = "Checkboxes"

    override fun apply(color: Color) {
        // No-op: ColorPalette values are baked into SVG icons at theme load time
    }

    override fun revert() {
        // No-op: theme reload restores default ColorPalette values
    }
}
