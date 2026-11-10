package dev.ayuislands.studio

import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioAppearanceXml
import dev.ayuislands.studio.persistence.StudioDraftXml
import dev.ayuislands.studio.persistence.StudioStateCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StudioDraftCodecTest {
    @Test
    fun `draft baseline exact touches reapply and detached label survive restart`() {
        val document = sessionDocument()
        val session = sessionRecord(document)
        val draft = session.work.drafts.single()
        val map = LegacyFieldKey(LegacyStore.SYNTAX, "customOverrides")
        val tracking =
            DraftTracking(
                document.catalog.first().appearance,
                StudioList(
                    listOf(
                        FieldAddress(map, FieldPart.MapEntry("language|unknown")),
                        FieldAddress(map, FieldPart.MapOrder),
                    ),
                ),
                StudioList(listOf(FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "fontPresetEnabled")))),
                DraftIntent.FULL_PROFILE,
            )
        val edited = draft.copy(tracking = tracking, sourceName = "Former name")
        val work = session.work.copy(drafts = StudioList(listOf(edited)))
        val persisted = document.copy(sessions = StudioList(listOf(session.copy(work = work))))
        val decoded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(persisted)))
        assertEquals(persisted, decoded.document)
    }

    @Test
    fun `invalid or duplicate draft addresses never become writable sessions`() {
        val document = sessionDocument()
        val session = sessionRecord(document)
        val draft = session.work.drafts.single()
        val known = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
        val invalid = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "futureUnknownField"))
        for (touches in listOf(listOf(known, known), listOf(invalid))) {
            val edited = draft.copy(tracking = DraftTracking(draft.appearance, StudioList(touches)))
            val work = session.work.copy(drafts = StudioList(listOf(edited)))
            val candidate = document.copy(sessions = StudioList(listOf(session.copy(work = work))))
            assertIs<DecodeResult.Invalid>(StudioStateCodec.decode(StudioStateCodec.encode(candidate)))
        }
    }

    @Test
    fun `duplicate optional profile references are rejected rather than detached`() {
        val document = sessionDocument()
        val draft =
            sessionRecord(
                document,
            ).work.drafts.single().copy(source = ProfileReference(document.catalog.first().id, 1))
        val node = StudioDraftXml.write(draft)
        val reference = node.children.single { it.name == "reference" }
        assertFailsWith<IllegalArgumentException> {
            StudioDraftXml.read(
                node.copy(children = node.children + reference),
            )
        }
    }

    @Test
    fun `duplicate accepted profile references cannot become an unbound Current`() {
        val document = sessionDocument()
        val current = document.currents.global.copy(source = ProfileReference(document.catalog.first().id, 1))
        val node = StudioAppearanceXml.writeAccepted(current)
        val reference = node.children.single { it.name == "reference" }
        assertFailsWith<IllegalArgumentException> {
            StudioAppearanceXml.readAccepted(
                node.copy(
                    children =
                        node.children + reference,
                ),
            )
        }
    }
}
