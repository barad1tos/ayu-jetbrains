package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import java.awt.Color

/** Admission only: the owner must still capture and acknowledge actual recovery seeds before using them. */
internal fun checkBatchRestoration(
    scheme: AbstractColorsScheme,
    records: List<SchemeEntryRecord>,
) {
    val absent = records.filter { SchemeAdmission.isAbsent(it.before.cell) }.map { it.entry }
    if (absent.isEmpty()) return
    val parent = checkNotNull(scheme.parentScheme as? AbstractColorsScheme) { "Editor deletion parent is unavailable" }
    val attributes = scheme.directlyDefinedAttributes.toMutableMap()
    val colors = scheme.directlyDefinedColors.toMutableMap()
    for (record in records) {
        when (val entry = record.entry) {
            is SchemeEntry.AttributesEntry ->
                attributes[entry.key.externalName] =
                    record.planned.cell.attributePayload()
            is SchemeEntry.ColorEntry -> colors[entry.key] = record.planned.cell.colorPayload()
        }
    }
    val plan =
        prepareSchemePruning(
            parent,
            attributes,
            colors,
            absent.filterIsInstance<SchemeEntry.AttributesEntry>().mapTo(linkedSetOf()) { it.key },
            absent.filterIsInstance<SchemeEntry.ColorEntry>().mapTo(linkedSetOf()) { it.key },
        )
    check(plan is SchemePruningPlan.Prepared && scheme.parentScheme === parent) {
        "Editor local absence cannot be restored without changing unrelated fields"
    }
}

internal fun prepareSchemePruning(
    parent: AbstractColorsScheme,
    attributes: Map<String, TextAttributes>,
    colors: Map<ColorKey, Color>,
    attributeTargets: Set<TextAttributesKey>,
    colorTargets: Set<ColorKey>,
): SchemePruningPlan {
    val pendingAttributes = attributeTargets.filterTo(linkedSetOf()) { it.externalName in attributes }
    val pendingColors = colorTargets.filterTo(linkedSetOf()) { it in colors }
    if (pendingAttributes.isEmpty() && pendingColors.isEmpty()) return SchemePruningPlan.Unchanged
    val qualified = qualifyPruningParent(parent) ?: return SchemePruningPlan.Unavailable
    val attributeSteps =
        prepareAttributeSeeds(qualified.directlyDefinedAttributes, attributes, pendingAttributes)
            ?: return SchemePruningPlan.Unavailable
    val colorSteps = prepareColorSeeds(qualified.directlyDefinedColors, colors, pendingColors)
    val projectedAttributes = attributes.toMutableMap()
    attributeSteps.forEach { (key, steps) ->
        steps.lastOrNull()?.let { projectedAttributes[key.externalName] = it }
    }
    val projectedColors = colors.toMutableMap()
    colorSteps.forEach { (key, steps) -> steps.lastOrNull()?.let { projectedColors[key] = it } }
    val facts =
        capturePruningFacts(qualified, projectedAttributes, projectedColors)
            ?: return SchemePruningPlan.Unavailable
    if (!SchemeComparison.permitsPruning(
            facts,
            pendingAttributes.mapTo(linkedSetOf()) { it.externalName },
            pendingColors.mapTo(linkedSetOf()) { it.externalName },
        )
    ) {
        return SchemePruningPlan.Unavailable
    }
    return SchemePruningPlan.Prepared(qualified, attributeSteps, colorSteps)
}

private fun prepareAttributeSeeds(
    parent: Map<String, TextAttributes>,
    current: Map<String, TextAttributes>,
    targets: Set<TextAttributesKey>,
): Map<TextAttributesKey, List<TextAttributes>>? {
    val result = linkedMapOf<TextAttributesKey, List<TextAttributes>>()
    for (key in targets) {
        val original = parent[key.externalName]
        val present = current.getValue(key.externalName)
        val comparison = SchemeComparison.attributes(captureAttributeFacts(original), captureAttributeFacts(present))
        val choice =
            SchemeSeedPolicy.attributes(
                classifySchemeAttributes(original),
                classifySchemeAttributes(present),
                comparison,
            )
        result[key] =
            when (choice) {
                SchemeAttributeSeed.NONE -> emptyList()
                SchemeAttributeSeed.INHERITED -> listOf(AbstractColorsScheme.INHERITED_ATTRS_MARKER)
                SchemeAttributeSeed.PARENT -> listOf(requireNotNull(original))
                SchemeAttributeSeed.UNAVAILABLE -> return null
            }
    }
    return result.toMap()
}

private fun prepareColorSeeds(
    parent: Map<ColorKey, Color>,
    current: Map<ColorKey, Color>,
    targets: Set<ColorKey>,
): Map<ColorKey, List<Color>> =
    targets.associateWith { key ->
        val original = parent[key]
        val present = current.getValue(key)
        val comparison = SchemeComparison.colors(captureColorFacts(original), captureColorFacts(present))
        when (SchemeSeedPolicy.colors(classifySchemeColor(original), classifySchemeColor(present), comparison)) {
            SchemeColorSeed.NONE -> emptyList()
            SchemeColorSeed.INHERITED -> listOf(AbstractColorsScheme.INHERITED_COLOR_MARKER)
            SchemeColorSeed.NULL -> listOf(AbstractColorsScheme.NULL_COLOR_MARKER)
            SchemeColorSeed.PARENT -> listOf(requireNotNull(original))
            SchemeColorSeed.BARRIER -> listOf(AbstractColorsScheme.INHERITED_COLOR_MARKER, requireNotNull(original))
        }
    }
