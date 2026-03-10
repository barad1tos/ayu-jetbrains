package dev.ayuislands.accent

import dev.ayuislands.accent.SystemAppearanceProvider.Appearance
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [SystemAppearanceProvider].
 *
 * Note: SystemInfo.isMac is a static final boolean and cannot be mocked.
 * The non-Mac branch is not testable in this environment.
 */
class SystemAppearanceProviderTest {
    @Test
    fun `Appearance enum has exactly two values`() {
        val values = Appearance.entries

        assertEquals(2, values.size)
        assertEquals(Appearance.LIGHT, values[0])
        assertEquals(Appearance.DARK, values[1])
    }
}
