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
    private val visibilityChanged: (Set<PrimitiveCategory>) -> Unit = {},
) {
    private var tunableCategories: Map<String, Set<PrimitiveCategory>>? = null
    private var nativeCategories: Set<PrimitiveCategory>? = null

    fun refreshCapabilities(variant: AyuVariant) {
        tunableCategories = SyntaxIntensityService.getInstance().tunableCategories(variant)?.takeIf { it.isNotEmpty() }
    }

    fun refreshRows(language: String) {
        val availableCategories = availableFor(language)
        visibilityChanged(availableCategories)
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
            applyAvailability(resetButtons[category], isAvailable, reason, updateVisibility = false)
            styleControls[category]?.let { control ->
                control.setAvailable(isAvailable, reason)
                control.component.isVisible = isAvailable
            }
        }
    }

    fun availableFor(language: String): Set<PrimitiveCategory> {
        nativeCategories?.let { return it }
        val capabilities = tunableCategories ?: return PrimitiveCategory.entries.toSet()
        return capabilities[language].orEmpty()
    }

    fun showCapability(state: SyntaxCapabilityState?) {
        nativeCategories =
            when (state) {
                is SyntaxCapabilityState.Confirmed -> state.evidence.confirmedCells
                is SyntaxCapabilityState.Incompatible -> state.confirmedCells
                is SyntaxCapabilityState.Checking,
                is SyntaxCapabilityState.SupportUnavailable,
                is SyntaxCapabilityState.TemporarilyUnavailable,
                null,
                -> emptySet()
            }
        state?.languageId?.let(::refreshRows)
    }

    private fun applyAvailability(
        component: JComponent?,
        isAvailable: Boolean,
        reason: String?,
        updateVisibility: Boolean = true,
    ) {
        component?.isEnabled = isAvailable
        if (updateVisibility) component?.isVisible = isAvailable
        component?.toolTipText = reason
    }
}
