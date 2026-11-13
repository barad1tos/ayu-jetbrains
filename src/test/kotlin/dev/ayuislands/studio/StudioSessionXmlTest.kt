package dev.ayuislands.studio

import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.persistence.StudioDraftXml
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.studio.persistence.StudioSessionXml
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.StudioXmlNode
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioSessionXmlTest {
    @Test
    fun `session round trip keeps draft order selection tracking and pending intents`() {
        val session = session()
        val xml = StudioXml.write(StudioSessionXml.writeSession(session))

        assertEquals(session, StudioSessionXml.readSession(StudioXml.parse(xml)))
    }

    @Test
    fun `session refuses foreign tags and unknown intent kinds`() {
        val node = StudioSessionXml.writeSession(session())
        assertFailsWith<IllegalArgumentException> { StudioSessionXml.readSession(node.copy(name = "wrong")) }

        for (container in listOf("catalog-intents", "shared-intents")) {
            val unknown = StudioXmlNode("intent", mapOf("kind" to "future"))
            val malformed = StudioXmlNode(container, children = listOf(unknown))
            val children = node.children.map { if (it.name == container) malformed else it }
            assertFailsWith<IllegalArgumentException> {
                StudioSessionXml.readSession(StudioXmlNode("session", node.attributes, children))
            }
        }
    }

    @Test
    fun `draft refuses duplicate references and malformed field addresses`() {
        val draft = session().work.drafts.first()
        val node = StudioDraftXml.write(draft)
        val duplicate = StudioXmlNode("draft", node.attributes, node.children + node.child("reference"))
        assertFailsWith<IllegalArgumentException> { StudioDraftXml.read(duplicate) }
        assertFailsWith<IllegalArgumentException> { StudioDraftXml.read(node.copy(name = "wrong")) }

        val address = StudioDraftXml.writeAddress(draft.tracking.touched.first())
        val attributes = address.attributes + ("part" to "unknown")
        assertFailsWith<IllegalArgumentException> { StudioDraftXml.readAddress(address.copy(attributes = attributes)) }
        assertFailsWith<IllegalArgumentException> { StudioDraftXml.readAddress(address.copy(name = "wrong")) }
    }

    private fun session(): StudioSessionRecord {
        val fields =
            StudioLegacyFields.capture(
                freshSettingsState(),
                SyntaxIntensityBaseState(),
                AccentMappingsState(),
                origins = emptyMap(),
            )
        val factory = StudioFactoryProfiles.create(fields).first()
        val profile = ProfileDefinition.User(ProfileId("personal"), "Personal", factory.appearance, 2)
        val reference = ProfileReference(profile.id, profile.revision)
        val baseline = AcceptedAppearance(factory.appearance, 7, reference)
        val address = LegacyFieldKey(LegacyStore.SYNTAX, "customOverrides")
        val tracking =
            DraftTracking(
                initial = factory.appearance,
                touched =
                    StudioList(
                        listOf(
                            FieldAddress(address, FieldPart.MapEntry($$"language|$LITERAL$")),
                            FieldAddress(address, FieldPart.MapOrder),
                        ),
                    ),
                reapply = StudioList(listOf(FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled")))),
                intent = DraftIntent.FULL_PROFILE,
                rotationOrigin = RotationAcceptanceOrigin.PROFILE_ACTIVATION,
            )
        val first = DraftRecord(DraftId("z"), reference, baseline, factory.appearance, "Former name", tracking)
        val second = DraftRecord(DraftId("a"), null, baseline, factory.appearance)
        val catalog =
            StudioList(
                listOf(
                    CatalogIntent.Create(profile.copy(id = ProfileId("new"), revision = 0), first.id),
                    CatalogIntent.Update(profile, profile.copy(revision = 3), second.id),
                    CatalogIntent.Rename(profile, "New label"),
                    CatalogIntent.Delete(profile),
                ),
            )
        val field = fields.records.first { it.key.name == "externalThemeEnhancementsEnabled" }
        val shared =
            StudioList(
                listOf(
                    SharedIntent.SystemPolicy(SystemAccentPolicy.BY_PROFILE, SystemAccentPolicy.FORCE_ON),
                    SharedIntent.Field(field, field.copy(value = RawValue.BooleanValue(true))),
                ),
            )
        val work = SessionWork(StudioList(listOf(first, second)), second.id, catalog, shared)
        val project = StudioTarget.Project(StudioProjectKey($$"/work/$PROJECT$/ā"))
        return StudioSessionRecord("session-1", 5, project, 7, work, SessionAvailability.RECOVERED)
    }
}
