package dev.ayuislands.accent

import com.intellij.openapi.util.SystemInfo

/**
 * Caches a nullable macOS system value for [ttlMs] milliseconds.
 *
 * [isSupported] isolates the platform boundary so the cache contract remains portable.
 */
class CachedMacReader<T>(
    private val ttlMs: Long = 5_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isSupported: Boolean = SystemInfo.isMac,
    private val reader: () -> T?,
) {
    @Volatile
    private var cached: T? = null

    @Volatile
    private var timestamp: Long = 0L

    @Synchronized
    fun read(): T? {
        if (!isSupported) return null
        val now = clock()
        if (now - timestamp < ttlMs) return cached
        val result = reader()
        cached = result
        timestamp = now
        return result
    }
}
