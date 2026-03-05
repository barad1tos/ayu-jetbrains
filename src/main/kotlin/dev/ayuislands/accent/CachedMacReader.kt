package dev.ayuislands.accent

import com.intellij.openapi.util.SystemInfo
import javax.swing.SwingUtilities

class CachedMacReader<T>(
    private val ttlMs: Long = 5_000L,
    private val reader: () -> T?,
) {
    @Volatile
    private var cached: T? = null

    @Volatile
    private var timestamp: Long = 0L

    fun read(): T? {
        if (!SystemInfo.isMac) return null
        val now = System.currentTimeMillis()
        if (now - timestamp < ttlMs) return cached
        if (SwingUtilities.isEventDispatchThread()) return cached
        val result = reader()
        cached = result
        timestamp = now
        return result
    }
}
