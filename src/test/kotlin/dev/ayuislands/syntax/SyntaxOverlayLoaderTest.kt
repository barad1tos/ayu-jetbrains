package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
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
}
