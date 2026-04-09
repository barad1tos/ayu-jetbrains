package dev.ayuislands.font

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/** Thrown when a font archive cannot be extracted safely. */
class FontArchiveException(
    message: String,
) : RuntimeException(message)

/**
 * Pure zip extractor that only writes entries whose name matches one of the
 * provided [filesToKeep] regexes. Guards against path-traversal (`..`) and
 * canonical-path escape attempts.
 */
object FontArchiveExtractor {
    private const val BUFFER_SIZE = 8 * 1024

    fun extract(
        zipFile: File,
        destDir: File,
        filesToKeep: List<Regex>,
    ): List<File> {
        if (!destDir.exists()) destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile
        val extracted = mutableListOf<File>()

        ZipInputStream(FileInputStream(zipFile).buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                processEntry(zip, entry, canonicalDest, filesToKeep, extracted)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (extracted.isEmpty()) {
            throw FontArchiveException("No matching files in archive")
        }
        return extracted
    }

    private fun processEntry(
        zip: ZipInputStream,
        entry: java.util.zip.ZipEntry,
        canonicalDest: File,
        filesToKeep: List<Regex>,
        accumulator: MutableList<File>,
    ) {
        if (entry.isDirectory) return
        val name = entry.name
        val target = validateTarget(name, canonicalDest)
        val simpleName = name.substringAfterLast('/')
        val matches = filesToKeep.any { it.containsMatchIn(simpleName) || it.containsMatchIn(name) }
        if (!matches) return
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read = zip.read(buffer)
            while (read >= 0) {
                out.write(buffer, 0, read)
                read = zip.read(buffer)
            }
        }
        accumulator.add(target)
    }

    private fun validateTarget(
        name: String,
        canonicalDest: File,
    ): File {
        if (name.contains("..")) {
            throw FontArchiveException("Zip entry attempts path traversal: $name")
        }
        val target = File(canonicalDest, name).canonicalFile
        val escaped =
            !target.path.startsWith(canonicalDest.path + File.separator) &&
                target.path != canonicalDest.path
        if (escaped) {
            throw FontArchiveException("Zip entry escapes destination: $name")
        }
        return target
    }
}
