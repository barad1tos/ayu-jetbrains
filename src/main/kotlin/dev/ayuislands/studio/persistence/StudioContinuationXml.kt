package dev.ayuislands.studio.persistence

import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioAcceptedInput
import dev.ayuislands.studio.model.StudioEffectiveContinuation
import dev.ayuislands.studio.model.StudioList

internal object StudioContinuationXml {
    fun write(continuation: StudioEffectiveContinuation): StudioXmlNode =
        StudioXmlNode(
            "continuation",
            children =
                listOf(
                    StudioRotationXml.writeOwner(continuation.source),
                    StudioAppearanceXml.writeAppearance(continuation.requested),
                ) + listOfNotNull(continuation.accent?.let(::writeAccent)),
        )

    fun read(node: StudioXmlNode): StudioEffectiveContinuation {
        require(node.name == "continuation") { "Expected continuation element" }
        node.requireShape(childNames = setOf("owner", "appearance", "accent"))
        return StudioEffectiveContinuation(
            StudioRotationXml.readOwner(node.child("owner")),
            StudioAppearanceXml.readAppearance(node.child("appearance")),
            node.optionalChild("accent")?.let(::readAccent),
        )
    }

    private fun writeAccent(accent: StudioAccentContinuation): StudioXmlNode =
        StudioXmlNode(
            "accent",
            mapOf("source" to accent.resolved.source.name, "hex" to StudioXml.encodeText(accent.resolved.hex)) +
                (accent.variant?.let { mapOf("variant" to it.name) } ?: emptyMap()),
            accent.dependencies.map(::writeInput),
        )

    private fun readAccent(node: StudioXmlNode): StudioAccentContinuation {
        node.requireShape(setOf("source", "hex") + node.attributes.keys.filter { it == "variant" }, setOf("input"))
        return StudioAccentContinuation(
            node.attributes["variant"]?.let(AyuVariant::valueOf),
            ResolvedAccent(
                AccentResolver.Source.valueOf(node.attribute("source")),
                StudioXml.decodeText(node.attribute("hex")),
            ),
            StudioList(node.children.map(::readInput)),
        )
    }

    private fun writeInput(input: StudioAcceptedInput): StudioXmlNode =
        StudioXmlNode(
            "input",
            children =
                listOf(StudioDraftXml.writeAddress(input.address)) +
                    when (val accepted = input.value) {
                        FieldValue.Absent -> emptyList()
                        is FieldValue.Present -> listOf(StudioValueXml.writeValue(accepted.value))
                    },
        )

    private fun readInput(node: StudioXmlNode): StudioAcceptedInput {
        node.requireShape(childNames = setOf("address", "value"))
        return StudioAcceptedInput(
            StudioDraftXml.readAddress(node.child("address")),
            node.optionalChild("value")?.let { FieldValue.Present(StudioValueXml.readValue(it)) } ?: FieldValue.Absent,
        )
    }
}
