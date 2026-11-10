package dev.ayuislands.studio

import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioFileLockTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `closing a same process contender cannot release the owner lock to another JVM`() {
        openStudioFiles(directory).use { owner ->
            assertTrue(owner.hasWriteLock)
            assertEquals("BUSY", observeExternalLock())
            openStudioFiles(directory).use { contender ->
                assertFalse(contender.hasWriteLock)
                assertEquals("BUSY", observeExternalLock())
            }
            assertTrue(owner.hasWriteLock)
            assertEquals("BUSY", observeExternalLock())
        }
        assertEquals("ACQUIRED", observeExternalLock())
    }

    @Test
    fun `canonical directory aliases share the same lock channel`() {
        val alias = Files.createSymbolicLink(directory.resolve("alias"), directory)
        openStudioFiles(directory).use { owner ->
            openStudioFiles(alias).use { contender -> assertFalse(contender.hasWriteLock) }
            assertTrue(owner.hasWriteLock)
            assertEquals("BUSY", observeExternalLock())
        }
    }

    @Test
    fun `foreign in process lock survives a rejected contender until safe later acquisition`() {
        val lockFile = directory.resolve("store.lock")
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                openStudioFiles(directory).use { contender -> assertFalse(contender.hasWriteLock) }
                assertEquals("BUSY", observeExternalLock())
            }
            openStudioFiles(directory).use { successor ->
                assertTrue(successor.hasWriteLock)
                assertEquals("BUSY", observeExternalLock())
            }
        }
        assertEquals("ACQUIRED", observeExternalLock())
    }

    private fun observeExternalLock(): String {
        val runtime = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val probe = StudioLockProbe::class.java
        val classpath = directory.resolve("probe-classes")
        val resource = probe.name.replace('.', '/') + ".class"
        val classFile = classpath.resolve(resource)
        Files.createDirectories(classFile.parent)
        probe.getResourceAsStream("/$resource")!!.use { Files.write(classFile, it.readAllBytes()) }
        val process =
            ProcessBuilder(runtime, "-cp", classpath.toString(), probe.name, directory.resolve("store.lock").toString())
                .redirectErrorStream(true)
                .start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Lock probe timed out")
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}
