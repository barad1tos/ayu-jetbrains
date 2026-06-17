package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Baseline-loader behaviour for [SyntaxOverlayLoader]. Validates
 * [loadBaselineForVariant] returns the parsed baseline `<attributes>` section
 * for each Ayu variant from the `baselineResourceBase` path, caches per
 * variant, and degrades gracefully on missing/malformed resources.
 *
 * Uses dedicated fixtures at `src/test/resources/themes/baseline-test/` so
 * the test runs without booting LightPlatformTestCase. Mock pattern mirrors
 * [SyntaxOverlayLoaderTest] (TextAttributesKey.find stubbed to return
 * deterministic mocks keyed by external name).
 */
class SyntaxOverlayLoaderBaselineTest {
    companion object {
        private const val OVERLAY_BASE = "/themes/extended"
        private const val FIXTURE_BASE = "/themes/baseline-test"
    }

    @BeforeTest
    fun setUp() {
        mockkStatic(TextAttributesKey::class)
        val cache = mutableMapOf<String, TextAttributesKey>()
        every { TextAttributesKey.find(any<String>()) } answers {
            val name = firstArg<String>()
            cache.getOrPut(name) {
                mockk(relaxed = true) {
                    every { externalName } returns name
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun loader(base: String = FIXTURE_BASE): SyntaxOverlayLoader = SyntaxOverlayLoader(OVERLAY_BASE, base)

    private fun Map<String, TextAttributes>.foregroundHex(keyName: String): String {
        val attributes = this[keyName] ?: error("missing $keyName")
        val color = attributes.foregroundColor ?: error("$keyName must define a concrete foreground")
        return "%02X%02X%02X".format(color.red, color.green, color.blue)
    }

    private fun Map<String, TextAttributes>.fontType(keyName: String): Int =
        this[keyName]?.fontType ?: error("missing $keyName")

    @Test
    fun `loadBaselineForVariant returns non-empty map for Mirage fixture`() {
        val baseline = loader().loadBaselineForVariant("Mirage")
        assertTrue(baseline.isNotEmpty(), "Mirage baseline fixture must yield at least one key")
        val names = baseline.keys.map { it.externalName }
        assertTrue("FAKE_BASELINE_COMMENT_1" in names, "expected FAKE_BASELINE_COMMENT_1 in Mirage baseline")
        assertTrue("FAKE_BASELINE_DECL_1" in names, "expected FAKE_BASELINE_DECL_1 in Mirage baseline")
    }

    @Test
    fun `loadBaselineForVariant loads all three variants from fixtures`() {
        val l = loader()
        assertTrue(l.loadBaselineForVariant("Mirage").isNotEmpty(), "Mirage baseline must be non-empty")
        assertTrue(l.loadBaselineForVariant("Dark").isNotEmpty(), "Dark baseline must be non-empty")
        assertTrue(l.loadBaselineForVariant("Light").isNotEmpty(), "Light baseline must be non-empty")
    }

    @Test
    fun `loadBaselineForVariant preserves foreground color from XML value element`() {
        val baseline = loader().loadBaselineForVariant("Mirage")
        val commentKey = baseline.keys.first { it.externalName == "FAKE_BASELINE_COMMENT_1" }
        val attrs = baseline[commentKey]
        assertNotNull(attrs)
        val fg = attrs.foregroundColor
        assertNotNull(fg, "FAKE_BASELINE_COMMENT_1 must have a parsed foreground color")
        assertEquals(0xB8, fg.red, "red channel must match XML 'B8CFE6'")
        assertEquals(0xCF, fg.green, "green channel must match XML 'B8CFE6'")
        assertEquals(0xE6, fg.blue, "blue channel must match XML 'B8CFE6'")
    }

    @Test
    fun `loadBaselineForVariant caches per variant (second call returns same reference)`() {
        val l = loader()
        val first = l.loadBaselineForVariant("Mirage")
        val second = l.loadBaselineForVariant("Mirage")
        assertSame(first, second, "baseline cache must return identical reference on second call")
    }

    @Test
    fun `loadBaselineForVariant returns empty map when resource missing (no throw)`() {
        val l = SyntaxOverlayLoader(baselineResourceBase = "/themes/nonexistent-baseline")
        val baseline = l.loadBaselineForVariant("Mirage")
        assertTrue(baseline.isEmpty(), "missing baseline resource must yield empty map (graceful)")
    }

    @Test
    fun `loadBaselineForVariant default constructor uses production baseline base`() {
        val instance = SyntaxOverlayLoader()
        assertEquals(
            "/themes",
            instance.baselineResourceBase,
            "default baselineResourceBase must point at production /themes",
        )
    }

    @Test
    fun `production baseline schemes materialize Noctule Swift local identifier keys`() {
        val requiredKeys = listOf("SWIFT.VARIABLE", "SWIFT.IDENTIFIER", "SWIFT.LOCAL_VARIABLE")
        val expectedLocalForegrounds =
            mapOf(
                "Mirage" to "CCCAC2",
                "Dark" to "BFBDB6",
                "Light" to "5C6166",
            )
        val l = SyntaxOverlayLoader()

        for (variant in listOf("Mirage", "Dark", "Light")) {
            val baselineByName = l.loadBaselineForVariant(variant).entries.associate { it.key.externalName to it.value }
            for (keyName in requiredKeys) {
                val attributes =
                    baselineByName[keyName]
                        ?: error("$variant baseline scheme is missing $keyName")
                assertNotNull(
                    attributes.foregroundColor,
                    "$variant baseline scheme must define a concrete foreground for $keyName",
                )
                assertEquals(
                    expectedLocalForegrounds.getValue(variant),
                    baselineByName.foregroundHex(keyName),
                    "$variant baseline scheme must keep $keyName on the Kotlin local-variable color",
                )
            }
        }
    }

    @Test
    fun `production baseline schemes materialize Groovy Jenkinsfile roles`() {
        val requiredKeys =
            listOf(
                "Groovy constructor declaration",
                "Groovy constructor call",
                "Groovy var",
                "Groovy reassigned var",
                "Groovy parameter",
                "Groovy reassigned parameter",
                "Method call",
                "Static method access",
                "Instance field",
                "Instance property reference ID",
                "Static field",
                "Static property reference ID",
                "Map key",
                "GROOVY_KEYWORD",
                "Groovydoc comment",
                "Groovydoc tag",
                "GString",
                "String",
                "Number",
                "Operation sign",
                "Braces",
                "Brackets",
                "Parentheses",
                "Closure braces",
                "Lambda braces",
                "Label",
                "Closure parameter",
                "Valid string escape",
                "Invalid string escape",
            )
        val expectedForegrounds =
            mapOf(
                "Mirage" to
                    mapOf(
                        "Groovy constructor declaration" to "FFCC66",
                        "Groovy constructor call" to "FFCC66",
                        "Groovy var" to "CCCAC2",
                        "Groovy reassigned var" to "DFBFFF",
                        "Groovy parameter" to "DFBFFF",
                        "Groovy reassigned parameter" to "DFBFFF",
                        "Method call" to "FFD173",
                        "Static method access" to "FFD173",
                        "Instance field" to "F28779",
                        "Instance property reference ID" to "F28779",
                        "Static field" to "F28779",
                        "Static property reference ID" to "F28779",
                        "Map key" to "D9A6EF",
                        "GROOVY_KEYWORD" to "FFAD66",
                        "Groovydoc comment" to "D5FF80",
                        "Groovydoc tag" to "B8CFE6",
                        "GString" to "D5FF80",
                        "String" to "D5FF80",
                        "Number" to "DFBFFF",
                        "Operation sign" to "F29E74",
                        "Braces" to "F29E74",
                        "Brackets" to "F29E74",
                        "Parentheses" to "F29E74",
                        "Closure braces" to "FFAD66",
                        "Lambda braces" to "FFA659",
                        "Label" to "FFA659",
                        "Closure parameter" to "DFBFFF",
                        "Valid string escape" to "95E6CB",
                        "Invalid string escape" to "D95757",
                    ),
                "Dark" to
                    mapOf(
                        "Groovy constructor declaration" to "E69F25",
                        "Groovy constructor call" to "E69F25",
                        "Groovy var" to "BFBDB6",
                        "Groovy reassigned var" to "D2A6FF",
                        "Groovy parameter" to "D2A6FF",
                        "Groovy reassigned parameter" to "D2A6FF",
                        "Method call" to "FFB454",
                        "Static method access" to "FFB454",
                        "Instance field" to "F07178",
                        "Instance property reference ID" to "F07178",
                        "Static field" to "F07178",
                        "Static property reference ID" to "F07178",
                        "Map key" to "C290DF",
                        "GROOVY_KEYWORD" to "FF8F40",
                        "Groovydoc comment" to "AAD94C",
                        "Groovydoc tag" to "ACB6BF",
                        "GString" to "AAD94C",
                        "String" to "AAD94C",
                        "Number" to "D2A6FF",
                        "Operation sign" to "F29668",
                        "Braces" to "F29668",
                        "Brackets" to "F29668",
                        "Parentheses" to "F29668",
                        "Closure braces" to "FFA759",
                        "Lambda braces" to "FFA759",
                        "Label" to "FFA759",
                        "Closure parameter" to "D2A6FF",
                        "Valid string escape" to "95E6CB",
                        "Invalid string escape" to "D95757",
                    ),
                "Light" to
                    mapOf(
                        "Groovy constructor declaration" to "D89400",
                        "Groovy constructor call" to "D89400",
                        "Groovy var" to "5C6166",
                        "Groovy reassigned var" to "A37ACC",
                        "Groovy parameter" to "A37ACC",
                        "Groovy reassigned parameter" to "A37ACC",
                        "Method call" to "EBA400",
                        "Static method access" to "EBA400",
                        "Instance field" to "F07171",
                        "Instance property reference ID" to "F07171",
                        "Static field" to "F07171",
                        "Static property reference ID" to "F07171",
                        "Map key" to "55B4D4",
                        "GROOVY_KEYWORD" to "FA8532",
                        "Groovydoc comment" to "86B300",
                        "Groovydoc tag" to "8A8FA5",
                        "GString" to "86B300",
                        "String" to "86B300",
                        "Number" to "A37ACC",
                        "Operation sign" to "F2A191",
                        "Braces" to "F2A191",
                        "Brackets" to "F2A191",
                        "Parentheses" to "F2A191",
                        "Closure braces" to "FA8532",
                        "Lambda braces" to "FA8532",
                        "Label" to "FA8532",
                        "Closure parameter" to "A37ACC",
                        "Valid string escape" to "4CBF99",
                        "Invalid string escape" to "E65050",
                    ),
            )
        val expectedFontTypes =
            mapOf(
                "Class" to 2,
                "Interface name" to 2,
                "Trait name" to 2,
                "Enum name" to 2,
                "Abstract class name" to 2,
                "Anonymous class name" to 2,
                "Type parameter" to 2,
                "Groovy constructor declaration" to 2,
                "Groovy constructor call" to 2,
                "Static method access" to 2,
                "Static field" to 2,
                "Static property reference ID" to 2,
                "GROOVY_KEYWORD" to 1,
                "Groovydoc comment" to 2,
                "Groovydoc tag" to 3,
                "Closure parameter" to 2,
            )
        val l = SyntaxOverlayLoader()

        for (variant in listOf("Mirage", "Dark", "Light")) {
            val baselineByName = l.loadBaselineForVariant(variant).entries.associate { it.key.externalName to it.value }
            for (keyName in requiredKeys) {
                val attributes =
                    baselineByName[keyName]
                        ?: error("$variant baseline scheme is missing $keyName")
                assertNotNull(
                    attributes.foregroundColor,
                    "$variant baseline scheme must define a concrete foreground for $keyName",
                )
            }
            for ((keyName, expectedHex) in expectedForegrounds.getValue(variant)) {
                assertEquals(
                    expectedHex,
                    baselineByName.foregroundHex(keyName),
                    "$variant baseline scheme must map $keyName to the Groovy Jenkinsfile role color",
                )
            }
            for ((keyName, expectedFontType) in expectedFontTypes) {
                assertEquals(
                    expectedFontType,
                    baselineByName.fontType(keyName),
                    "$variant baseline scheme must preserve $keyName Groovy Jenkinsfile font style",
                )
            }
        }
    }

    @Test
    fun `production baseline schemes materialize Ayu inlay type hint foregrounds`() {
        val expectedForegrounds =
            mapOf(
                "Mirage" to "5CCFE6",
                "Dark" to "39BAE6",
                "Light" to "55B4D4",
            )
        val loader = SyntaxOverlayLoader()

        for (variant in listOf("Mirage", "Dark", "Light")) {
            val baselineByName =
                loader.loadBaselineForVariant(variant).entries.associate {
                    it.key.externalName to it.value
                }
            val attributes =
                baselineByName["INLAY_TEXT_WITHOUT_BACKGROUND"]
                    ?: error("$variant baseline scheme is missing INLAY_TEXT_WITHOUT_BACKGROUND")
            assertNotNull(
                attributes.foregroundColor,
                "$variant baseline scheme must define a concrete foreground for inlay type hints",
            )
            assertEquals(
                expectedForegrounds.getValue(variant),
                baselineByName.foregroundHex("INLAY_TEXT_WITHOUT_BACKGROUND"),
                "$variant baseline scheme must map inlay type hints to the Ayu type color",
            )
        }
    }
}
