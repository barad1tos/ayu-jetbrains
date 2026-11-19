package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Captures the original options without invoking legacy settings services or rewriting source files. */
internal fun readLegacyFiles(optionsDirectory: Path): StudioList<LegacyArchive> =
    StudioList(
        legacyFileNames.map { (store, name) ->
            LegacyArchive(store, readLegacyFile(optionsDirectory.resolve(name)))
        },
    )

private fun readLegacyFile(file: Path): LegacyFile =
    try {
        LegacyFile.Present(StudioBytes(Files.readAllBytes(file)))
    } catch (failure: NoSuchFileException) {
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw failure
        LegacyFile.Absent
    }
