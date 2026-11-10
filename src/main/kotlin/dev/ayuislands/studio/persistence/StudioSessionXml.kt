package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.SystemAccentPolicy

internal object StudioSessionXml {
    private const val CATALOG_INTENTS = "catalog-intents"
    private const val SHARED_INTENTS = "shared-intents"

    fun writeSession(session: StudioSessionRecord): StudioXmlNode =
        StudioXmlNode(
            "session",
            mapOf(
                "id" to StudioXml.encodeText(session.id),
                "generation" to session.generation.toString(),
                "base" to session.baseDocumentRevision.toString(),
                "availability" to session.availability.name,
                "selected" to StudioXml.encodeText(session.work.selected.value),
            ),
            listOf(
                StudioAppearanceXml.writeTarget(session.target),
                StudioXmlNode("drafts", children = session.work.drafts.map(StudioDraftXml::write)),
                StudioXmlNode(CATALOG_INTENTS, children = session.work.catalog.map(::writeCatalogIntent)),
                StudioXmlNode(SHARED_INTENTS, children = session.work.shared.map(::writeSharedIntent)),
            ),
        )

    fun readSession(node: StudioXmlNode): StudioSessionRecord {
        node.requireShape(
            setOf("id", "generation", "base", "availability", "selected"),
            setOf("target", "drafts", CATALOG_INTENTS, SHARED_INTENTS),
        )
        val drafts =
            node
                .child("drafts")
                .requireShape(childNames = setOf("draft"))
                .children
                .map(StudioDraftXml::read)
        val catalog =
            node
                .child(
                    CATALOG_INTENTS,
                ).requireShape(childNames = setOf("intent"))
                .children
                .map(::readCatalogIntent)
        val shared =
            node
                .child(SHARED_INTENTS)
                .requireShape(childNames = setOf("intent"))
                .children
                .map(::readSharedIntent)
        return StudioSessionRecord(
            StudioXml.decodeText(node.attribute("id")),
            node.attribute("generation").toLong(),
            StudioAppearanceXml.readTarget(node.child("target")),
            node.attribute("base").toLong(),
            SessionWork(
                StudioList(drafts),
                DraftId(StudioXml.decodeText(node.attribute("selected"))),
                StudioList(catalog),
                StudioList(shared),
            ),
            SessionAvailability.valueOf(node.attribute("availability")),
        )
    }

    private fun writeCatalogIntent(intent: CatalogIntent): StudioXmlNode =
        when (intent) {
            is CatalogIntent.Create ->
                StudioXmlNode(
                    "intent",
                    mapOf("kind" to "create", "draft" to StudioXml.encodeText(intent.draft.value)),
                    listOf(StudioAppearanceXml.writeProfile(intent.definition)),
                )
            is CatalogIntent.Update ->
                StudioXmlNode(
                    "intent",
                    mapOf("kind" to "update", "draft" to StudioXml.encodeText(intent.draft.value)),
                    listOf(
                        StudioXmlNode("before", children = listOf(StudioAppearanceXml.writeProfile(intent.before))),
                        StudioXmlNode("after", children = listOf(StudioAppearanceXml.writeProfile(intent.after))),
                    ),
                )
            is CatalogIntent.Rename ->
                StudioXmlNode(
                    "intent",
                    mapOf("kind" to "rename", "name" to StudioXml.encodeText(intent.name)),
                    listOf(StudioAppearanceXml.writeProfile(intent.before)),
                )
            is CatalogIntent.Delete ->
                StudioXmlNode(
                    "intent",
                    mapOf("kind" to "delete"),
                    listOf(StudioAppearanceXml.writeProfile(intent.before)),
                )
        }

    private fun readCatalogIntent(node: StudioXmlNode): CatalogIntent =
        when (node.attribute("kind")) {
            "create" -> {
                node.requireShape(setOf("kind", "draft"), setOf("profile"))
                CatalogIntent.Create(
                    readUser(node.child("profile")),
                    DraftId(StudioXml.decodeText(node.attribute("draft"))),
                )
            }
            "rename" -> {
                node.requireShape(setOf("kind", "name"), setOf("profile"))
                CatalogIntent.Rename(readUser(node.child("profile")), StudioXml.decodeText(node.attribute("name")))
            }
            "delete" -> {
                node.requireShape(setOf("kind"), setOf("profile"))
                CatalogIntent.Delete(readUser(node.child("profile")))
            }
            "update" -> {
                node.requireShape(setOf("kind", "draft"), setOf("before", "after"))
                CatalogIntent.Update(
                    readUser(node.child("before").requireShape(childNames = setOf("profile")).child("profile")),
                    readUser(node.child("after").requireShape(childNames = setOf("profile")).child("profile")),
                    DraftId(StudioXml.decodeText(node.attribute("draft"))),
                )
            }
            else -> throw IllegalArgumentException("Unknown catalog intent")
        }

    private fun readUser(node: StudioXmlNode): ProfileDefinition.User =
        requireNotNull(StudioAppearanceXml.readProfile(node) as? ProfileDefinition.User) { "Expected user definition" }

    private fun writeSharedIntent(intent: SharedIntent): StudioXmlNode =
        when (intent) {
            is SharedIntent.Field ->
                StudioXmlNode(
                    "intent",
                    mapOf("kind" to "field"),
                    listOf(
                        StudioXmlNode("before", children = listOf(StudioValueXml.writeField(intent.before))),
                        StudioXmlNode("after", children = listOf(StudioValueXml.writeField(intent.after))),
                    ),
                )
            is SharedIntent.SystemPolicy ->
                StudioXmlNode(
                    "intent",
                    mapOf(
                        "kind" to "system-policy",
                        "before" to intent.before.name,
                        "after" to intent.after.name,
                    ),
                )
        }

    private fun readSharedIntent(node: StudioXmlNode): SharedIntent =
        when (node.attribute("kind")) {
            "field" -> {
                node.requireShape(setOf("kind"), setOf("before", "after"))
                SharedIntent.Field(
                    StudioValueXml.readField(
                        node.child("before").requireShape(childNames = setOf("field")).child("field"),
                    ),
                    StudioValueXml.readField(
                        node.child("after").requireShape(childNames = setOf("field")).child("field"),
                    ),
                )
            }
            "system-policy" -> {
                node.requireShape(setOf("kind", "before", "after"))
                SharedIntent.SystemPolicy(
                    SystemAccentPolicy.valueOf(node.attribute("before")),
                    SystemAccentPolicy.valueOf(node.attribute("after")),
                )
            }
            else -> throw IllegalArgumentException("Unknown shared intent")
        }
}
