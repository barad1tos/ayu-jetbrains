package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
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
 * Algorithmic tests for [SyntaxOverlayLoader]. Uses the `resourceBase`
 * constructor seam to point at test fixtures in
 * `src/test/resources/themes/extended-test/`.
 *
 * Plain `kotlin.test` — no platform fixture required: JDOMUtil and
 * TextAttributesKey.find are static platform calls that work in the
 * unit-test JVM (testFramework(TestFrameworkType.Platform)). Malformed-XML
 * paths use mockkStatic on JDOMUtil to inject parse failure (avoids
 * checking a malformed .xml fixture into git where pre-commit check-xml
 * would reject it).
 */
class SyntaxOverlayLoaderTest {
    companion object {
        private const val TEST_BASE = "/themes/extended-test"
    }

    @BeforeTest
    fun setUp() {
        // TextAttributesKey.find calls into ApplicationManager.getApplication()
        // which is null in plain kotlin.test JVM (no IntelliJ platform fixture).
        // Stub it to return mocks keyed by externalName so the loader can build
        // its overlay maps without booting LightPlatformTestCase.
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

    private fun loader(base: String = TEST_BASE): SyntaxOverlayLoader = SyntaxOverlayLoader(resourceBase = base)

    private fun Map<String, TextAttributes>.foregroundHex(keyName: String): String {
        val attributes = this[keyName] ?: error("missing $keyName")
        val color = attributes.foregroundColor ?: error("$keyName must define a concrete foreground")
        return "%02X%02X%02X".format(color.red, color.green, color.blue)
    }

    private fun Map<String, TextAttributes>.fontType(keyName: String): Int =
        this[keyName]?.fontType ?: error("missing $keyName")

    @Test
    fun `constructor accepts resourceBase override`() {
        val instance = SyntaxOverlayLoader(resourceBase = "/some/test/base")
        assertNotNull(instance)
    }

    @Test
    fun `default constructor uses production resourceBase`() {
        val instance = SyntaxOverlayLoader()
        assertEquals("/themes/extended", instance.resourceBase)
    }

    @Test
    fun `loads overlay XML for all three variants from test fixtures`() {
        val l = loader()
        assertTrue(l.loadOverlayForVariant("Mirage").isNotEmpty())
        assertTrue(l.loadOverlayForVariant("Dark").isNotEmpty())
        assertTrue(l.loadOverlayForVariant("Light").isNotEmpty())
    }

    @Test
    fun `Mirage overlay contains expected fake keys`() {
        val overlay = loader().loadOverlayForVariant("Mirage")
        val names = overlay.keys.map { it.externalName }
        assertTrue("FAKE_KEY_DECL_1" in names)
        assertTrue("FAKE_KEY_REF_1" in names)
        assertTrue("FAKE_KEY_COMMENT_1" in names)
    }

    @Test
    fun `malformed XML returns empty map and logs WARN (no throw)`() {
        // Inject parse failure via mockkStatic — avoids checking in a malformed
        // .xml fixture (pre-commit check-xml hook rejects malformed XML on disk).
        mockkStatic(JDOMUtil::class)
        every { JDOMUtil.load(any<java.io.InputStream>()) } throws RuntimeException("simulated parse failure")
        val overlay = loader().loadOverlayForVariant("Mirage")
        assertTrue(overlay.isEmpty())
    }

    @Test
    fun `missing resource file returns empty result without throw`() {
        val l = SyntaxOverlayLoader(resourceBase = "/themes/nonexistent-base")
        assertTrue(l.loadOverlayForVariant("Mirage").isEmpty())
    }

    @Test
    fun `loader caches overlay maps (second call returns same reference)`() {
        val l = loader()
        val first = l.loadOverlayForVariant("Mirage")
        val second = l.loadOverlayForVariant("Mirage")
        assertSame(first, second)
    }

    @Test
    fun `baseAttributes-only entry is loaded without throw (no value child)`() {
        // FAKE_KEY_WITH_BASE_REF has baseAttributes="JAVA_KEYWORD" and no <value>.
        // Loader must resolve via TextAttributesKey.find(baseRef).defaultAttributes
        // or fall back to empty TextAttributes when defaultAttributes is null.
        val overlay = loader().loadOverlayForVariant("Mirage")
        val names = overlay.keys.map { it.externalName }
        assertTrue("FAKE_KEY_WITH_BASE_REF" in names)
    }

    @Test
    fun `production baseline schemes materialize Groovy Jenkinsfile signature roles`() {
        val requiredKeys =
            listOf(
                "Class",
                "Interface name",
                "Trait name",
                "Enum name",
                "Abstract class name",
                "Anonymous class name",
                "Type parameter",
                "Groovy method declaration",
            )
        val expectedForegrounds =
            mapOf(
                "Mirage" to
                    mapOf(
                        "Class" to "73D0FF",
                        "Interface name" to "73D0FF",
                        "Trait name" to "73D0FF",
                        "Enum name" to "73D0FF",
                        "Abstract class name" to "5CCFE6",
                        "Anonymous class name" to "5CCFE6",
                        "Type parameter" to "5CCFE6",
                        "Groovy method declaration" to "FFD173",
                    ),
                "Dark" to
                    mapOf(
                        "Class" to "59C2FF",
                        "Interface name" to "59C2FF",
                        "Trait name" to "59C2FF",
                        "Enum name" to "59C2FF",
                        "Abstract class name" to "39BAE6",
                        "Anonymous class name" to "39BAE6",
                        "Type parameter" to "39BAE6",
                        "Groovy method declaration" to "FFB454",
                    ),
                "Light" to
                    mapOf(
                        "Class" to "22A4E6",
                        "Interface name" to "22A4E6",
                        "Trait name" to "22A4E6",
                        "Enum name" to "22A4E6",
                        "Abstract class name" to "55B4D4",
                        "Anonymous class name" to "55B4D4",
                        "Type parameter" to "55B4D4",
                        "Groovy method declaration" to "EBA400",
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
                    "$variant baseline scheme must map $keyName to the Groovy Jenkinsfile signature color",
                )
            }
            for ((keyName, expectedFontType) in expectedFontTypes) {
                assertEquals(
                    expectedFontType,
                    baselineByName.fontType(keyName),
                    "$variant baseline scheme must preserve $keyName Groovy Jenkinsfile signature font style",
                )
            }
        }
    }

