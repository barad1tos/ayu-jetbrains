package dev.ayuislands.accent

import com.intellij.openapi.util.SystemInfo

class CachedMacReader<T>(
    private val ttlMs: Long = 5_000L,
    private val reader: () -> T?,
) {
    @Volatile
    private var cached: T? = null

    @Volatile
    private var timestamp: Long = 0L

    @Synchronized
    fun read(): T? {
        if (!SystemInfo.isMac) return null
        val now = System.currentTimeMillis()
        if (now - timestamp < ttlMs) return cached
        val result = reader()
        cached = result
        timestamp = now
        return result
    }
}
