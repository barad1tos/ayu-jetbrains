package dev.ayuislands.syntax

import com.google.gson.GsonBuilder
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color

class SyntaxUpgradeCompatibilityTest {
    @Test
    fun `catalog expansion preserves captured attributes until a new cell is edited`() {
        val baseline = loadBaseline()
        val captured = capture(baseline)
        val expectedScenarios =
            baseline.editorBackgrounds.keys.flatMap { variant ->
                SyntaxPreset.entries.map { preset -> scenarioId(variant, preset) }
            }

        assertEquals(CAPTURED_HEAD, baseline.capturedFromHead)
        assertEquals(expectedScenarios.toSet(), baseline.scenarios.mapTo(linkedSetOf(), RenderingScenario::id))

        captured.scenarios.zip(baseline.scenarios).forEach { (actual, expected) ->
            assertEquals(expected.expectedAttributes, actual.expectedAttributes, expected.id)
        }
    }

    private fun capture(baseline: UpgradeBaseline): UpgradeBaseline =
        baseline.copy(
            scenarios =
                baseline.scenarios.map { scenario ->
                    scenario.copy(expectedAttributes = compute(baseline, scenario))
                },
        )

    private fun compute(
        baseline: UpgradeBaseline,
        scenario: RenderingScenario,
    ): List<AttributeSnapshot> {
        val baselineAttributes = decodeAttributes(baseline.baselineAttributes)
        val overlayAttributes = decodeAttributes(baseline.overlayAttributes)
        val result =
            SyntaxIntensityApplicator.compute(
                SyntaxIntensityApplicator.Request(
                    preset = SyntaxPreset.valueOf(scenario.preset),
                    variantName = scenario.variant,
                    editorBg = parseColor(baseline.editorBackgrounds.getValue(scenario.variant)),
                    baseline = baselineAttributes,
                    overlay = overlayAttributes,
                    customOverrides = scenario.customOverrides,
                    subordinatePreset = SyntaxPreset.valueOf(scenario.subordinatePreset),
                    customStyles = scenario.customStyles,
                    readabilityOptions = scenario.readability.toOptions(),
                    customEmphasis = scenario.customEmphasis,
                    fallbacks = baseline.fallbacks,
                ),
            )

        return baseline.trackedKeys.map { keyName ->
            val attributes =
                checkNotNull(
                    resolveAttributes(
                        keyName = keyName,
                        computed = result,
                        baseline = baselineAttributes,
                        overlay = overlayAttributes,
                        fallbacks = baseline.fallbacks,
                    ),
                ) { "${scenario.id} did not resolve $keyName" }
            attributes.snapshot(keyName)
        }
    }

    private fun resolveAttributes(
        keyName: String,
        computed: Map<TextAttributesKey, TextAttributes>,
        baseline: Map<TextAttributesKey, TextAttributes>,
        overlay: Map<TextAttributesKey, TextAttributes>,
        fallbacks: Map<String, String>,
    ): TextAttributes? {
        computed.findByName(keyName)?.let { return it }
        val direct = overlay.findByName(keyName) ?: baseline.findByName(keyName)
        if (direct?.foregroundColor != null) return direct

        val visited = mutableSetOf<String>()
        var fallbackName = fallbacks[keyName]
        while (fallbackName != null && visited.add(fallbackName)) {
            computed.findByName(fallbackName)?.let { return it }
            val fallback = overlay.findByName(fallbackName) ?: baseline.findByName(fallbackName)
            if (fallback?.foregroundColor != null) return fallback
            fallbackName = fallbacks[fallbackName]
        }
        return null
    }

    private fun Map<TextAttributesKey, TextAttributes>.findByName(name: String): TextAttributes? =
        entries.firstOrNull { (key) -> key.externalName == name }?.value

    private fun decodeAttributes(source: Map<String, AttributeSnapshot>): Map<TextAttributesKey, TextAttributes> =
        source.map { (keyName, snapshot) -> TextAttributesKey.find(keyName) to snapshot.toAttributes() }.toMap()

    private fun AttributeSnapshot.toAttributes(): TextAttributes =
        TextAttributes().also { attributes ->
            attributes.foregroundColor = foreground?.let(::parseColor)
            attributes.backgroundColor = background?.let(::parseColor)
            attributes.effectColor = effectColor?.let(::parseColor)
            attributes.effectType = effectType?.let(EffectType::valueOf)
            attributes.errorStripeColor = errorStripeColor?.let(::parseColor)
            attributes.fontType = fontType
        }

    private fun TextAttributes.snapshot(keyName: String): AttributeSnapshot =
        AttributeSnapshot(
            key = keyName,
            foreground = foregroundColor?.toArgb(),
            background = backgroundColor?.toArgb(),
            effectColor = effectColor?.toArgb(),
            effectType = effectType?.name,
            errorStripeColor = errorStripeColor?.toArgb(),
            fontType = fontType,
        )

    private fun ReadabilitySnapshot.toOptions(): SyntaxReadabilityOptions =
        SyntaxReadabilityOptions(
            dimComments = dimComments,
            softenDocumentation = softenDocumentation,
            quietOperators = quietOperators,
            emphasizeDeclarations = emphasizeDeclarations,
        )

    private fun loadBaseline(): UpgradeBaseline {
        val stream = checkNotNull(javaClass.getResourceAsStream(BASELINE_PATH)) { "Missing $BASELINE_PATH" }
        return stream.reader().use { reader -> GsonBuilder().create().fromJson(reader, UpgradeBaseline::class.java) }
    }

    private fun parseColor(value: String): Color = Color(value.removePrefix("#").toLong(16).toInt(), true)

    private fun Color.toArgb(): String = "#%08X".format(rgb)

    private fun scenarioId(
        variant: String,
        preset: SyntaxPreset,
    ): String = "${variant.lowercase()}-${preset.name.lowercase()}"

    private data class UpgradeBaseline(
        val capturedFromHead: String,
        val editorBackgrounds: Map<String, String>,
        val trackedKeys: List<String>,
        val baselineAttributes: Map<String, AttributeSnapshot>,
        val overlayAttributes: Map<String, AttributeSnapshot>,
        val fallbacks: Map<String, String>,
        val scenarios: List<RenderingScenario>,
    )

    private data class RenderingScenario(
        val id: String,
        val variant: String,
        val preset: String,
        val subordinatePreset: String,
        val customOverrides: Map<String, Map<String, Int>>,
        val customStyles: Map<String, Map<String, Int>>,
        val customEmphasis: Map<String, Map<String, Int>>,
        val readability: ReadabilitySnapshot,
        val expectedAttributes: List<AttributeSnapshot>,
    )

    private data class ReadabilitySnapshot(
        val dimComments: Boolean,
        val softenDocumentation: Boolean,
        val quietOperators: Boolean,
        val emphasizeDeclarations: Boolean,
    )

    private data class AttributeSnapshot(
        val key: String,
        val foreground: String?,
        val background: String?,
        val effectColor: String?,
        val effectType: String?,
        val errorStripeColor: String?,
        val fontType: Int,
    )

    private companion object {
        const val BASELINE_PATH = "/syntax-contract/upgrade-baseline.json"
        const val CAPTURED_HEAD = "2e1cb3b0732df456d46cd8502f7acc12275a6a2a"
    }
}
