package dev.ayuislands.studio

import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.migration.StudioFieldMapping
import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.AuxiliaryPreferences
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.DocumentHeader
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioAcceptedInput
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioCurrents
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioEffectiveContinuation
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.persistence.DecodeIssue
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioDocumentXml
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StudioStateCodecTest {
    @Test
    fun `document round trip retains ordered projects drafts archives and runtime state`() {
        val original = document()
        val encoded = StudioStateCodec.encode(original)
        val decoded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(encoded))

        assertEquals(original, decoded.document)
        assertContentEquals(encoded, StudioStateCodec.encode(decoded.document))
        val payload = StudioXml.write(StudioDocumentXml.writeDocument(original))
        assertEquals(StudioXml.hashBytes(payload), decoded.digest)
    }

    @Test
    fun `future schema does not decode or reinterpret its payload`() {
        val future = """<ayu-studio schema="2"><payload>new-format!</payload></ayu-studio>"""
        assertEquals(DecodeResult.FutureSchema(2), StudioStateCodec.decode(future.encodeToByteArray()))
    }

    @Test
    fun `checksummed document must match its outer transaction metadata`() {
        val original = document()
        val documentNode = StudioDocumentXml.writeDocument(original)
        val metadata = documentNode.attributes + ("revision" to "4")
        val mismatch = StudioXml.envelope("ayu-studio", metadata, documentNode)
        val valid = StudioXml.envelope("ayu-studio", documentNode.attributes, documentNode)
        val digest = StudioXml.hashBytes(StudioXml.write(documentNode))
        val badHash = StudioXml.write(valid).decodeToString().replace(digest, "0".repeat(64))
        assertEquals(DecodeResult.Invalid(DecodeIssue.CHECKSUM), StudioStateCodec.decode(badHash.encodeToByteArray()))
        assertEquals(
            DecodeResult.Invalid(DecodeIssue.INVALID_STRUCTURE),
            StudioStateCodec.decode(StudioXml.write(mismatch)),
        )
    }

    @Test
    fun `duplicate project identities and misplaced fields are rejected in both directions`() {
        val original = document()
        val projects = original.currents.projects
        val duplicate = original.copy(currents = original.currents.copy(projects = StudioList(projects + projects[0])))
        val misplaced = original.copy(shared = original.shared.copy(workspace = original.shared.plugins))
        listOf(duplicate, misplaced).forEach(::assertInvalid)
    }

    @Test
    fun `invalid draft addresses and pending edits cannot become a recoverable document`() {
        val original = document()
        val session = original.sessions.single()
        val work = session.work
        val draft = work.drafts.first()
        val field = draft.appearance.glow.records[0]
        val address = FieldAddress(field.key, FieldPart.MapOrder)
        val badDraft = draft.copy(tracking = draft.tracking.copy(touched = StudioList(listOf(address))))
        val wrongOwner = SharedIntent.Field(field, field)
        val setting = original.shared.plugins.records[0]
        val shared = SharedIntent.Field(setting, setting)
        val user = original.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val stale = CatalogIntent.Update(user, user, work.selected)
        val valid = CatalogIntent.Update(user, user.copy(revision = user.revision + 1), work.selected)
        val cases =
            listOf(
                work.copy(drafts = StudioList(listOf(badDraft, work.drafts[1]))),
                work.copy(shared = StudioList(listOf(wrongOwner))),
                work.copy(shared = StudioList(listOf(shared, shared))),
                work.copy(catalog = StudioList(listOf(stale))),
            )
        for (invalid in cases) {
            assertInvalid(original.copy(sessions = StudioList(listOf(session.copy(work = invalid)))))
        }
        val updated = session.copy(work = work.copy(catalog = StudioList(listOf(valid))))
        val recoverable = original.copy(sessions = StudioList(listOf(updated)))
        val decoded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(recoverable)))
        assertEquals(recoverable, decoded.document)
    }

    @Test
    fun `blank project targets and malformed draft sources cannot be recovered`() {
        val original = document()
        val session = original.sessions.single()
        val draft = session.work.drafts[0]
        for (source in listOf(ProfileReference(ProfileId(""), 1), ProfileReference(ProfileId("custom"), -1))) {
            val work =
                session.work.copy(
                    drafts = StudioList(listOf(draft.copy(source = source), session.work.drafts[1])),
                )
            assertInvalid(original.copy(sessions = StudioList(listOf(session.copy(work = work)))))
        }
        val blank = session.copy(target = StudioTarget.Project(StudioProjectKey(" ")))
        assertInvalid(original.copy(sessions = StudioList(listOf(blank))))
    }

    @Test
    fun `continuation accepts shared mapping entries but rejects invalid addresses`() {
        val original = document()
        val continuation = requireNotNull(original.runtime.effectiveContinuation)
        val key = LegacyFieldKey(LegacyStore.MAPPINGS, "projectAccents")
        val entry = FieldAddress(key, FieldPart.MapEntry("/work/missing"))

        fun withAddress(address: FieldAddress): StudioDocument {
            val accent =
                StudioAccentContinuation(
                    null,
                    ResolvedAccent(AccentResolver.Source.GLOBAL, "#FFCD66"),
                    StudioList(listOf(StudioAcceptedInput(address, FieldValue.Absent))),
                )
            return original.copy(
                runtime = original.runtime.copy(effectiveContinuation = continuation.copy(accent = accent)),
            )
        }

        val valid = withAddress(entry)
        assertEquals(
            valid,
            assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(valid))).document,
        )
        assertInvalid(withAddress(entry.copy(field = key.copy(name = "missing"))))
        val scalar = LegacyFieldKey(LegacyStore.MAPPINGS, "languageFallbackAccent")
        assertInvalid(withAddress(entry.copy(field = scalar)))
    }

    @Test
    fun `malformed framing and document type declarations cannot load`() {
        for (xml in listOf("<ayu-studio>", "<!DOCTYPE ayu-studio><ayu-studio/>")) {
            assertEquals(DecodeResult.Invalid(DecodeIssue.MALFORMED), StudioStateCodec.decode(xml.encodeToByteArray()))
        }
    }

    private fun assertInvalid(document: StudioDocument) {
        assertFailsWith<IllegalArgumentException> { StudioStateCodec.encode(document) }
        val node = StudioDocumentXml.writeDocument(document)
        val envelope = StudioXml.envelope("ayu-studio", node.attributes, node)
        assertEquals(
            DecodeResult.Invalid(DecodeIssue.INVALID_STRUCTURE),
            StudioStateCodec.decode(StudioXml.write(envelope)),
        )
    }

    private fun document(): StudioDocument {
        val fields =
            StudioLegacyFields.capture(
                freshSettingsState(),
                SyntaxIntensityBaseState(),
                AccentMappingsState(),
                origins = emptyMap(),
            )
        val factories = StudioFactoryProfiles.create(fields)
        val appearance = factories.first().appearance
        val profile = ProfileDefinition.User(ProfileId("custom"), "Custom", appearance, 1)
        val current = AcceptedAppearance(appearance, 2, ProfileReference(profile.id, profile.revision))
        val project = StudioProjectKey($$"/work/$PROJECT$/ā")
        val target = StudioTarget.Project(project)
        val owner = RuntimeOwner(target, current.revision)
        val draft = DraftRecord(DraftId("first"), current.source, current, appearance)
        val other = draft.copy(id = DraftId("second"), source = null)
        val drafts = StudioList(listOf(other, draft))
        val work = SessionWork(drafts, draft.id, StudioList(emptyList()), StudioList(emptyList()))
        val session = StudioSessionRecord("session", 3, target, 2, work, SessionAvailability.RECOVERED)
        val archives =
            listOf(
                LegacyArchive(LegacyStore.MAPPINGS, LegacyFile.Present(StudioBytes(byteArrayOf()))),
                LegacyArchive(LegacyStore.SETTINGS, LegacyFile.Present(StudioBytes(byteArrayOf(0, -1, 32)))),
                LegacyArchive(LegacyStore.SYNTAX, LegacyFile.Absent),
            )

        fun owned(owner: StudioFieldOwner) = StudioFieldMapping.select(fields, owner)
        val projects =
            listOf(
                StudioProjectRecord(project, "Pinned", current),
                StudioProjectRecord(StudioProjectKey("/work/a"), "Other", current.copy(source = null)),
            )
        return StudioDocument(
            DocumentHeader(1, 3, $$"save-$ID$", MigrationSource("migration", StudioList(archives))),
            StudioList(factories + profile),
            StudioCurrents(current.copy(source = ProfileReference(ProfileId("unavailable"), 1)), StudioList(projects)),
            SharedPreferences(
                owned(StudioFieldOwner.PLUGINS),
                owned(StudioFieldOwner.WORKSPACE),
                owned(StudioFieldOwner.SYSTEM),
                owned(StudioFieldOwner.MAPPINGS),
                SystemAccentPolicy.FORCE_ON,
            ),
            AuxiliaryPreferences(
                owned(StudioFieldOwner.UI),
                owned(StudioFieldOwner.RESOURCES),
                owned(StudioFieldOwner.MIGRATION),
            ),
            StudioRuntimeState(
                owned(StudioFieldOwner.RUNTIME),
                RotationRuntimeCheckpoint(owner, -1, 0, resume = RotationResume.NoHistory),
                StudioEffectiveContinuation(owner, appearance, null),
            ),
            StudioList(listOf(session)),
            StudioList(listOf(OpaqueRecord("future", StudioBytes(byteArrayOf(9, 0, -1))))),
        )
    }
}
