package dev.ayuislands.accent

import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [SystemAccentProvider].
 *
 * Note: SystemInfo.isMac is a static final boolean and cannot be mocked.
 * The non-Mac branch is not testable in this environment.
 */
class SystemAccentProviderTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `accent map covers all 8 macOS values`() {
        val field = SystemAccentProvider::class.java.getDeclaredField("MACOS_ACCENT_MAP")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(SystemAccentProvider) as Map<Int, String>

        val expectedKeys = setOf(-1, 0, 1, 2, 3, 4, 5, 6)

        assertEquals(expectedKeys, map.keys)
        assertEquals(8, map.size)
    }

    @Test
    fun `all accent hex values are valid 7-char hex strings`() {
        val field = SystemAccentProvider::class.java.getDeclaredField("MACOS_ACCENT_MAP")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(SystemAccentProvider) as Map<Int, String>

        val hexPattern = Regex("^#[0-9A-Fa-f]{6}$")

        map.forEach { (key, value) ->
            assertEquals(7, value.length, "Accent $key hex '$value' should be 7 chars")
            assertTrue(hexPattern.matches(value), "Accent $key hex '$value' should match #RRGGBB")
        }
    }

    @Test
    fun `default accent hex is blue`() {
        val field = SystemAccentProvider::class.java.getDeclaredField("DEFAULT_ACCENT_HEX")
        field.isAccessible = true
        val defaultHex = field.get(SystemAccentProvider) as String

        assertEquals("#73D0FF", defaultHex)
    }
}
