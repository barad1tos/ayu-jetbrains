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
}
