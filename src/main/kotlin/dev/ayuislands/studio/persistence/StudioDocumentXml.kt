package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.AuxiliaryPreferences
import dev.ayuislands.studio.model.DocumentHeader
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioCurrents
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.SystemAccentPolicy

internal object StudioDocumentXml {
    private const val LEGACY_MARKERS = "legacy-markers"
    private const val SYSTEM_ACCENT_POLICY = "system-accent-policy"

    fun writeDocument(document: StudioDocument): StudioXmlNode =
        StudioXmlNode(
            "document",
            headerAttributes(document.header),
            listOf(
                writeMigration(document.header.migration),
                StudioXmlNode("catalog", children = document.catalog.map(StudioAppearanceXml::writeProfile)),
                writeCurrents(document.currents),
                writeShared(document.shared),
                StudioXmlNode(
                    "auxiliary",
                    children =
                        listOf(
                            StudioValueXml.writeFields("ui", document.auxiliary.ui),
                            StudioValueXml.writeFields("resources", document.auxiliary.resources),
                            StudioValueXml.writeFields(LEGACY_MARKERS, document.auxiliary.legacyMarkers),
                        ),
                ),
                StudioXmlNode(
                    "runtime",
                    children =
                        listOf(StudioValueXml.writeFields("legacy", document.runtime.legacy)) +
                            listOfNotNull(
                                document.runtime.rotation?.let(StudioRotationXml::write),
                                document.runtime.effectiveContinuation?.let(StudioContinuationXml::write),
                            ),
                ),
                StudioXmlNode("sessions", children = document.sessions.map(StudioSessionXml::writeSession)),
                StudioValueXml.writeExtensions(document.extensions),
            ),
        )

    fun readDocument(node: StudioXmlNode): StudioDocument {
        require(node.name == "document")
        node.requireShape(
            setOf("schema", "revision", "transaction"),
            setOf("migration", "catalog", "currents", "shared", "auxiliary", "runtime", "sessions", "extensions"),
        )
        val auxiliary = node.child("auxiliary").requireShape(childNames = setOf("ui", "resources", LEGACY_MARKERS))
        val runtime = node.child("runtime").requireShape(childNames = setOf("legacy", "rotation", "continuation"))
        return StudioDocument(
            DocumentHeader(
                node.attribute("schema").toInt(),
                node.attribute("revision").toLong(),
                StudioXml.decodeText(node.attribute("transaction")),
                readMigration(node.child("migration")),
            ),
            StudioList(
                node
                    .child("catalog")
                    .requireShape(childNames = setOf("profile"))
                    .children
                    .map(StudioAppearanceXml::readProfile),
            ),
            readCurrents(node.child("currents")),
            readShared(node.child("shared")),
            AuxiliaryPreferences(
                StudioValueXml.readFields(auxiliary.child("ui")),
                StudioValueXml.readFields(auxiliary.child("resources")),
                StudioValueXml.readFields(auxiliary.child(LEGACY_MARKERS)),
            ),
            StudioRuntimeState(
                StudioValueXml.readFields(runtime.child("legacy")),
                runtime.optionalChild("rotation")?.let(StudioRotationXml::read),
                runtime.optionalChild("continuation")?.let(StudioContinuationXml::read),
            ),
            StudioList(
                node
                    .child("sessions")
                    .requireShape(childNames = setOf("session"))
                    .children
                    .map(StudioSessionXml::readSession),
            ),
            StudioValueXml.readExtensions(node.child("extensions")),
        )
    }

    fun headerAttributes(header: DocumentHeader): Map<String, String> =
        mapOf(
            "schema" to header.schema.toString(),
            "revision" to header.revision.toString(),
            "transaction" to StudioXml.encodeText(header.transactionId),
        )

    private fun writeMigration(source: MigrationSource): StudioXmlNode =
        StudioXmlNode(
            "migration",
            mapOf("id" to StudioXml.encodeText(source.id)),
            source.archives.map { archive ->
                val file = archive.file
                StudioXmlNode(
                    "archive",
                    mapOf(
                        "store" to archive.store.name,
                        "presence" to if (file is LegacyFile.Present) "value" else "absent",
                    ),
                    text = if (file is LegacyFile.Present) StudioXml.encodeBytes(file.bytes.toByteArray()) else "",
                )
            },
        )

    private fun readMigration(node: StudioXmlNode): MigrationSource {
        node.requireShape(setOf("id"), setOf("archive"))
        val archives =
            node.children.map { child ->
                child.requireShape(setOf("store", "presence"), allowsText = true)
                val file =
                    when (child.attribute("presence")) {
                        "absent" -> {
                            require(child.text.isEmpty())
                            LegacyFile.Absent
                        }

                        "value" -> {
                            LegacyFile.Present(StudioBytes(StudioXml.decodeBytes(child.text)))
                        }

                        else -> {
                            throw IllegalArgumentException("Invalid archive presence")
                        }
                    }
                LegacyArchive(LegacyStore.valueOf(child.attribute("store")), file)
            }
        return MigrationSource(StudioXml.decodeText(node.attribute("id")), StudioList(archives))
    }

    private fun writeCurrents(currents: StudioCurrents): StudioXmlNode =
        StudioXmlNode(
            "currents",
            children =
                listOf(StudioAppearanceXml.writeAccepted(currents.global)) +
                    currents.projects.map { project ->
                        StudioXmlNode(
                            "project",
                            mapOf(
                                "key" to StudioXml.encodeText(project.key.value),
                                "name" to StudioXml.encodeText(project.displayName),
                            ),
                            listOf(StudioAppearanceXml.writeAccepted(project.current)),
                        )
                    },
        )

    private fun readCurrents(node: StudioXmlNode): StudioCurrents {
        node.requireShape(childNames = setOf("accepted", "project"))
        val projects =
            node.children.filter { it.name == "project" }.map { child ->
                child.requireShape(setOf("key", "name"), setOf("accepted"))
                StudioProjectRecord(
                    StudioProjectKey(StudioXml.decodeText(child.attribute("key"))),
                    StudioXml.decodeText(child.attribute("name")),
                    StudioAppearanceXml.readAccepted(child.child("accepted")),
                )
            }
        return StudioCurrents(StudioAppearanceXml.readAccepted(node.child("accepted")), StudioList(projects))
    }

    private fun writeShared(shared: SharedPreferences): StudioXmlNode =
        StudioXmlNode(
            "shared",
            mapOf(SYSTEM_ACCENT_POLICY to shared.systemAccentPolicy.name),
            listOf(
                StudioValueXml.writeFields("plugins", shared.plugins),
                StudioValueXml.writeFields("workspace", shared.workspace),
                StudioValueXml.writeFields("system", shared.system),
                StudioValueXml.writeFields("mappings", shared.mappings),
            ),
        )

    private fun readShared(node: StudioXmlNode): SharedPreferences {
        node.requireShape(setOf(SYSTEM_ACCENT_POLICY), setOf("plugins", "workspace", "system", "mappings"))
        return SharedPreferences(
            StudioValueXml.readFields(node.child("plugins")),
            StudioValueXml.readFields(node.child("workspace")),
            StudioValueXml.readFields(node.child("system")),
            StudioValueXml.readFields(node.child("mappings")),
            SystemAccentPolicy.valueOf(node.attribute(SYSTEM_ACCENT_POLICY)),
        )
    }
}
