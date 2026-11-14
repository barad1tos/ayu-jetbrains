package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.migration.hasCompleteArchives
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import java.nio.charset.CharacterCodingException
import javax.xml.stream.XMLStreamException

/** Original 2.x filenames used to read migration inputs and verify their sealed archives. */
internal val legacyFileNames: Map<LegacyStore, String> =
    mapOf(
        LegacyStore.SETTINGS to "ayuIslands.xml",
        LegacyStore.SYNTAX to "ayu-islands-syntax-intensity.xml",
        LegacyStore.MAPPINGS to "ayuIslandsAccentMappings.xml",
    )

/** Only a complete manifest can authorize reuse of a migration identity. */
internal class StudioMigrationArchive(
    private val files: StudioFiles,
) {
    fun findSource(archives: StudioList<LegacyArchive>): MigrationSource? {
        var damaged: StoreDataException? = null
        for (id in files.directories(ROOT).sorted()) {
            if (!transactionFileId.matches(id)) continue
            val source =
                try {
                    readSource(id)
                } catch (failure: StoreDataException) {
                    if (damaged == null) damaged = failure
                    continue
                }
            if (source?.archives == archives) return source
        }
        // A damaged archive is evidence of prior migration work, not an empty store.
        damaged?.let { throw it }
        return null
    }

    fun preserve(source: MigrationSource) {
        val prefix = prefix(source.id)
        if (!source.hasCompleteArchives()) throw StoreDataException(StoreIssue.INVALID_CANDIDATE)
        val manifest = StudioXml.write(writeManifest(source))
        files.whileWritable {
            val existing = files.read("$prefix/$MANIFEST")
            if (existing == null) {
                preserveArchives(prefix, source.archives)
                files.createVerified("$prefix/$MANIFEST", manifest)
            } else {
                if (!existing.contentEquals(manifest)) throw StoreDataException(StoreIssue.CONFLICT)
                verify(source)
            }
        }
    }

    fun verify(source: MigrationSource) {
        val verified = readSource(source.id) ?: throw StoreDataException(StoreIssue.INVALID_CANDIDATE)
        if (verified != source) throw StoreDataException(StoreIssue.CONFLICT)
    }

    private fun preserveArchives(
        prefix: String,
        archives: StudioList<LegacyArchive>,
    ) {
        for ((store, original) in archives) {
            val bytes = files.read(archivePath(prefix, store))
            val saved = (original as? LegacyFile.Present)?.bytes
            if (bytes != null && saved?.toByteArray()?.contentEquals(bytes) != true) {
                throw StoreDataException(StoreIssue.CONFLICT)
            }
        }
        for ((store, original) in archives) {
            if (original is LegacyFile.Present) {
                files.createVerified(archivePath(prefix, store), original.bytes.toByteArray())
            }
        }
    }

    private fun readSource(id: String): MigrationSource? {
        val prefix = prefix(id)
        val bytes = files.read("$prefix/$MANIFEST") ?: return null
        val entries = readManifest(id, bytes)
        return MigrationSource(id, StudioList(entries.map { readArchive(prefix, it) }))
    }

    private fun readArchive(
        prefix: String,
        entry: ManifestEntry,
    ): LegacyArchive {
        val bytes = files.read(archivePath(prefix, entry.store))
        if (bytes?.let(StudioXml::hashBytes) != entry.digest) throw StoreDataException(StoreIssue.CONFLICT)
        val original = if (bytes == null) LegacyFile.Absent else LegacyFile.Present(StudioBytes(bytes))
        return LegacyArchive(entry.store, original)
    }

    private fun writeManifest(source: MigrationSource): StudioXmlNode =
        StudioXmlNode(
            ROOT,
            mapOf("migration" to StudioXml.encodeText(source.id)),
            source.archives.map { archive ->
                val digest = (archive.file as? LegacyFile.Present)?.bytes?.toByteArray()?.let(StudioXml::hashBytes)
                val attributes =
                    mapOf("store" to archive.store.name, "filename" to legacyFileNames.getValue(archive.store))
                val evidence =
                    if (digest == null) {
                        mapOf("presence" to "absent")
                    } else {
                        mapOf("presence" to "value", "sha256" to digest)
                    }
                StudioXmlNode("archive", attributes + evidence)
            },
        )

    private fun readManifest(
        id: String,
        bytes: ByteArray,
    ): List<ManifestEntry> =
        try {
            val manifest = StudioXml.parse(bytes).requireShape(setOf("migration"), setOf("archive"))
            require(manifest.name == ROOT && StudioXml.decodeText(manifest.attribute("migration")) == id)
            val entries = manifest.children.map(::readEntry)
            require(entries.size == LegacyStore.entries.size)
            require(entries.map { it.store }.toSet() == LegacyStore.entries.toSet())
            entries
        } catch (failure: IllegalArgumentException) {
            throw StoreDataException(StoreIssue.INVALID_DATA, failure)
        } catch (failure: XMLStreamException) {
            throw StoreDataException(StoreIssue.INVALID_DATA, failure)
        } catch (failure: CharacterCodingException) {
            throw StoreDataException(StoreIssue.INVALID_DATA, failure)
        }

    private fun readEntry(node: StudioXmlNode): ManifestEntry {
        val store = LegacyStore.valueOf(node.attribute("store"))
        require(node.attribute("filename") == legacyFileNames.getValue(store))
        val attributes = setOf("store", "filename", "presence")
        val digest =
            when (node.attribute("presence")) {
                "absent" -> {
                    node.requireShape(attributes)
                    null
                }
                "value" -> node.requireShape(attributes + "sha256").attribute("sha256")
                else -> throw IllegalArgumentException("Unknown archive presence")
            }
        return ManifestEntry(store, digest)
    }

    private fun prefix(id: String): String = "$ROOT/${requireStoreFileId(id)}"

    private fun archivePath(
        prefix: String,
        store: LegacyStore,
    ): String = "$prefix/${store.name.lowercase()}.xml"

    private data class ManifestEntry(
        val store: LegacyStore,
        val digest: String?,
    )

    private companion object {
        const val ROOT = "source-backup"
        const val MANIFEST = "manifest.xml"
    }
}
