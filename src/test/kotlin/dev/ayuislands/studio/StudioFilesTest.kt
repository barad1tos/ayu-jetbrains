package dev.ayuislands.studio

import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.createVerified
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.persistence.writeVerified
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioFilesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `only the lock owner may change files and another reader does not release its lock`() {
        val bytes = byteArrayOf(7, 0, -1)
        openStudioFiles(directory).use { owner ->
            assertTrue(owner.hasWriteLock)
            owner.writeVerified("state.xml", bytes, "first")

            openStudioFiles(directory.resolve(".")).use { reader ->
                assertFalse(reader.hasWriteLock)
                assertContentEquals(bytes, reader.read("state.xml"))
                assertFailsWith<IllegalStateException> { reader.stage("stage.xml") }
                assertFailsWith<IllegalStateException> { reader.createVerified("archive.xml", bytes) }
                assertFailsWith<IllegalStateException> { reader.replace("state.xml", "other.xml") }
                assertFailsWith<IllegalStateException> { reader.remove("state.xml") }
            }

            assertTrue(owner.hasWriteLock)
            openStudioFiles(directory).use { assertFalse(it.hasWriteLock) }
            owner.writeVerified("state.xml", byteArrayOf(8), "next")
        }
        openStudioFiles(directory).use { next ->
            assertTrue(next.hasWriteLock)
            assertContentEquals(byteArrayOf(8), next.read("state.xml"))
            assertNull(next.read("state.xml.tmp.next"))
        }
    }

    @Test
    fun `archive creation never overwrites retained bytes`() {
        openStudioFiles(directory).use { files ->
            val archive = "source-backup/first/settings.xml"
            files.stage("$archive.tmp.archive").use { staged ->
                staged.write(byteArrayOf(0))
                staged.force()
            }
            assertNull(files.read(archive))
            files.createVerified(archive, byteArrayOf(0, -1))
            val orphan = "$archive.tmp.archive"
            files.stage(orphan).use { it.write(byteArrayOf(9)) }
            assertFailsWith<IOException> { files.publishNew(orphan, archive) }
            assertContentEquals(byteArrayOf(0, -1), files.read(archive))
            files.createVerified(archive, byteArrayOf(0, -1))
            assertNull(files.read(orphan))
            assertFailsWith<IOException> { files.createVerified(archive, byteArrayOf(9)) }
            assertContentEquals(byteArrayOf(0, -1), files.read(archive))
            assertEquals(listOf("first"), files.directories("source-backup"))
            assertEquals(emptyList(), files.directories("absent"))
            files.remove(archive)
            assertNull(files.read(archive))
        }
    }

    @Test
    fun `concurrent archive requests cannot interleave staging and publication`() {
        openStudioFiles(directory).use { actual ->
            val staged = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val contender = CountDownLatch(1)
            val paused =
                object : StudioFiles by actual {
                    override fun publishNew(
                        source: String,
                        destination: String,
                    ) {
                        staged.countDown()
                        check(resume.await(10, TimeUnit.SECONDS))
                        actual.publishNew(source, destination)
                    }
                }
            Executors.newFixedThreadPool(2).use { executor ->
                val first = executor.submit { paused.createVerified("archive.xml", byteArrayOf(1)) }
                try {
                    assertTrue(staged.await(10, TimeUnit.SECONDS))
                    val second =
                        executor.submit {
                            contender.countDown()
                            assertFailsWith<IOException> { actual.createVerified("archive.xml", byteArrayOf(2)) }
                        }
                    assertTrue(contender.await(10, TimeUnit.SECONDS))
                    assertFailsWith<TimeoutException> { second.get(150, TimeUnit.MILLISECONDS) }
                    resume.countDown()
                    first.get(10, TimeUnit.SECONDS)
                    second.get(10, TimeUnit.SECONDS)
                } finally {
                    resume.countDown()
                }
            }
            assertContentEquals(byteArrayOf(1), actual.read("archive.xml"))
        }
    }

    @Test
    fun `a staged handle cannot write after ownership has passed to another handle`() {
        val owner = openStudioFiles(directory)
        owner.stage("stage.xml").use { staged ->
            owner.close()
            openStudioFiles(directory).use { next ->
                assertTrue(next.hasWriteLock)
                assertFailsWith<IllegalStateException> { staged.write(byteArrayOf(9)) }
                assertFailsWith<IllegalStateException> { staged.force() }
                assertContentEquals(byteArrayOf(), next.read("stage.xml"))
            }
        }
    }

    @Test
    fun `reusing an orphaned staging name cannot mutate the published archive`() {
        openStudioFiles(directory).use { files ->
            val staging = "archive.xml.tmp.archive"
            files.stage(staging).use { it.write(byteArrayOf(1)) }
            files.publishNew(staging, "archive.xml")
            files.stage(staging).use { it.write(byteArrayOf(2)) }
            assertContentEquals(byteArrayOf(1), files.read("archive.xml"))
            assertContentEquals(byteArrayOf(2), files.read(staging))
        }
    }

    @Test
    fun `staged mismatch leaves old bytes and replaced mismatch does not report success`() {
        val previous = "before".encodeToByteArray()
        val candidate = "after".encodeToByteArray()
        for (badRead in listOf("state.xml.tmp.next", "state.xml")) {
            openStudioFiles(directory).use { actual ->
                actual.writeVerified("state.xml", previous, "first")
                val corruptRead =
                    object : StudioFiles by actual {
                        override fun read(name: String): ByteArray? =
                            actual.read(name)?.let { if (name == badRead) it + 32.toByte() else it }
                    }
                assertFailsWith<IOException> { corruptRead.writeVerified("state.xml", candidate, "next") }
                val expected = if (badRead == "state.xml") candidate else previous
                assertContentEquals(expected, actual.read("state.xml"))
            }
        }
    }

    @Test
    fun `unsupported atomic replacement does not fall back to overwrite`() {
        openStudioFiles(directory).use { actual ->
            actual.writeVerified("state.xml", byteArrayOf(1), "first")
            val unsupported =
                object : StudioFiles by actual {
                    override fun replace(
                        source: String,
                        destination: String,
                    ): Unit = throw AtomicMoveNotSupportedException(source, destination, "test provider")
                }
            assertFailsWith<AtomicMoveNotSupportedException> {
                unsupported.writeVerified("state.xml", byteArrayOf(2), "next")
            }
            assertContentEquals(byteArrayOf(1), actual.read("state.xml"))
        }
    }

    @Test
    fun `file operations do not follow links or replace the ownership marker`() {
        val root = directory.resolve("store")
        val foreign = Files.createDirectory(directory.resolve("foreign"))
        val outside = Files.write(foreign.resolve("settings.xml"), byteArrayOf(7))
        openStudioFiles(root).use { files ->
            Files.createSymbolicLink(root.resolve("redirect"), foreign)
            Files.createSymbolicLink(root.resolve("linked.xml"), outside)
            assertFailsWith<IOException> { files.read("linked.xml") }
            assertFailsWith<IOException> { files.stage("redirect/settings.xml").close() }
            assertFailsWith<IllegalArgumentException> { files.remove("../foreign/settings.xml") }
            assertFailsWith<IllegalArgumentException> { files.stage("store.lock").close() }
            assertFailsWith<IllegalArgumentException> { files.remove("STORE.LOCK") }
            assertFailsWith<IllegalArgumentException> { files.replace("linked.xml", "store.lock") }
            assertContentEquals(byteArrayOf(7), Files.readAllBytes(outside))
            assertTrue(files.hasWriteLock)
        }
    }

    @Test
    fun `process exit preserves the generation on each side of the atomic replacement`() {
        val previous = byteArrayOf(1, 0)
        val candidate = byteArrayOf(2, -1)
        for (point in StoreFilePoint.entries) {
            val root = directory.resolve("crash-${point.name}")
            val expected = if (point == StoreFilePoint.REPLACED) candidate else previous
            openStudioFiles(root).use { it.writeVerified("state.xml", previous, "before") }
            val runtime = Path.of(System.getProperty("java.home"), "bin", "java")
            val process =
                ProcessBuilder(
                    runtime.toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    StoreFileProbe::class.java.name,
                    root.toString(),
                    point.name,
                ).redirectErrorStream(true).start()
            Executors.newSingleThreadExecutor().use { executor ->
                try {
                    val ready = executor.submit<String?> { process.inputStream.bufferedReader().readLine() }
                    assertEquals("ready", ready.get(20, TimeUnit.SECONDS))
                    openStudioFiles(root).use { reader ->
                        assertFalse(reader.hasWriteLock)
                        assertContentEquals(expected, reader.read("state.xml"))
                    }
                } finally {
                    process.destroyForcibly()
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Store probe did not exit")
                }
            }
            openStudioFiles(root).use { reopened ->
                assertTrue(reopened.hasWriteLock)
                assertContentEquals(expected, reopened.read("state.xml"))
                when (point) {
                    StoreFilePoint.STAGED -> assertContentEquals(candidate, reopened.read("state.xml.tmp.next"))
                    StoreFilePoint.REPLACED -> assertNull(reopened.read("state.xml.tmp.next"))
                    StoreFilePoint.ARCHIVE_STAGED -> {
                        assertNull(reopened.read("archive.xml"))
                        reopened.createVerified("archive.xml", candidate)
                        assertContentEquals(candidate, reopened.read("archive.xml"))
                    }
                }
            }
        }
    }
}

private enum class StoreFilePoint { STAGED, REPLACED, ARCHIVE_STAGED }

private object StoreFileProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        openStudioFiles(Path.of(arguments[0])).use { owner ->
            check(owner.hasWriteLock)
            val bytes = byteArrayOf(2, -1)
            when (StoreFilePoint.valueOf(arguments[1])) {
                StoreFilePoint.REPLACED -> owner.writeVerified("state.xml", bytes, "next")
                StoreFilePoint.STAGED -> stage(owner, "state.xml.tmp.next", bytes)
                StoreFilePoint.ARCHIVE_STAGED -> stage(owner, "archive.xml.tmp.archive", byteArrayOf(2))
            }
            println("ready")
            System.`in`.read()
        }
    }

    private fun stage(
        files: StudioFiles,
        name: String,
        bytes: ByteArray,
    ) {
        files.stage(name).use { staged ->
            staged.write(bytes)
            staged.force()
        }
    }
}
