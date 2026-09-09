package dev.ayuislands.accent

import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(5)
class MacPreferenceReaderTest {
    @Test
    fun `timeout bounds output read and terminates child`() {
        val process = startChildProcess("stay-alive")

        try {
            assertNull(MacPreferenceReader.read(startProcess = { process }, timeoutMs = 100L))
            assertTrue(process.waitFor(2L, TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        } finally {
            cleanUp(process)
        }
    }

    @Test
    fun `returns output from completed child`() {
        val process = startChildProcess("complete")

        try {
            val result = MacPreferenceReader.read(startProcess = { process })
            assertEquals(MacPreferenceResult(exitCode = 0, output = "4${System.lineSeparator()}"), result)
        } finally {
            cleanUp(process)
        }
    }

    @Test
    fun `preserves nonzero status from completed child`() {
        val process = startChildProcess("nonzero")

        try {
            val result = MacPreferenceReader.read(startProcess = { process })
            assertEquals(MacPreferenceResult(exitCode = 1, output = "missing${System.lineSeparator()}"), result)
        } finally {
            cleanUp(process)
        }
    }

    @Test
    fun `read failure returns null and cleans up process`() {
        val process = ReaderFailureProcess()

        assertNull(MacPreferenceReader.read(startProcess = { process }))
        assertTrue(process.wasDestroyed)
    }

    @Test
    fun `interruption returns null cleans up and restores interrupt status`() {
        val process = ReaderInterruptedProcess()

        try {
            assertNull(MacPreferenceReader.read(startProcess = { process }))
            assertTrue(process.wasDestroyed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `factory interruption returns null and restores interrupt status`() {
        try {
            val result =
                MacPreferenceReader.read(
                    startProcess = { throw InterruptedException("interrupted before start") },
                )

            assertNull(result)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private fun startChildProcess(mode: String): Process {
        val javaExecutable = "${System.getProperty("java.home")}/bin/java"
        return ProcessBuilder(
            javaExecutable,
            "-cp",
            System.getProperty("java.class.path"),
            PreferenceChildFixture::class.java.name,
            mode,
        ).start()
    }

    private fun cleanUp(process: Process) {
        if (process.isAlive) process.destroyForcibly()
        assertTrue(process.waitFor(2L, TimeUnit.SECONDS))
    }
}

internal open class CompletedPreferenceProcess(
    output: String,
    private val exitCode: Int,
) : Process() {
    private val standardOutput = ByteArrayInputStream(output.toByteArray())

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = standardOutput

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = exitCode

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = true

    override fun exitValue(): Int = exitCode

    override fun destroy() = Unit
}

private class ReaderFailureProcess : CompletedPreferenceProcess("", 0) {
    var wasDestroyed = false

    override fun getInputStream(): InputStream =
        object : InputStream() {
            override fun read(): Int = error("output unavailable")
        }

    override fun destroyForcibly(): Process {
        wasDestroyed = true
        return this
    }
}

private class ReaderInterruptedProcess : CompletedPreferenceProcess("", 0) {
    var wasDestroyed = false

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        if (wasDestroyed) return true
        throw InterruptedException("interrupted")
    }

    override fun destroyForcibly(): Process {
        wasDestroyed = true
        return this
    }
}

internal object PreferenceChildFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.single()) {
            "complete" -> println("4")
            "nonzero" -> {
                println("missing")
                kotlin.system.exitProcess(1)
            }
            "stay-alive" -> {
                println("4")
                Thread.sleep(10_000L)
            }
        }
    }
}
