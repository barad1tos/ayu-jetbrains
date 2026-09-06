package dev.ayuislands.accent

import java.util.concurrent.TimeUnit

internal data class MacPreferenceResult(
    val exitCode: Int,
    val output: String,
)

/** Reads short scalar output from macOS preference processes. */
internal object MacPreferenceReader {
    fun read(
        startProcess: () -> Process,
        timeoutMs: Long = 2_000L,
    ): MacPreferenceResult? {
        var process: Process? = null
        return try {
            process = startProcess()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                terminateProcess(process)
                return null
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            MacPreferenceResult(process.exitValue(), output)
        } catch (_: InterruptedException) {
            process?.let(::terminateProcess)
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            process?.let(::terminateProcess)
            null
        }
    }

    private fun terminateProcess(process: Process) {
        try {
            process.destroyForcibly()
        } catch (_: Exception) {
            // The original read failure remains the observable result.
        }
    }
}