    @Test
    fun `production extended schemes materialize Noctule Swift semantic roles`() {
        val requiredKeys =
            listOf(
                "SWIFT.VARIABLE",
                "SWIFT.IDENTIFIER",
                "SWIFT.LOCAL_VARIABLE",
                "SWIFT.PARAMETER",
                "SWIFT.ARGUMENT_LABEL",
                "SWIFT.PROPERTY",
                "SWIFT.ENUM_MEMBER",
                "SWIFT.FUNCTION_NAME",
                "SWIFT.FUNCTION_NAME_STATIC",
                "SWIFT.METHOD_NAME",
                "SWIFT.METHOD_NAME_LIBRARY",
                "SWIFT.CLASS_NAME",
                "SWIFT.STRUCT_NAME",
                "SWIFT.TYPE",
                "SWIFT.TYPEALIAS",
                "SWIFT.MODULE_NAME",
                "SWIFT.ATTRIBUTE_NAME",
                "SWIFT.DIRECTIVE",
                "SWIFT.STRING",
                "SWIFT.LINE_COMMENT",
            )
        val expectedForegrounds =
            mapOf(
                "Mirage" to
                    mapOf(
                        "SWIFT.VARIABLE" to "CCCAC2",
                        "SWIFT.PARAMETER" to "DFBFFF",
                        "SWIFT.ARGUMENT_LABEL" to "5CCFE6",
                        "SWIFT.PROPERTY" to "F28779",
                        "SWIFT.FUNCTION_NAME" to "FFD173",
                        "SWIFT.CLASS_NAME" to "73D0FF",
                        "SWIFT.ATTRIBUTE_NAME" to "FFDFB3",
                        "SWIFT.DIRECTIVE" to "FFAD66",
                    ),
                "Dark" to
                    mapOf(
                        "SWIFT.VARIABLE" to "BFBDB6",
                        "SWIFT.PARAMETER" to "D2A6FF",
                        "SWIFT.ARGUMENT_LABEL" to "39BAE6",
                        "SWIFT.PROPERTY" to "F07178",
                        "SWIFT.FUNCTION_NAME" to "FFB454",
                        "SWIFT.CLASS_NAME" to "59C2FF",
                        "SWIFT.ATTRIBUTE_NAME" to "E6C08A",
                        "SWIFT.DIRECTIVE" to "FF8F40",
                    ),
                "Light" to
                    mapOf(
                        "SWIFT.VARIABLE" to "5C6166",
                        "SWIFT.PARAMETER" to "A37ACC",
                        "SWIFT.ARGUMENT_LABEL" to "55B4D4",
                        "SWIFT.PROPERTY" to "F07171",
                        "SWIFT.FUNCTION_NAME" to "EBA400",
                        "SWIFT.CLASS_NAME" to "22A4E6",
                        "SWIFT.ATTRIBUTE_NAME" to "E59645",
                        "SWIFT.DIRECTIVE" to "FA8532",
                    ),
            )
        val l = SyntaxOverlayLoader()

        for (variant in listOf("Mirage", "Dark", "Light")) {
            val overlayByName = l.loadOverlayForVariant(variant).entries.associate { it.key.externalName to it.value }
            for (keyName in requiredKeys) {
                val attributes =
                    overlayByName[keyName]
                        ?: error("$variant extended scheme is missing $keyName")
                assertNotNull(
                    attributes.foregroundColor,
                    "$variant extended scheme must define a concrete foreground for $keyName",
                )
            }
            for ((keyName, expectedHex) in expectedForegrounds.getValue(variant)) {
                assertEquals(
                    expectedHex,
                    overlayByName.foregroundHex(keyName),
                    "$variant extended scheme must map $keyName to the Kotlin-equivalent Swift role color",
                )
            }
            assertTrue(
                listOf(
                    overlayByName.foregroundHex("SWIFT.VARIABLE"),
                    overlayByName.foregroundHex("SWIFT.PARAMETER"),
                    overlayByName.foregroundHex("SWIFT.ARGUMENT_LABEL"),
                    overlayByName.foregroundHex("SWIFT.PROPERTY"),
                    overlayByName.foregroundHex("SWIFT.FUNCTION_NAME"),
                    overlayByName.foregroundHex("SWIFT.CLASS_NAME"),
                ).distinct().size >= 6,
                "$variant extended scheme must keep core Swift semantic roles visually distinct",
            )
        }
    }

