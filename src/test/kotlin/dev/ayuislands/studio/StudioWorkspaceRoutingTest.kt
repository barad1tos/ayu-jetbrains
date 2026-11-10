package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioWorkspaceRoutingTest {
    @Test
    fun `a workspace edit selects only its changed native inputs`() {
        val document = sessionDocument()
        for (field in document.shared.workspace.records
            .filter { it.value is RawValue.BooleanValue }) {
            val before = field.value as RawValue.BooleanValue
            val edit = SharedIntent.Field(field, field.copy(value = RawValue.BooleanValue(!before.value)))
            val plan = accept(document, session(document, listOf(edit)))

            assertEquals(listOf(EffectOwner.WORKSPACE), plan.effects)
            assertEquals(listOf(FieldAddress(field.key)), plan.workspaceFields)
            assertEquals(document.currents, plan.candidate.currents)
        }
    }

    @Test
    fun `already accepted workspace edits are not replayed from a stale draft`() {
        val document = sessionDocument()
        val edits =
            document.shared.workspace.records
                .filter { it.key.name in setOf("hideEditorVScrollbar", "hideEditorHScrollbar") }
                .map { field ->
                    val before = field.value as RawValue.BooleanValue
                    SharedIntent.Field(field, field.copy(value = RawValue.BooleanValue(!before.value)))
                }
        val latest =
            document.copy(
                shared =
                    document.shared.copy(
                        workspace =
                            document.shared.workspace.copy(
                                records =
                                    StudioList(
                                        document.shared.workspace.records.map {
                                            if (it.key == edits.first().before.key) edits.first().after else it
                                        },
                                    ),
                            ),
                    ),
            )
        val plan = accept(latest, session(document, edits))

        assertEquals(listOf(FieldAddress(edits.last().before.key)), plan.workspaceFields)
    }

    @Test
    fun `source presence alone does not reapply workspace surfaces`() {
        val document = sessionDocument()
        val field =
            document.shared.workspace.records
                .first()
        val edit = SharedIntent.Field(field, field.copy(origin = FieldOrigin(SourcePresence.VALUE)))
        val plan = accept(document, session(document, listOf(edit)))

        assertTrue(plan.workspaceFields.isEmpty())
        assertTrue(plan.effects.isEmpty())
    }

    private fun session(
        document: StudioDocument,
        edits: List<SharedIntent>,
    ): StudioSessionRecord {
        val record = sessionRecord(document)
        return record.copy(work = record.work.copy(shared = StudioList(edits)))
    }

    private fun accept(
        document: StudioDocument,
        session: StudioSessionRecord,
    ): AcceptancePlan.Commit {
        val request = AcceptanceRequest(session, "workspace", 1, AcceptanceOrigin.SETTINGS)
        val context = sessionInputs(document, sessionState(document)).context
        return assertIs<AcceptancePlan.Commit>(
            assertIs<AcceptancePlanResult.Ready>(StudioAcceptancePlanner.plan(request, document, context)).plan,
        )
    }
}
