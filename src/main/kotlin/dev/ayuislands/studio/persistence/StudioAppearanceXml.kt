package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget

internal object StudioAppearanceXml {
    fun writeAppearance(appearance: AppearanceSnapshot): StudioXmlNode =
        StudioXmlNode(
            "appearance",
            children =
                listOf(
                    StudioValueXml.writeFields("accent", appearance.accent),
                    StudioValueXml.writeFields("font", appearance.font),
                    StudioValueXml.writeFields("glow", appearance.glow),
                    StudioValueXml.writeFields("syntax", appearance.syntax),
                    StudioValueXml.writeFields("vcs", appearance.vcs),
                ),
        )

    fun readAppearance(node: StudioXmlNode): AppearanceSnapshot {
        require(node.name == "appearance") { "Expected appearance element" }
        node.requireShape(childNames = setOf("accent", "font", "glow", "syntax", "vcs"))
        return AppearanceSnapshot(
            StudioValueXml.readFields(node.child("accent")),
            StudioValueXml.readFields(node.child("font")),
            StudioValueXml.readFields(node.child("glow")),
            StudioValueXml.readFields(node.child("syntax")),
            StudioValueXml.readFields(node.child("vcs")),
        )
    }

    fun writeProfile(profile: ProfileDefinition): StudioXmlNode {
        val variant =
            when (profile) {
                is ProfileDefinition.Factory ->
                    mapOf(
                        "kind" to "factory",
                        "version" to profile.recipeVersion.toString(),
                    )
                is ProfileDefinition.User -> mapOf("kind" to "user", "version" to profile.revision.toString())
            }
        return StudioXmlNode(
            "profile",
            variant +
                mapOf("id" to StudioXml.encodeText(profile.id.value), "name" to StudioXml.encodeText(profile.name)),
            listOf(writeAppearance(profile.appearance)),
        )
    }

    fun readProfile(node: StudioXmlNode): ProfileDefinition {
        require(node.name == "profile") { "Expected profile element" }
        node.requireShape(setOf("kind", "version", "id", "name"), setOf("appearance"))
        val id = ProfileId(StudioXml.decodeText(node.attribute("id")))
        val name = StudioXml.decodeText(node.attribute("name"))
        val appearance = readAppearance(node.child("appearance"))
        return when (node.attribute("kind")) {
            "factory" -> ProfileDefinition.Factory(id, name, appearance, node.attribute("version").toInt())
            "user" -> ProfileDefinition.User(id, name, appearance, node.attribute("version").toLong())
            else -> throw IllegalArgumentException("Unknown profile kind")
        }
    }

    fun writeAccepted(accepted: AcceptedAppearance): StudioXmlNode =
        StudioXmlNode(
            "accepted",
            mapOf("revision" to accepted.revision.toString()),
            listOf(writeAppearance(accepted.appearance)) + listOfNotNull(accepted.source?.let(::writeReference)),
        )

    fun readAccepted(node: StudioXmlNode): AcceptedAppearance {
        require(node.name == "accepted") { "Expected accepted element" }
        node.requireShape(setOf("revision"), setOf("appearance", "reference"))
        return AcceptedAppearance(
            readAppearance(node.child("appearance")),
            node.attribute("revision").toLong(),
            node.optionalChild("reference")?.let(::readReference),
        )
    }

    fun writeReference(reference: ProfileReference): StudioXmlNode =
        StudioXmlNode(
            "reference",
            mapOf("id" to StudioXml.encodeText(reference.id.value), "revision" to reference.revision.toString()),
        )

    fun readReference(node: StudioXmlNode): ProfileReference {
        require(node.name == "reference") { "Expected reference element" }
        node.requireShape(setOf("id", "revision"))
        return ProfileReference(
            ProfileId(StudioXml.decodeText(node.attribute("id"))),
            node.attribute("revision").toLong(),
        )
    }

    fun writeTarget(target: StudioTarget): StudioXmlNode =
        when (target) {
            StudioTarget.Global -> StudioXmlNode("target", mapOf("kind" to "global"))
            is StudioTarget.Project ->
                StudioXmlNode("target", mapOf("kind" to "project", "key" to StudioXml.encodeText(target.key.value)))
        }

    fun readTarget(node: StudioXmlNode): StudioTarget {
        require(node.name == "target") { "Expected target element" }
        return when (node.attribute("kind")) {
            "global" -> {
                node.requireShape(setOf("kind"))
                StudioTarget.Global
            }
            "project" -> {
                node.requireShape(setOf("kind", "key"))
                StudioTarget.Project(StudioProjectKey(StudioXml.decodeText(node.attribute("key"))))
            }
            else -> throw IllegalArgumentException("Unknown target kind")
        }
    }
}
