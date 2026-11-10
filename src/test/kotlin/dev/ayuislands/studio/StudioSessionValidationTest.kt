package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionHandling
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSessionValidationTest {
    @Test
    fun `shared edit cannot smuggle an appearance field or change its stable key`() {
        val document = sessionDocument()
        val state = sessionState(document)
        val fields = document.shared.workspace.records
        val appearance =
            document.currents.global.appearance.glow.records
                .first()
        val invalid =
            listOf(SharedIntent.Field(appearance, appearance), SharedIntent.Field(fields.first(), fields.last()))
        for (intent in invalid) {
            val result =
                StudioSessionReducer.reduce(
                    state,
                    SessionEvent.Intent(SessionIntent.Shared(intent)),
                    sessionInputs(document, state),
                )
            assertEquals(SessionHandling.REJECTED, result.handling)
            assertEquals(state.record, result.state.record)
            assertTrue(result.commands.isEmpty())
        }
    }

    @Test
    fun `invalid scalar removal and unknown reapply address are rejected without throwing or mutating`() {
        val document = sessionDocument()
        val state = sessionState(document)
        val draft = requireNotNull(state.record).work.selected
        val scalar = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
        val unknown = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "not-a-field"))
        val intents =
            listOf(
                SessionIntent.EditField(draft, scalar, FieldValue.Absent),
                SessionIntent.EditField(draft, unknown, FieldValue.Absent),
                SessionIntent.Reapply(draft, StudioList(listOf(unknown))),
            )
        for (intent in intents) {
            val result = StudioSessionReducer.reduce(state, SessionEvent.Intent(intent), sessionInputs(document, state))
            assertEquals(SessionHandling.REJECTED, result.handling)
            assertEquals(state.record, result.state.record)
        }
    }

    @Test
    fun `duplicate shared intents cannot bypass review in memory or become writable after restart`() {
        val document = sessionDocument()
        val state = sessionState(document)
        val record = requireNotNull(state.record)
        val field =
            document.shared.workspace.records
                .first()
        val intent = SharedIntent.Field(field, field)
        val invalid = record.copy(work = record.work.copy(shared = StudioList(listOf(intent, intent))))
        val request = AcceptanceRequest(invalid, "invalid", 1, AcceptanceOrigin.SETTINGS)
        val blocked =
            assertIs<AcceptancePlanResult.Blocked>(
                StudioAcceptancePlanner.plan(request, document, sessionInputs(document, state).context),
            )
        assertEquals(listOf(AcceptanceBlock.INVALID_REQUEST), blocked.reasons)
        val encoded = StudioStateCodec.encode(document.copy(sessions = StudioList(listOf(invalid))))
        assertIs<DecodeResult.Invalid>(StudioStateCodec.decode(encoded))
    }

    @Test
    fun `staged update cannot replace a different profile identity after restart`() {
        val document = sessionDocument()
        val record = sessionRecord(document)
        val before = document.catalog.filterIsInstance<ProfileDefinition.User>().first()
        val after = before.copy(id = ProfileId("another-profile"), revision = before.revision + 1)
        val intent = CatalogIntent.Update(before, after, record.work.selected)
        val invalid = record.copy(work = record.work.copy(catalog = StudioList(listOf(intent))))
        val encoded = StudioStateCodec.encode(document.copy(sessions = StudioList(listOf(invalid))))
        assertIs<DecodeResult.Invalid>(StudioStateCodec.decode(encoded))
    }
}
