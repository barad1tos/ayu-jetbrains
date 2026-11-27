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
            applyAvailability(categoryLabels[category], isAvailable, reason)
            applyAvailability(sliders[category], isAvailable, reason, isEditable = isEditable)
            applyAvailability(sliderLabels[category], isAvailable, reason)
            applyAvailability(
                resetButtons[category],
                isAvailable,
                reason,
                updateVisibility = false,
                isEditable = isEditable,
            )
            styleControls[category]?.let { control ->
                control.setAvailable(isAvailable && isEditable, reason)
                control.component.isVisible = isAvailable
            }
        }
    }

    fun availableFor(language: String): Set<PrimitiveCategory> = snapshot.availableFor(language)

    fun showCapability(state: SyntaxCapabilityState?) {
        snapshot = snapshot.copy(nativeConfirmed = state.confirmedCategories())
        state?.languageId?.let(::refreshRows)
    }

    fun render(
        language: String,
        state: SyntaxCapabilityState?,
        actuated: Set<PrimitiveCategory>?,
        isEditable: Boolean,
    ) {
        snapshot =
            SyntaxAvailabilitySnapshot(
                nativeConfirmed = state?.takeIf { it.languageId == language }.confirmedCategories(),
                actuatedByLanguage = actuated?.let { mapOf(language to it.toSet()) },
            )
        refreshRows(language, isEditable)
    }

    private fun applyAvailability(
        component: JComponent?,
        isAvailable: Boolean,
        reason: String?,
        updateVisibility: Boolean = true,
        isEditable: Boolean = true,
    ) {
        component?.isEnabled = isAvailable && isEditable
        if (updateVisibility) component?.isVisible = isAvailable
        component?.toolTipText = reason
    }
}

private fun SyntaxCapabilityState?.confirmedCategories(): Set<PrimitiveCategory> =
    when (this) {
        is SyntaxCapabilityState.Confirmed -> evidence.confirmedCells.toSet()
        is SyntaxCapabilityState.Incompatible -> confirmedCells.toSet()
        is SyntaxCapabilityState.Checking,
        is SyntaxCapabilityState.SupportUnavailable,
        is SyntaxCapabilityState.TemporarilyUnavailable,
        null,
        -> emptySet()
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
