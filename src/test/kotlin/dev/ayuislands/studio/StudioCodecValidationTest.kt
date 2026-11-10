package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioCurrents
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.persistence.DecodeIssue
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.StudioValueXml
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.StudioXmlNode
import java.nio.charset.CharacterCodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StudioCodecValidationTest {
    @Test
    fun `known fields with wrong raw type block writable decoding`() {
        val input = document()
        val invalid =
            input.shared.system.records.map { field ->
                if (field.key.name == "followSystemAppearance") field.copy(value = RawValue.Text("true")) else field
            }
        val changed =
            input.copy(
                shared = input.shared.copy(system = input.shared.system.copy(records = StudioList(invalid))),
            )
        assertIs<DecodeResult.Invalid>(StudioStateCodec.decode(StudioStateCodec.encode(changed)))
    }

    @Test
    fun `draft identities staged snapshots and all session availability states round trip`() {
        val input = document()
        val original = input.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val renamed = original.copy(name = "Same name", revision = original.revision + 1)
        val created = original.copy(id = ProfileId("user-2"), name = renamed.name)
        val reference = ProfileReference(original.id, original.revision)
        val accepted = input.currents.global.copy(source = reference)
        val key = StudioProjectKey($$"/projects/$USER_HOME$/ exact ")
        val field =
            input.shared.system.records
                .first()
        val sessions =
            SessionAvailability.entries.mapIndexed { index, availability ->
                val id = DraftId("draft-$index")
                StudioSessionRecord(
                    "session-$index",
                    7,
                    if (index ==
                        0
                    ) {
                        StudioTarget.Global
                    } else {
                        StudioTarget.Project(key)
                    },
                    3,
                    SessionWork(
                        StudioList(listOf(DraftRecord(id, reference, accepted, renamed.appearance))),
                        id,
                        StudioList(
                            listOf(
                                CatalogIntent.Create(created, id),
                                CatalogIntent.Update(original, renamed, id),
                                CatalogIntent.Rename(original, "Explicit rename"),
                                CatalogIntent.Delete(created),
                            ),
                        ),
                        StudioList(
                            listOf(
                                SharedIntent.Field(field, field),
                                SharedIntent.SystemPolicy(SystemAccentPolicy.FORCE_OFF, SystemAccentPolicy.BY_PROFILE),
                            ),
                        ),
                    ),
                    availability,
                )
            }
        val candidate =
            input.copy(
                currents =
                    StudioCurrents(
                        accepted,
                        StudioList(listOf(StudioProjectRecord(key, "Same name", accepted))),
                    ),
                sessions = StudioList(sessions),
            )
        assertEquals(
            candidate,
            assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(candidate))).document,
        )
    }

    @Test
    fun `raw transport preserves null empty whitespace IEEE bits and recursive collection order`() {
        val values =
            listOf(
                RawValue.Null,
                RawValue.Text(""),
                RawValue.Text($$"\u0000 \r\n $PROJECT_DIR$ < &"),
                RawValue.BooleanValue(false),
                RawValue.Integer(Int.MIN_VALUE),
                RawValue.LongValue(Long.MAX_VALUE),
                RawValue.FloatBits((-0.0f).toRawBits()),
                RawValue.FloatBits(0x7fc00001),
                RawValue.Bytes(StudioBytes(byteArrayOf(0, -1))),
                RawValue.OrderedSet(StudioList(listOf("z", "a", ""))),
                RawValue.OrderedList(StudioList(listOf(RawValue.Null, RawValue.Text("tail")))),
            )
        val nested =
            RawValue.OrderedMap(
                StudioList(
                    values.mapIndexed {
                        index,
                        value,
                        ->
                        RawValue.Entry("key-$index", value)
                    },
                ),
            )
        val output = StudioValueXml.readValue(StudioXml.parse(StudioXml.write(StudioValueXml.writeValue(nested))))
        assertEquals(nested, output)
        for (malformed in listOf(
            StudioXmlNode("value", mapOf("type" to "boolean"), text = "TRUE"),
            StudioXmlNode("value", mapOf("type" to "future")),
        )) {
            assertFailsWith<IllegalArgumentException> { StudioValueXml.readValue(malformed) }
        }
        assertFailsWith<CharacterCodingException> {
            StudioValueXml.readValue(StudioXmlNode("value", mapOf("type" to "text"), text = "/w=="))
        }
    }

    @Test
    fun `checksum and envelope identity prevent accepting a different generation`() {
        val encoded = StudioXml.parse(StudioStateCodec.encode(document()))
        val payload = encoded.child("payload")
        val wrongChecksum = encoded.copy(children = listOf(payload.copy(attributes = mapOf("sha256" to "00"))))
        assertEquals(
            DecodeResult.Invalid(DecodeIssue.CHECKSUM),
            StudioStateCodec.decode(StudioXml.write(wrongChecksum)),
        )
        val wrongRevision = encoded.copy(attributes = encoded.attributes + ("revision" to "999"))
        assertIs<DecodeResult.Invalid>(StudioStateCodec.decode(StudioXml.write(wrongRevision)))
        assertIs<DecodeResult.Invalid>(
            StudioStateCodec.decode(StudioXml.write(encoded.copy(children = listOf(payload, payload)))),
        )
    }

    private fun document(): StudioDocument =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.load("unknown-and-ordered"))).document
}
