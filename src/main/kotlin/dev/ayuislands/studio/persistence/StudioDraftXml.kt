package dev.ayuislands.studio.persistence

import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.StudioList

internal object StudioDraftXml {
    private const val SOURCE_NAME = "source-name"
    private const val ROTATION_ORIGIN = "rotation-origin"

    fun write(draft: DraftRecord): StudioXmlNode =
        StudioXmlNode(
            "draft",
            mapOf("id" to StudioXml.encodeText(draft.id.value)) +
                (draft.sourceName?.let { mapOf(SOURCE_NAME to StudioXml.encodeText(it)) } ?: emptyMap()),
            listOf(
                StudioAppearanceXml.writeAccepted(draft.base),
                StudioAppearanceXml.writeAppearance(draft.appearance),
                writeTracking(draft.tracking),
            ) + listOfNotNull(draft.source?.let(StudioAppearanceXml::writeReference)),
        )

    fun read(node: StudioXmlNode): DraftRecord {
        require(node.name == "draft") { "Expected draft element" }
        node.requireShape(
            setOf("id") + node.attributes.keys.filter { it == SOURCE_NAME },
            setOf("accepted", "appearance", "reference", "tracking"),
        )
        return DraftRecord(
            DraftId(StudioXml.decodeText(node.attribute("id"))),
            node.optionalChild("reference")?.let(StudioAppearanceXml::readReference),
            StudioAppearanceXml.readAccepted(node.child("accepted")),
            StudioAppearanceXml.readAppearance(node.child("appearance")),
            node.attributes[SOURCE_NAME]?.let(StudioXml::decodeText),
            readTracking(node.child("tracking")),
        )
    }

    private fun writeTracking(tracking: DraftTracking): StudioXmlNode =
        StudioXmlNode(
            "tracking",
            mapOf("intent" to tracking.intent.name) +
                (tracking.rotationOrigin?.let { mapOf(ROTATION_ORIGIN to it.name) } ?: emptyMap()),
            listOf(
                StudioAppearanceXml.writeAppearance(tracking.initial),
                StudioXmlNode("touched", children = tracking.touched.map(::writeAddress)),
                StudioXmlNode("reapply", children = tracking.reapply.map(::writeAddress)),
            ),
        )

    private fun readTracking(node: StudioXmlNode): DraftTracking {
        node.requireShape(
            setOf("intent") + node.attributes.keys.filter { it == ROTATION_ORIGIN },
            setOf("appearance", "touched", "reapply"),
        )
        return DraftTracking(
            StudioAppearanceXml.readAppearance(node.child("appearance")),
            StudioList(
                node
                    .child("touched")
                    .requireShape(childNames = setOf("address"))
                    .children
                    .map(::readAddress),
            ),
            StudioList(
                node
                    .child("reapply")
                    .requireShape(childNames = setOf("address"))
                    .children
                    .map(::readAddress),
            ),
            DraftIntent.valueOf(node.attribute("intent")),
            node.attributes[ROTATION_ORIGIN]?.let(RotationAcceptanceOrigin::valueOf),
        )
    }

    fun writeAddress(address: FieldAddress): StudioXmlNode {
        val attributes =
            mapOf(
                "source" to address.field.source.name,
                "field" to StudioXml.encodeText(address.field.name),
            )
        val part =
            when (val value = address.part) {
                FieldPart.Value -> mapOf("part" to "value")
                FieldPart.MapOrder -> mapOf("part" to "order")
                is FieldPart.MapEntry -> mapOf("part" to "entry", "key" to StudioXml.encodeText(value.key))
            }
        return StudioXmlNode("address", attributes + part)
    }

    fun readAddress(node: StudioXmlNode): FieldAddress {
        require(node.name == "address") { "Expected address element" }
        val part =
            when (node.attribute("part")) {
                "value" -> FieldPart.Value
                "order" -> FieldPart.MapOrder
                "entry" -> FieldPart.MapEntry(StudioXml.decodeText(node.attribute("key")))
                else -> throw IllegalArgumentException("Unknown field address")
            }
        node.requireShape(
            setOf("source", "field", "part") + if (part is FieldPart.MapEntry) setOf("key") else emptySet(),
        )
        return FieldAddress(
            LegacyFieldKey(
                LegacyStore.valueOf(node.attribute("source")),
                StudioXml.decodeText(node.attribute("field")),
            ),
            part,
        )
    }
}
