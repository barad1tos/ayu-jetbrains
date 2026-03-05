package dev.ayuislands.accent

import dev.ayuislands.accent.SystemAppearanceProvider.Appearance
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [SystemAppearanceProvider].
 *
 * Note: SystemInfo.isMac is a static final boolean and cannot be mocked.
 * The non-Mac branch is not testable in this environment.
 */
class SystemAppearanceProviderTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `Appearance enum has exactly two values`() {
        val values = Appearance.entries

        assertEquals(2, values.size)
        assertEquals(Appearance.LIGHT, values[0])
        assertEquals(Appearance.DARK, values[1])
    }

    @Test
    fun `resolve returns null on EDT with empty cache`() {
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true

        val result = SystemAppearanceProvider.resolve()

        assertNull(result)
    }
}
