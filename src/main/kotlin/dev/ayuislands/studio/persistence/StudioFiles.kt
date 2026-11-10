package dev.ayuislands.studio.persistence

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Narrow store IO boundary; staged handles expose the actual write/force/close fault boundaries. */
internal interface StudioFiles : AutoCloseable {
    val hasWriteLock: Boolean

    fun read(name: String): ByteArray?

    fun directories(name: String): List<String>

    fun stage(name: String): StagedFile

    fun create(name: String): StagedFile

    fun replace(
        source: String,
        destination: String,
    )

    fun remove(name: String)
}

internal interface StagedFile : AutoCloseable {
    fun write(bytes: ByteArray)

    fun force()
}

internal fun openStudioFiles(directory: Path): StudioFiles = NioStudioFiles(directory)

private class NioStudioFiles(
    directory: Path,
) : StudioFiles {
    private val root = Files.createDirectories(directory).toRealPath()
    private val lockPath = root.resolve("store.lock")
    private val lock = StoreLockChannels.acquire(lockPath)

    override val hasWriteLock: Boolean get() = lock?.isValid == true

    override fun read(name: String): ByteArray? =
        try {
            Files.readAllBytes(resolve(name))
        } catch (_: NoSuchFileException) {
            null
        }

    override fun directories(name: String): List<String> =
        try {
            Files.newDirectoryStream(resolve(name)).use { entries ->
                entries
                    .filter {
                        Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory
                    }.map { it.fileName.toString() }
            }
        } catch (_: NoSuchFileException) {
            emptyList()
        }

    override fun stage(name: String): StagedFile {
        check(hasWriteLock) { "Store write lock is not held" }
        val path = resolve(name)
        Files.createDirectories(path.parent)
        return NioStagedFile(
            FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
        )
    }

    override fun create(name: String): StagedFile {
        check(hasWriteLock) { "Store write lock is not held" }
        val path = resolve(name)
        Files.createDirectories(path.parent)
        return NioStagedFile(
            FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
        )
    }

    override fun replace(
        source: String,
        destination: String,
    ) {
        check(hasWriteLock) { "Store write lock is not held" }
        Files.move(
            resolve(source),
            resolve(destination),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun remove(name: String) {
        check(hasWriteLock) { "Store write lock is not held" }
        Files.deleteIfExists(resolve(name))
    }

    override fun close() {
        lock?.let { StoreLockChannels.release(lockPath, it) }
    }

    private fun resolve(name: String): Path {
        val resolved = root.resolve(name).normalize()
        require(resolved.startsWith(root) && resolved != root) { "Invalid store-relative path" }
        return resolved
    }
}

/** Closing any second channel can release another owner's JVM-wide OS lock on macOS. */
private object StoreLockChannels {
    private class Entry(
        val channel: FileChannel,
        var lock: FileLock? = null,
    )

    private val entries = mutableMapOf<Path, Entry>()

    @Synchronized
    fun acquire(path: Path): FileLock? {
        val entry =
            entries.getOrPut(path) {
                Entry(
                    FileChannel.open(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                )
            }
        if (entry.lock != null) return null
        return try {
            val lock = entry.channel.tryLock()
            entry.lock = lock
            if (lock == null) close(path, entry)
            lock
        } catch (_: OverlappingFileLockException) {
            // A foreign in-process owner may exist. Retain this single channel until a later acquisition:
            // closing it would release that owner's OS lock even though we never acquired one ourselves.
            null
        } catch (failure: IOException) {
            close(path, entry)
            throw failure
        }
    }

    @Synchronized
    fun release(
        path: Path,
        lock: FileLock,
    ) {
        val entry = entries[path] ?: return
        if (entry.lock !== lock) return
        try {
            lock.release()
        } finally {
            close(path, entry)
        }
    }

    private fun close(
        path: Path,
        entry: Entry,
    ) {
        try {
            entry.channel.close()
        } finally {
            entries.remove(path)
        }
    }
}

private class NioStagedFile(
    private val channel: FileChannel,
) : StagedFile,
    AutoCloseable by channel {
    override fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun force() = channel.force(true)
}
