package dev.ayuislands.studio

import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.StudioAppearanceXml
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.StudioXmlNode
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioAppearanceXmlTest {
    @Test
    fun `factory and user profiles round trip without changing payloads or labels`() {
        val factory = factory()
        val extension = OpaqueRecord("future:schema", StudioBytes(byteArrayOf(0, -1)))
        val syntax = factory.appearance.syntax.copy(extensions = StudioList(listOf(extension)))
        val appearance = factory.appearance.copy(syntax = syntax)
        val custom = ProfileDefinition.User(ProfileId("user-1"), $$"  ā $LITERAL$  ", appearance, revision = 2)

        for (profile in listOf(factory, custom)) {
            val encoded = StudioXml.write(StudioAppearanceXml.writeProfile(profile))
            val decoded = StudioAppearanceXml.readProfile(StudioXml.parse(encoded))
            assertEquals(profile, decoded)
            assertContentEquals(encoded, StudioXml.write(StudioAppearanceXml.writeProfile(decoded)))
        }
    }

    @Test
    fun `accepted appearance keeps optional profile reference and target identity`() {
        val factory = factory()
        val reference = ProfileReference(factory.id, 3)
        for (source in listOf(null, reference)) {
            val accepted = AcceptedAppearance(factory.appearance, 7, source)
            val node = StudioAppearanceXml.writeAccepted(accepted)
            assertEquals(accepted, StudioAppearanceXml.readAccepted(StudioXml.parse(StudioXml.write(node))))
        }

        val project = StudioTarget.Project(StudioProjectKey($$"/work/$LITERAL$/ā"))
        for (target in listOf(StudioTarget.Global, project)) {
            val node = StudioAppearanceXml.writeTarget(target)
            assertEquals(target, StudioAppearanceXml.readTarget(StudioXml.parse(StudioXml.write(node))))
        }
    }

    @Test
    fun `profile codec rejects unknown kinds and ambiguous appearance domains`() {
        val node = StudioAppearanceXml.writeProfile(factory())
        val unknown = node.copy(attributes = node.attributes + ("kind" to "unknown"))
        assertFailsWith<IllegalArgumentException> { StudioAppearanceXml.readProfile(unknown) }

        val appearance = node.child("appearance")
        val duplicate = StudioXmlNode("appearance", children = appearance.children + appearance.child("accent"))
        assertFailsWith<IllegalArgumentException> { StudioAppearanceXml.readAppearance(duplicate) }
        assertFailsWith<IllegalArgumentException> {
            StudioAppearanceXml.readTarget(StudioXmlNode("target", mapOf("kind" to "unknown")))
        }
    }

    @Test
    fun `appearance codecs reject records with the wrong element identity`() {
        val factory = factory()
        val reference = ProfileReference(factory.id, 1)
        val accepted = AcceptedAppearance(factory.appearance, 2, reference)
        val appearance = StudioAppearanceXml.writeAppearance(factory.appearance).copy(name = "wrong")
        val profile = StudioAppearanceXml.writeProfile(factory).copy(name = "wrong")
        val current = StudioAppearanceXml.writeAccepted(accepted).copy(name = "wrong")
        val source = StudioAppearanceXml.writeReference(reference).copy(name = "wrong")
        val target = StudioAppearanceXml.writeTarget(StudioTarget.Global).copy(name = "wrong")

        assertFailsWith<IllegalArgumentException> { StudioAppearanceXml.readAppearance(appearance) }
        assertFailsWith<IllegalArgumentException> { StudioAppearanceXml.readProfile(profile) }
        assertFailsWith<IllegalArgumentException> { StudioAppearanceXml.readAccepted(current) }
        assertFailsWith<IllegalArgumentException> { StudioAppearanceXml.readReference(source) }
        assertFailsWith<IllegalArgumentException> { StudioAppearanceXml.readTarget(target) }
    }

    private fun factory(): ProfileDefinition.Factory {
        val captured =
            StudioLegacyFields.capture(
                freshSettingsState(),
                SyntaxIntensityBaseState(),
                AccentMappingsState(),
                origins = emptyMap(),
            )
        return StudioFactoryProfiles.create(captured).first()
    }
}
