package dev.ayuislands.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RED → GREEN invariants for [PrimitiveCategory] — the 16-value closed catalog
 * that bounds the granularity of preset curves and Custom drill-down sliders
 * (D-05, INTENSITY-05).
 *
 * Pins:
 *  - cardinality (== 16; adding a 17th category MUST require an explicit
 *    schema bump, not a drive-by enum addition)
 *  - non-blank display names (UI labels must be presentable)
 *  - unique names (no duplicate enum constants — guarded by the language too,
 *    but locked here for documentation)
 *  - presence of the 16 expected entries (FUNCTION_DECL … DOCUMENTATION) so a
 *    rename leaks into a compile error AND a test failure
 *  - exact display-name strings from the D-05 catalog
 */
class PrimitiveCategoryTest {
    private val expectedDisplayNames: Map<PrimitiveCategory, String> =
        mapOf(
            PrimitiveCategory.FUNCTION_DECL to "Function declaration",
            PrimitiveCategory.CLASS_DECL to "Class declaration",
            PrimitiveCategory.INTERFACE_DECL to "Interface declaration",
            PrimitiveCategory.KEYWORD to "Keyword",
            PrimitiveCategory.PARAMETER to "Parameter",
            PrimitiveCategory.LOCAL_VAR to "Local variable",
            PrimitiveCategory.STRING_LITERAL to "String literal",
            PrimitiveCategory.NUMBER_LITERAL to "Number literal",
            PrimitiveCategory.COMMENT to "Comment",
            PrimitiveCategory.ANNOTATION to "Annotation",
            PrimitiveCategory.OPERATOR to "Operator",
            PrimitiveCategory.TYPE_REF to "Type reference",
            PrimitiveCategory.STATIC_FIELD to "Static field",
            PrimitiveCategory.INSTANCE_FIELD to "Instance field",
            PrimitiveCategory.GENERICS to "Generics",
            PrimitiveCategory.DOCUMENTATION to "Documentation",
        )

    @Test
    fun `catalog is exactly 16 entries (closed catalog per D-05)`() {
        assertEquals(
            16,
            PrimitiveCategory.entries.size,
            "PrimitiveCategory MUST remain a 16-value closed catalog — adding a " +
                "17th entry requires a schema bump + migration plan, not a drive-by edit.",
        )
    }

    @Test
    fun `every entry has a non-blank display name`() {
        for (category in PrimitiveCategory.entries) {
            assertTrue(
                category.displayName.isNotBlank(),
                "${category.name}.displayName must be non-blank for UI rendering",
            )
        }
    }

    @Test
    fun `entry names are unique`() {
        val names = PrimitiveCategory.entries.map { it.name }
        val duplicates = names.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertEquals(
            names.size,
            names.toSet().size,
            "PrimitiveCategory entry names must be unique — duplicates: $duplicates",
        )
    }

    @Test
    fun `all 16 expected entries are present`() {
        val actual = PrimitiveCategory.entries.map { it.name }.toSet()
        val expected = expectedDisplayNames.keys.map { it.name }.toSet()
        assertEquals(
            expected,
            actual,
            "PrimitiveCategory entries diverged from the D-05 catalog. " +
                "Missing: ${expected - actual}; Unexpected: ${actual - expected}",
        )
    }

    @Test
    fun `display names match the D-05 catalog exactly`() {
        for ((category, expectedLabel) in expectedDisplayNames) {
            assertEquals(
                expectedLabel,
                category.displayName,
                "${category.name}.displayName drifted from D-05 catalog",
            )
        }
    }

    @Test
    fun `primitive storage ids preserve every existing persisted enum name`() {
        val expected = PrimitiveCategory.entries.associate { it.name to it.name }
        val actual = PrimitiveCategory.entries.associate { it.name to it.specification.storageId }

        assertEquals(expected, actual)
    }

    @Test
    fun `enum iteration order remains compatible with the legacy catalog`() {
        assertEquals(
            listOf(
                "FUNCTION_DECL",
                "CLASS_DECL",
                "INTERFACE_DECL",
                "KEYWORD",
                "PARAMETER",
                "LOCAL_VAR",
                "STRING_LITERAL",
                "NUMBER_LITERAL",
                "COMMENT",
                "ANNOTATION",
                "OPERATOR",
                "TYPE_REF",
                "STATIC_FIELD",
                "INSTANCE_FIELD",
                "GENERICS",
                "DOCUMENTATION",
            ),
            PrimitiveCategory.entries.map(PrimitiveCategory::name),
        )
    }

    @Test
    fun `primitive specifications have stable presentation order within each group`() {
        for ((group, categories) in PrimitiveCategory.entries.groupBy { it.specification.group }) {
            val orders = categories.map { it.specification.order }
            assertEquals(orders.size, orders.toSet().size, "$group contains duplicate presentation orders")
        }
    }
}
