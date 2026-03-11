package dev.ayuislands.accent

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
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
    @Test
    fun `invokes reader on Mac when cache expired`() {
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
    fun `returns null when reader returns null`() {
        val reader = CachedMacReader<String>(ttlMs = 0L) { null }

        assertNull(reader.read())
    }
}
