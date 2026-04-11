package dev.ayuislands.accent

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun `cache expires after ttlMs elapsed (mocked clock)`() {
        // Drive the clock manually instead of Thread.sleep — keeps the test
        // deterministic on slow CI machines.
        var nowMs = 1_000L
        var callCount = 0
        val reader =
            CachedMacReader(
                ttlMs = 50L,
                clock = { nowMs },
            ) {
                callCount++
                "fresh-$callCount"
            }

        assertEquals("fresh-1", reader.read())
        assertEquals(1, callCount)

        // Still inside TTL window — cache hit
        nowMs += 49L
        assertEquals("fresh-1", reader.read())
        assertEquals(1, callCount)

        // Cross the TTL boundary — cache must refresh
        nowMs += 2L
        assertEquals("fresh-2", reader.read())
        assertEquals(2, callCount)
    }

    @Test
    fun `cache returns same instance within TTL across multiple calls`() {
        var callCount = 0
        val reader =
            CachedMacReader(ttlMs = 60_000L) {
                callCount++
                // Deliberately allocate a fresh object each invocation
                // so === identity distinguishes cache hits from misses.
                StringBuilder("value").toString()
            }

        val first = reader.read()
        val second = reader.read()
        val third = reader.read()
        val fourth = reader.read()
        val fifth = reader.read()

        // Reader must have been invoked exactly once
        assertEquals(1, callCount)

        // All five results equal by value
        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(first, fourth)
        assertEquals(first, fifth)

        // And they must be the SAME instance (cached, not re-read)
        assertSame(first, second)
        assertSame(first, third)
        assertSame(first, fourth)
        assertSame(first, fifth)

        // Sanity: first cached value is non-null
        assertTrue(first != null)
    }
}
