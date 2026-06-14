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
}
