package dev.ayuislands.settings

import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.toolbar.popup.PopupSwatch
import dev.ayuislands.settings.mappings.RoundedSwatchRenderer
import dev.ayuislands.studio.ui.StudioUiTokens
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JPanel

/** A shared palette whose caller owns selection acceptance and the custom color dialog. */
internal class AccentSwatchGrid(
    onSelected: (String) -> Unit,
    onCustomRequested: () -> Unit,
) : JPanel(BorderLayout()) {
    private val spacing = StudioUiTokens.spacing()
    private val swatches =
        AYU_ACCENT_PRESETS.map { preset ->
            PopupSwatch(AccentHex.unsafeOf(preset.hex), isSelected = false) { onSelected(it.value) }.apply {
                toolTipText = "${preset.name} (${preset.hex})"
                getAccessibleContext().accessibleName = toolTipText
            }
        }
    private val custom =
        ActionLink("Custom…") { onCustomRequested() }.apply {
            autoHideOnDisable = false
        }

    init {
        isOpaque = false
        (layout as BorderLayout).vgap = spacing.fieldGap
        add(
            JPanel(GridLayout(ROWS, COLUMNS, spacing.paletteGap, spacing.paletteGap)).apply {
                isOpaque = false
                swatches.forEach { add(it) }
            },
            BorderLayout.NORTH,
        )
        add(
            JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                isOpaque = false
                add(custom)
            },
            BorderLayout.SOUTH,
        )
        render(null, isEditable = true)
    }

    /** Rendering observes raw selection without accepting, normalizing, or emitting an edit. */
    fun render(
        selectedHex: String?,
        isEditable: Boolean,
    ) {
        swatches.forEach { swatch ->
            swatch.setSelected(swatch.hex.value.equals(selectedHex, ignoreCase = true))
            swatch.isEnabled = isEditable
        }
        custom.isEnabled = isEditable
        val color = selectedHex?.let(RoundedSwatchRenderer::safeDecodeColor)
        custom.icon = color?.let { ColorIcon(JBUI.scale(CUSTOM_ICON_SIZE), it) }
        val description = if (selectedHex == null) "Choose a custom accent" else "Custom accent: $selectedHex"
        custom.toolTipText = description
        custom.accessibleContext.accessibleDescription = description
    }

    private companion object {
        const val ROWS = 2
        const val COLUMNS = 6
        const val CUSTOM_ICON_SIZE = 18
    }
}
