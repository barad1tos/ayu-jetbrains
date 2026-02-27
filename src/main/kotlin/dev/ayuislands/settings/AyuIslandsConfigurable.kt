package dev.ayuislands.settings

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant

/** Settings page at Appearance > Ayu Islands composing panel sections. */
class AyuIslandsConfigurable : BoundConfigurable("Ayu Islands") {

    private val panels: List<AyuIslandsSettingsPanel> = listOf(
        AyuIslandsAccentPanel(),
    )

    override fun createPanel(): com.intellij.openapi.ui.DialogPanel {
        val pluginVersion = PluginManagerCore
            .getPlugin(PluginId.getId("com.ayuislands.theme"))
            ?.version ?: "unknown"

        val variant = AyuVariant.detect()

        return panel {
            row {
                label("Ayu Islands v$pluginVersion")
                    .applyToComponent { font = JBUI.Fonts.label(13f).asBold() }
            }

            if (variant == null) {
                row {
                    comment("Activate an Ayu Islands theme to configure accent colors")
                }
            } else {
                for (section in panels) {
                    section.buildPanel(this@panel, variant)
                }
            }
        }
    }

    override fun isModified(): Boolean =
        super.isModified() || panels.any { it.isModified() }

    override fun apply() {
        super.apply()
        for (section in panels) {
            section.apply()
        }
    }

    override fun reset() {
        super.reset()
        for (section in panels) {
            section.reset()
        }
    }
}
