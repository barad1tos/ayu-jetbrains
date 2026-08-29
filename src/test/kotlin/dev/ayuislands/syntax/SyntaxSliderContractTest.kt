package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class SyntaxSliderContractTest {
    @Test
    fun `every exposed syntax cell changes an owned foreground between slider endpoints`() {
        val loader = SyntaxOverlayLoader()
        val failures =
            variants.flatMap { variant ->
                SyntaxSliderContract
                    .evaluate(
                        variantName = variant.name,
                        editorBackground = variant.editorBackground,
                        baseline = loader.loadBaselineForVariant(variant.name),
                        overlay = loader.loadOverlayForVariant(variant.name),
                        fallbacks = loader.fallbacksFor(variant.name),
                    ).filterNot(SliderEvidence::isEffective)
                    .map { evidence -> variant.name to evidence }
            }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Enabled syntax cells with no endpoint effect:\n",
                separator = "\n",
            ) { (variant, evidence) ->
                "$variant ${evidence.cell.language}/${evidence.cell.category}: " +
                    "sources=${evidence.sourceKeys}"
            },
        )
    }

    @Test
    fun `direct Java keyword reports its changed key`() {
        val keyword = key("JAVA_KEYWORD")

        val evidence =
            SyntaxSliderContract.evaluate(
                variantName = "Mirage",
                editorBackground = MIRAGE_BACKGROUND,
                baseline = mapOf(keyword to attributes(0xFFCC66)),
                overlay = emptyMap(),
                fallbacks = emptyMap(),
            )

        assertEquals(
            setOf("JAVA_KEYWORD"),
            evidence.single().changedKeys,
            "Moving the Java keyword slider must change its owned editor key",
        )
    }

    @Test
    fun `inherited Swift brackets use the supplied fallback instead of platform defaults`() {
        val brackets = key("SWIFT.BRACKETS")
        val defaultBrackets = key("DEFAULT_BRACKETS")

        val evidence =
            SyntaxSliderContract.evaluate(
                variantName = "Mirage",
                editorBackground = MIRAGE_BACKGROUND,
                baseline =
                    mapOf(
                        brackets to TextAttributes(),
                        defaultBrackets to attributes(0xCCCAC2),
                    ),
                overlay = emptyMap(),
                fallbacks = mapOf("SWIFT.BRACKETS" to "DEFAULT_BRACKETS"),
            )

        assertEquals(
            setOf("SWIFT.BRACKETS"),
            evidence.single { it.cell.language == "Swift" }.changedKeys,
            "The Swift bracket slider must resolve the explicit Ayu fallback",
        )
    }

    @Test
    fun `unresolvable inherited key is not exposed as tunable`() {
        val brackets = key("SWIFT.BRACKETS")

        val evidence =
            SyntaxSliderContract.evaluate(
                variantName = "Mirage",
                editorBackground = MIRAGE_BACKGROUND,
                baseline = mapOf(brackets to TextAttributes()),
                overlay = emptyMap(),
                fallbacks = emptyMap(),
            )

        assertTrue(
            evidence.isEmpty(),
            "A key with no resolvable foreground must not expose an intensity slider",
        )
    }

    private data class VariantCase(
        val name: String,
        val editorBackground: Color,
    )

    private companion object {
        private val MIRAGE_BACKGROUND = Color(0x1F, 0x24, 0x30)

        private val variants =
            listOf(
                VariantCase("Mirage", MIRAGE_BACKGROUND),
                VariantCase("Dark", Color(0x0D, 0x10, 0x17)),
                VariantCase("Light", Color(0xFC, 0xFC, 0xFC)),
            )

        private fun key(name: String): TextAttributesKey = TextAttributesKey.createTextAttributesKey(name)

        private fun attributes(rgb: Int): TextAttributes =
            TextAttributes().also { attributes -> attributes.foregroundColor = Color(rgb) }
    }
}
