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
    private var snapshot = SyntaxAvailabilitySnapshot()

    fun refreshCapabilities(variant: AyuVariant) {
        val actuatedByLanguage =
            SyntaxIntensityService
                .getInstance()
                .tunableCategories(variant)
                ?.mapValues { (_, categories) -> categories.toSet() }
        snapshot = snapshot.copy(actuatedByLanguage = actuatedByLanguage)
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

    fun availableFor(language: String): Set<PrimitiveCategory> = snapshot.availableFor(language)

    fun showCapability(state: SyntaxCapabilityState?) {
        val nativeConfirmed =
            when (state) {
                is SyntaxCapabilityState.Confirmed -> state.evidence.confirmedCells.toSet()
                is SyntaxCapabilityState.Incompatible -> state.confirmedCells.toSet()
                is SyntaxCapabilityState.Checking,
                is SyntaxCapabilityState.SupportUnavailable,
                is SyntaxCapabilityState.TemporarilyUnavailable,
                null,
                -> emptySet()
            }
        snapshot = snapshot.copy(nativeConfirmed = nativeConfirmed)
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

internal data class SyntaxAvailabilitySnapshot(
    val nativeConfirmed: Set<PrimitiveCategory>? = null,
    val actuatedByLanguage: Map<String, Set<PrimitiveCategory>>? = null,
) {
    fun availableFor(language: String): Set<PrimitiveCategory> {
        val native = nativeConfirmed
        val actuated = actuatedByLanguage?.get(language).orEmpty()
        return when {
            native != null && actuatedByLanguage != null -> native.intersect(actuated)
            native != null -> native
            actuatedByLanguage != null -> actuated
            else -> PrimitiveCategory.entries.toSet()
        }
    }
}
