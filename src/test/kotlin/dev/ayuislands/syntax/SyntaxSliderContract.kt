package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color

internal data class TuningCell(
    val language: String,
    val category: PrimitiveCategory,
)

internal data class SliderEvidence(
    val cell: TuningCell,
    val sourceKeys: Set<String>,
    val changedKeys: Set<String>,
) {
    val isEffective: Boolean
        get() = changedKeys.isNotEmpty()
}

internal object SyntaxSliderContract {
    fun evaluate(
        variantName: String,
        editorBackground: Color,
        baseline: Map<TextAttributesKey, TextAttributes>,
        overlay: Map<TextAttributesKey, TextAttributes>,
        fallbacks: Map<String, String>,
    ): List<SliderEvidence> {
        val context =
            EvaluationContext(
                variantName = variantName,
                editorBackground = editorBackground,
                baseline = baseline,
                overlay = overlay,
                fallbacks = fallbacks,
            )
        val keys = LinkedHashSet<TextAttributesKey>()
        keys.addAll(baseline.keys)
        keys.addAll(overlay.keys)
        val tunableCategories =
            SyntaxIntensityApplicator
                .tunableCategories(
                    baseline = baseline,
                    overlay = overlay,
                    fallbacks = fallbacks,
                )
        val cells =
            tunableCategories
                .flatMap { (language, categories) ->
                    categories.map { category -> TuningCell(language, category) }
                }.sortedWith(compareBy(TuningCell::language, { it.category.name }))

        return cells.map { cell ->
            evidenceFor(
                cell = cell,
                context = context,
                keys = keys,
            )
        }
    }

    private fun evidenceFor(
        cell: TuningCell,
        context: EvaluationContext,
        keys: Set<TextAttributesKey>,
    ): SliderEvidence {
        val low =
            computeEndpoint(
                cell = cell,
                sliderValue = MIN_SLIDER_VALUE,
                context = context,
            )
        val high =
            computeEndpoint(
                cell = cell,
                sliderValue = MAX_SLIDER_VALUE,
                context = context,
            )
        val ownedNames =
            keys
                .filter { key -> key.belongsTo(cell) }
                .mapTo(linkedSetOf(), TextAttributesKey::getExternalName)
        val changedNames =
            (low.keys + high.keys)
                .asSequence()
                .filter { key -> key.belongsTo(cell) }
                .filter { key ->
                    val lowRgb = low[key]?.foregroundColor?.rgb
                    val highRgb = high[key]?.foregroundColor?.rgb
                    lowRgb != null && highRgb != null && lowRgb != highRgb
                }.mapTo(linkedSetOf(), TextAttributesKey::getExternalName)

        return SliderEvidence(
            cell = cell,
            sourceKeys = ownedNames,
            changedKeys = changedNames,
        )
    }

    private fun computeEndpoint(
        cell: TuningCell,
        sliderValue: Int,
        context: EvaluationContext,
    ): Map<TextAttributesKey, TextAttributes> =
        SyntaxIntensityApplicator.compute(
            SyntaxIntensityApplicator.Request(
                preset = SyntaxPreset.CUSTOM,
                variantName = context.variantName,
                editorBg = context.editorBackground,
                baseline = context.baseline,
                overlay = context.overlay,
                customOverrides = mapOf(cell.language to mapOf(cell.category.name to sliderValue)),
                subordinatePreset = SyntaxPreset.AMBIENT,
                fallbacks = context.fallbacks,
            ),
        )

    private fun TextAttributesKey.belongsTo(cell: TuningCell): Boolean {
        val language = SyntaxLanguageRegistry.classify(externalName)
        val category = SyntaxCategoryRegistry.classify(externalName)
        return language.displayName == cell.language && category == cell.category
    }

    private data class EvaluationContext(
        val variantName: String,
        val editorBackground: Color,
        val baseline: Map<TextAttributesKey, TextAttributes>,
        val overlay: Map<TextAttributesKey, TextAttributes>,
        val fallbacks: Map<String, String>,
    )

    private const val MIN_SLIDER_VALUE = 0
    private const val MAX_SLIDER_VALUE = 100
}
