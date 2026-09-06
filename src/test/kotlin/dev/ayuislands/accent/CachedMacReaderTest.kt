package dev.ayuislands.accent

import com.intellij.openapi.util.SystemInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class CachedMacReaderTest {
    @Test
    fun `unsupported platform avoids clock and reader`() {
        val cache =
            CachedMacReader<String>(
                isSupported = false,
                clock = { error("Unsupported platforms must not consult the clock") },
                reader = { error("Unsupported platforms must not read macOS preferences") },
            )

        assertNull(cache.read())
        assertNull(cache.read())
    }

    @Test
    fun `cached value refreshes at exact TTL boundary`() {
        var nowMs = 1_000L
        var systemAccent = "#73D0FF"
        val cache =
            CachedMacReader(
                ttlMs = 50L,
                clock = { nowMs },
                isSupported = true,
                reader = { systemAccent },
            )

        assertEquals("#73D0FF", cache.read())
        systemAccent = "#F27983"
        nowMs = 1_049L
        assertEquals("#73D0FF", cache.read())
        nowMs = 1_050L
        assertEquals("#F27983", cache.read())
    }

    @Test
    fun `zero TTL refreshes on every read`() {
        val firstAccent = AccentValue("#73D0FF")
        val secondAccent = AccentValue("#F27983")
        val accents = ArrayDeque(listOf(firstAccent, secondAccent))
        val cache =
            CachedMacReader(
                ttlMs = 0L,
                isSupported = true,
                reader = accents::removeFirst,
            )

        assertSame(firstAccent, cache.read())
        assertSame(secondAccent, cache.read())
    }

    @Test
    fun `null remains cached until TTL then reader recovers`() {
        var nowMs = 1_000L
        var systemAccent: String? = null
        var readCount = 0
        val cache =
            CachedMacReader(
                ttlMs = 50L,
                clock = { nowMs },
                isSupported = true,
                reader = {
                    readCount++
                    systemAccent
                },
            )

        assertNull(cache.read())
        systemAccent = "#73D0FF"
        nowMs = 1_049L
        assertNull(cache.read())
        assertEquals(1, readCount)
        nowMs = 1_050L
        assertEquals("#73D0FF", cache.read())
        assertEquals(2, readCount)
    }

    @Test
    fun `reader exception propagates and next read retries`() {
        val readFailure = IllegalStateException("macOS accent preference unavailable")
        var shouldFail = true
        val cache =
            CachedMacReader(
                clock = { 10_000L },
                isSupported = true,
                reader = {
                    if (shouldFail) {
                        shouldFail = false
                        throw readFailure
                    }
                    "#73D0FF"
                },
            )

        assertSame(readFailure, assertFailsWith<IllegalStateException> { cache.read() })
        assertEquals("#73D0FF", cache.read())
    }

    @Test
    fun `cached value keeps the same instance within TTL`() {
        val cache =
            CachedMacReader(
                ttlMs = 60_000L,
                clock = { 100_000L },
                isSupported = true,
                reader = { AccentValue("#73D0FF") },
            )

        val firstRead = assertNotNull(cache.read())

        assertSame(firstRead, cache.read())
    }

    @Test
    fun `default platform support follows SystemInfo`() {
        var readCount = 0
        val cache =
            CachedMacReader {
                readCount++
                "#73D0FF"
            }

        val accent = cache.read()

        if (SystemInfo.isMac) {
            assertEquals("#73D0FF", accent)
            assertEquals(1, readCount)
        } else {
            assertNull(accent)
            assertEquals(0, readCount)
        }
    }

    private data class AccentValue(
        val hex: String,
    )
}
