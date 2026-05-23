package dev.ayuislands.syntax

import com.intellij.openapi.util.JDOMUtil
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Algorithmic tests for [SyntaxOverlayLoader]. Uses the `resourceBase`
 * constructor seam (warning #2 fix) to point at test fixtures in
 * `src/test/resources/themes/extended-{test,missing-tier}/`.
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
        private const val MISSING_TIER_BASE = "/themes/extended-missing-tier"
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun loader(base: String = TEST_BASE): SyntaxOverlayLoader = SyntaxOverlayLoader(resourceBase = base)

    @Test
    fun `constructor accepts resourceBase override (revision iteration 1, warning 2)`() {
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
    fun `parses mood-tiers txt into three non-empty tier sets`() {
        val l = loader()
        assertTrue(l.tierKeys(SyntaxMood.STANDARD).isNotEmpty())
        assertTrue(l.tierKeys(SyntaxMood.RICH).isNotEmpty())
        assertTrue(l.tierKeys(SyntaxMood.MAXIMUM).isNotEmpty())
    }

    @Test
    fun `parses axis-keys txt into four axis sets matching StyleAxis entries`() {
        val l = loader()
        StyleAxis.entries.forEach { axis ->
            assertTrue(
                l.axisKeys(axis).isNotEmpty(),
                "axis $axis must have at least one key in test fixture",
            )
        }
    }

    @Test
    fun `MINIMAL tier returns empty set (empty subset of overlay)`() {
        assertEquals(emptySet<Any>(), loader().tierKeys(SyntaxMood.MINIMAL))
    }

    @Test
    fun `malformed XML returns empty map and logs WARN (no throw)`() {
        // Inject parse failure via mockkStatic — avoids checking in a malformed
        // .xml fixture (pre-commit check-xml hook rejects malformed XML on disk).
        mockkStatic(JDOMUtil::class)
        every { JDOMUtil.load(any<java.io.InputStream>()) } throws RuntimeException("simulated parse failure")
        val overlay = loader().loadOverlayForVariant("Mirage")
        assertEquals(emptyMap<Any, Any>(), overlay)
    }

    @Test
    fun `missing mood-tier in txt returns empty set for that tier`() {
        val l = loader(base = MISSING_TIER_BASE)
        assertEquals(emptySet<Any>(), l.tierKeys(SyntaxMood.RICH))
        assertTrue(l.tierKeys(SyntaxMood.STANDARD).isNotEmpty())
        assertTrue(l.tierKeys(SyntaxMood.MAXIMUM).isNotEmpty())
    }

    @Test
    fun `missing resource file returns empty result without throw`() {
        val l = SyntaxOverlayLoader(resourceBase = "/themes/nonexistent-base")
        assertEquals(emptyMap<Any, Any>(), l.loadOverlayForVariant("Mirage"))
        assertEquals(emptySet<Any>(), l.tierKeys(SyntaxMood.STANDARD))
        StyleAxis.entries.forEach { axis ->
            assertEquals(emptySet<Any>(), l.axisKeys(axis))
        }
    }

    @Test
    fun `loader caches overlay maps (second call returns same reference)`() {
        val l = loader()
        val first = l.loadOverlayForVariant("Mirage")
        val second = l.loadOverlayForVariant("Mirage")
        assertSame(first, second)
    }

    @Test
    fun `loader caches tier maps (second call returns same reference)`() {
        val l = loader()
        val first = l.tierKeys(SyntaxMood.STANDARD)
        val second = l.tierKeys(SyntaxMood.STANDARD)
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
}