    @Test
    fun `production extended schemes materialize Groovy Jenkinsfile roles`() {
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
            val overlayByName = l.loadOverlayForVariant(variant).entries.associate { it.key.externalName to it.value }
            for (keyName in requiredKeys) {
                val attributes =
                    overlayByName[keyName]
                        ?: error("$variant extended scheme is missing $keyName")
                assertNotNull(
                    attributes.foregroundColor,
                    "$variant extended scheme must define a concrete foreground for $keyName",
                )
            }
            for ((keyName, expectedHex) in expectedForegrounds.getValue(variant)) {
                assertEquals(
                    expectedHex,
                    overlayByName.foregroundHex(keyName),
                    "$variant extended scheme must map $keyName to the Groovy Jenkinsfile role color",
                )
            }
            for ((keyName, expectedFontType) in expectedFontTypes) {
                assertEquals(
                    expectedFontType,
                    overlayByName.fontType(keyName),
                    "$variant extended scheme must preserve $keyName Groovy Jenkinsfile font style",
                )
            }
            assertTrue(
                listOf(
                    overlayByName.foregroundHex("Groovy var"),
                    overlayByName.foregroundHex("Groovy parameter"),
                    overlayByName.foregroundHex("Method call"),
                    overlayByName.foregroundHex("Instance property reference ID"),
                    overlayByName.foregroundHex("Map key"),
                    overlayByName.foregroundHex("GROOVY_KEYWORD"),
                ).distinct().size >= 6,
                "$variant extended scheme must keep core Groovy Jenkinsfile roles visually distinct",
            )
        }
    }
}
