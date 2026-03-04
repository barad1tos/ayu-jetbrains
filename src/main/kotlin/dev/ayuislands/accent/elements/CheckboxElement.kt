package dev.ayuislands.accent.elements

import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import java.awt.Color

class CheckboxElement : AccentElement {
    override val id = AccentElementId.CHECKBOXES
    override val displayName = "Checkboxes"

    private val log = logger<CheckboxElement>()

    override fun apply(color: Color) {
        log.info("Checkbox accent applies on next theme reload (SVG-based, not runtime-modifiable)")
    }

    override fun revert() {
        log.info("Checkbox accent reverts on next theme reload (SVG-based, not runtime-modifiable)")
    }
}
