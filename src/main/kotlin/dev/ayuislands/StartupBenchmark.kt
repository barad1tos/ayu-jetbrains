package dev.ayuislands

import com.intellij.openapi.diagnostic.logger
import java.security.MessageDigest

/**
 * Lightweight CPU microbenchmark used to derive an adaptive
 * onboarding delay that scales with hardware speed.
 */
internal object StartupBenchmark {
    private val LOG = logger<StartupBenchmark>()
    private const val ITERATIONS = 10_000
    private const val PAYLOAD_SIZE = 64
    private const val NANOS_PER_MS = 1_000_000

    /**
     * Runs a CPU microbenchmark by hashing a 64-byte payload
     * [ITERATIONS] times with SHA-256. Returns elapsed milliseconds.
     *
     * Must be called from a background thread.
     */
    fun measureCpuSpeed(): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val payload = ByteArray(PAYLOAD_SIZE)

        // Warm-up: ensure JCE provider is fully loaded before timing
        digest.update(payload)
        digest.digest()

        val startNanos = System.nanoTime()
        repeat(ITERATIONS) {
            digest.update(payload)
            digest.digest()
        }
        val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MS

        LOG.info("Ayu benchmark: $ITERATIONS SHA-256 iterations in ${elapsedMs}ms")
        return elapsedMs
    }
}
