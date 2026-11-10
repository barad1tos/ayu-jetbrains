package dev.ayuislands.settings

import com.intellij.ui.InplaceButton
import dev.ayuislands.syntax.PrimitiveCategory
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

    fun refreshCapabilities(actuatedByLanguage: Map<String, Set<PrimitiveCategory>>?) {
        snapshot = snapshot.copy(actuatedByLanguage = actuatedByLanguage)
    }

    fun refreshRows(
        language: String,
        isEditable: Boolean = true,
    ) {
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
            applyAvailability(categoryLabels[category], isAvailable, isEditable, reason)
            applyAvailability(sliders[category], isAvailable, isEditable, reason)
            applyAvailability(sliderLabels[category], isAvailable, isEditable, reason)
            applyAvailability(resetButtons[category], isAvailable, isEditable, reason, updateVisibility = false)
            styleControls[category]?.let { control ->
                control.setAvailable(isAvailable && isEditable, reason)
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
                -> emptySet()

                null -> null
            }
        snapshot = snapshot.copy(nativeConfirmed = nativeConfirmed)
        state?.languageId?.let(::refreshRows)
    }

    private fun applyAvailability(
        component: JComponent?,
        isAvailable: Boolean,
        isEditable: Boolean,
        reason: String?,
        updateVisibility: Boolean = true,
    ) {
        component?.isEnabled = isAvailable && isEditable
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
