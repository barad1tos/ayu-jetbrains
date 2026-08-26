package dev.ayuislands.settings

import com.intellij.ui.InplaceButton
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityService
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JSlider

/** Applies syntax capability snapshots to row presentation without changing stored preferences. */
internal class SyntaxCategoryAvailability(
    private val categoryLabels: Map<PrimitiveCategory, JLabel>,
    private val sliders: Map<PrimitiveCategory, JSlider>,
    private val sliderLabels: Map<PrimitiveCategory, JLabel>,
    private val resetButtons: Map<PrimitiveCategory, InplaceButton>,
    private val styleControls: Map<PrimitiveCategory, SyntaxStyleControl>,
) {
    private var tunableCategories: Map<String, Set<PrimitiveCategory>>? = null

    fun refreshCapabilities(variant: AyuVariant) {
        tunableCategories = SyntaxIntensityService.getInstance().tunableCategories(variant)
    }

    fun refreshRows(language: String) {
        val availableCategories = availableFor(language)
        for (category in PrimitiveCategory.entries) {
            val isAvailable = category in availableCategories
            val reason =
                if (isAvailable) {
                    null
                } else {
                    "$language highlighter does not expose ${category.displayName}"
                }
            applyAvailability(categoryLabels[category], isAvailable, reason)
            applyAvailability(sliders[category], isAvailable, reason)
            applyAvailability(sliderLabels[category], isAvailable, reason)
            applyAvailability(resetButtons[category], isAvailable, reason)
            styleControls[category]?.setAvailable(isAvailable, reason)
        }
    }

    fun availableFor(language: String): Set<PrimitiveCategory> {
        val capabilities = tunableCategories ?: return PrimitiveCategory.entries.toSet()
        return capabilities[language].orEmpty()
    }

    private fun applyAvailability(
        component: JComponent?,
        isAvailable: Boolean,
        reason: String?,
    ) {
        component?.isEnabled = isAvailable
        component?.toolTipText = reason
    }
}
