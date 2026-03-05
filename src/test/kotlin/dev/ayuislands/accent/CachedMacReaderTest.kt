package dev.ayuislands.accent

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [CachedMacReader].
 *
 * CachedMacReader guards with `SystemInfo.isMac` (static final boolean),
 * so these tests only run on macOS where that guard passes.
 */
@EnabledOnOs(OS.MAC)
class CachedMacReaderTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `invokes reader on Mac when cache expired`() {
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns false

        var callCount = 0
        val reader =
            CachedMacReader(ttlMs = 0L) {
                callCount++
                "result-$callCount"
            }

        assertEquals("result-1", reader.read())
        assertEquals("result-2", reader.read())
        assertEquals(2, callCount)
    }

    @Test
    fun `returns cached value within TTL`() {
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns false

        var callCount = 0
        val reader =
            CachedMacReader(ttlMs = 60_000L) {
                callCount++
                "cached-value"
            }

        assertEquals("cached-value", reader.read())
        assertEquals("cached-value", reader.read())
        assertEquals(1, callCount)
    }

    @Test
    fun `skips refresh on EDT and returns stale cache`() {
        mockkStatic(SwingUtilities::class)

        var callCount = 0
        val reader =
            CachedMacReader(ttlMs = 0L) {
                callCount++
                "fresh-value"
            }

        // Prime the cache from a non-EDT context
        every { SwingUtilities.isEventDispatchThread() } returns false
        assertEquals("fresh-value", reader.read())
        assertEquals(1, callCount)

        // On EDT with expired TTL, should return stale cache without invoking the reader
        every { SwingUtilities.isEventDispatchThread() } returns true
        assertEquals("fresh-value", reader.read())
        assertEquals(1, callCount)
    }

    @Test
    fun `returns null when reader returns null`() {
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns false

        val reader = CachedMacReader<String>(ttlMs = 0L) { null }

        assertNull(reader.read())
    }

    @Test
    fun `returns null on EDT when cache never primed`() {
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true

        val reader = CachedMacReader(ttlMs = 0L) { "value" }

        assertNull(reader.read())
    }
}
