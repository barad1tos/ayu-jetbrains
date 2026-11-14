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

    /** Keep one complete write and its readback exclusive of other writes and close. */
    fun whileWritable(operation: () -> Unit)

    fun read(name: String): ByteArray?

    fun directories(name: String): List<String>

    fun stage(name: String): StagedFile

    /** Remove abandoned staging files for one destination while the write owner is held. */
    fun removeStages(destination: String)

    fun publishNew(
        source: String,
        destination: String,
    )

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
            Files.newInputStream(resolve(name), LinkOption.NOFOLLOW_LINKS).use { it.readBytes() }
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

    @Synchronized
    override fun removeStages(destination: String) {
        requireWriteLock()
        val target = resolve(destination)
        val prefix = stagePrefix(target.fileName.toString())
        try {
            Files.newDirectoryStream(target.parent).use { entries ->
                for (entry in entries) {
                    val name = entry.fileName.toString()
                    if (name.startsWith(prefix) &&
                        transactionFileId.matches(name.removePrefix(prefix)) &&
                        Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        Files.deleteIfExists(entry)
                    }
                }
            }
        } catch (_: NoSuchFileException) {
            // The destination's directory is created when the first write is staged.
        }
    }

    @Synchronized
    override fun stage(name: String): StagedFile {
        requireWriteLock()
        val path = resolve(name)
        Files.createDirectories(path.parent)
        // A crash after archive publication can leave the old staging name linked to the archive.
        Files.deleteIfExists(path)
        return NioStagedFile(
            FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
            ::whileWritable,
        )
    }

    @Synchronized
    override fun publishNew(
        source: String,
        destination: String,
    ) {
        requireWriteLock()
        try {
            Files.createLink(resolve(destination), resolve(source))
        } catch (failure: UnsupportedOperationException) {
            throw IOException("Store provider cannot publish an immutable archive", failure)
        }
    }

    @Synchronized
    override fun replace(
        source: String,
        destination: String,
    ) {
        requireWriteLock()
        val target = resolve(destination)
        Files.move(
            resolve(source),
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    @Synchronized
    override fun remove(name: String) {
        requireWriteLock()
        Files.deleteIfExists(resolve(name))
    }

    @Synchronized
    override fun close() {
        lock?.let { StoreLockChannels.release(lockPath, it) }
    }

    private fun resolve(name: String): Path {
        val relative = Path.of(name)
        require(!relative.isAbsolute && relative.none { it.toString() == ".." }) { "Invalid store-relative path" }
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root) && resolved != root) { "Invalid store-relative path" }
        require(
            !root
                .relativize(resolved)
                .getName(0)
                .toString()
                .equals(lockPath.fileName.toString(), ignoreCase = true),
        ) {
            "Cannot access the ownership marker as store data"
        }
        var segment = root
        for (part in root.relativize(resolved)) {
            segment = segment.resolve(part)
            if (Files.isSymbolicLink(segment)) throw IOException("Symbolic link in store data path")
        }
        return resolved
    }

    private fun requireWriteLock() {
        check(hasWriteLock) { "Store write lock is not held" }
    }

    @Synchronized
    override fun whileWritable(operation: () -> Unit) {
        requireWriteLock()
        operation()
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
    private val whileWritable: (() -> Unit) -> Unit,
) : StagedFile,
    AutoCloseable by channel {
    override fun write(bytes: ByteArray) =
        whileWritable {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

    override fun force() = whileWritable { channel.force(true) }
}
