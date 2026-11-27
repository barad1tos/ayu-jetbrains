package dev.ayuislands.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import dev.ayuislands.font.FontWeight
import javax.swing.JList

internal fun fontWeightCombo(): ComboBox<FontWeight> =
    ComboBox(FontWeight.entries.toTypedArray()).apply {
        renderer =
            object : SimpleListCellRenderer<FontWeight>() {
                override fun customize(
                    list: JList<out FontWeight>,
                    value: FontWeight?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    text = value?.displayName.orEmpty()
                }
            }
    }
