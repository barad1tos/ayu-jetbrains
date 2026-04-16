package dev.ayuislands.accent

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedup'd background scheduler for [ProjectLanguageScanner] runs.
 *
 * [ProjectLanguageDetector.dominant] is reachable from the EDT via settings UI,
 * focus-swap listeners, and rotation ticks. A first-call scan over a 10 000-file
 * monorepo takes hundreds of milliseconds — unacceptable on EDT. This scheduler
 * lets the detector kick the scan onto a shared pool, return null immediately
 * (caller falls through to the global accent), and hit the warm cache on the
 * next call.
 *
 * Dedup gate: multiple simultaneous `dominant()` calls for the same project key
 * must not schedule redundant scans. The in-flight set is a keyset-backed
 * ConcurrentHashMap so `add` / `remove` are atomic without separate locking.
 */
internal object ProjectLanguageScanAsync {
    private val LOG = logger<ProjectLanguageScanAsync>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True when a scan for [key] is currently scheduled or running. */
    fun isInFlight(key: String): Boolean = key in inFlight

    /**
     * Schedule [task] on the IDE's shared application-pool executor, gated on
     * [key] so duplicate schedulings for the same project become no-ops.
     *
     * Returns true when the task was actually scheduled, false when a prior scan
     * for the same key is still in flight — callers can use the return value to
     * decide whether to fall through to a synchronous path or simply bail out.
     */
    fun schedule(
        key: String,
        task: () -> Unit,
    ): Boolean {
        if (!inFlight.add(key)) return false
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                task()
            } catch (exception: kotlin.coroutines.cancellation.CancellationException) {
                // Pool-level cancellation: IDE is shutting down. Let it propagate.
                throw exception
            } catch (exception: RuntimeException) {
                // Detector already logs its own warnings; double-log at debug to
                // preserve the async scheduling context if someone greps for it.
                LOG.debug("Background language scan for '$key' threw; cache unchanged", exception)
            } finally {
                inFlight.remove(key)
            }
        }
        return true
    }

    @TestOnly
    internal fun clearForTest() {
        inFlight.clear()
    }
}
