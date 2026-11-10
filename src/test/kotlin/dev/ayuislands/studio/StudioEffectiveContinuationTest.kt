package dev.ayuislands.studio

import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioAcceptedInput
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioEffectiveContinuation
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioDraftPolicy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioEffectiveContinuationTest {
    @Test
    fun `optional continuation round trips exact requested data provenance and granular source dependency`() {
        val baseline = sessionDocument()
        val absent = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(baseline))).document
        assertNull(absent.runtime.effectiveContinuation)
        val held = heldDocument()
        val encoded = StudioStateCodec.encode(held)
        val decoded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(encoded)).document

        assertEquals(held, decoded)
        assertContentEquals(encoded, StudioStateCodec.encode(decoded))
        assertEquals(held.currents, decoded.currents)
        assertEquals(held.shared, decoded.shared)
        assertEquals(held.catalog, decoded.catalog)
        assertEquals(held.runtime.legacy, decoded.runtime.legacy)
    }

    @Test
    fun `font acceptance merges requested appearance while preserving held project accent and source`() {
        val baseline = heldDocument()
        val record = edit(baseline, "fontPresetEnabled", RawValue.BooleanValue(true))
        val accepted = accept(baseline, record)
        val before = assertNotNull(baseline.runtime.effectiveContinuation)
        val after = assertNotNull(accepted.runtime.effectiveContinuation)

        assertEquals(before.source, after.source)
        assertEquals(before.accent, after.accent)
        assertEquals(accepted.currents.global.appearance, after.requested)
        assertEquals(baseline.currents.global.appearance.accent, accepted.currents.global.appearance.accent)
        assertEquals(baseline.shared, accepted.shared)
    }

    @Test
    fun `masked raw color and unrelated pin changes retain held accent but matching pin replacement releases it`() {
        val baseline = heldDocument()
        val rawEdited = accept(baseline, edit(baseline, "mirageAccent", RawValue.Text("#112233")))
        assertEquals(baseline.runtime.effectiveContinuation?.accent, rawEdited.runtime.effectiveContinuation?.accent)

        val unrelated = accept(baseline, pin(baseline, "another-project"))
        assertEquals(baseline.runtime.effectiveContinuation?.accent, unrelated.runtime.effectiveContinuation?.accent)

        val matching = accept(baseline, pin(baseline, "closed-project"))
        assertNull(matching.runtime.effectiveContinuation?.accent)
        assertEquals(baseline.currents.global.appearance, matching.currents.global.appearance)
    }

    private fun heldDocument(): StudioDocument {
        val original = sessionDocument()
        val mappings = original.shared.mappings
        val field = mappings.records.single { it.key.name == "projectAccents" }
        val pinned =
            field.copy(
                value =
                    RawValue.OrderedMap(
                        StudioList(listOf(RawValue.Entry("closed-project", RawValue.Text("#FF0000")))),
                    ),
            )
        val dependency =
            StudioAcceptedInput(
                FieldAddress(field.key, FieldPart.MapEntry("closed-project")),
                FieldValue.Present(RawValue.Text("#FF0000")),
            )
        val held =
            StudioEffectiveContinuation(
                RuntimeOwner(StudioTarget.Project(StudioProjectKey("closed-project")), 17),
                original.currents.global.appearance,
                StudioAccentContinuation(
                    AyuVariant.MIRAGE,
                    ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#FF0000"),
                    StudioList(listOf(dependency)),
                ),
            )
        return original.copy(
            shared =
                original.shared.copy(
                    mappings =
                        mappings.copy(
                            records =
                                StudioList(
                                    mappings.records.map {
                                        if (it.key ==
                                            field.key
                                        ) {
                                            pinned
                                        } else {
                                            it
                                        }
                                    },
                                ),
                        ),
                ),
            runtime = original.runtime.copy(effectiveContinuation = held),
        )
    }

    private fun edit(
        document: StudioDocument,
        name: String,
        value: RawValue,
    ): StudioSessionRecord {
        val record = sessionRecord(document)
        val draft =
            StudioDraftPolicy.edit(
                record.work.drafts.single(),
                FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, name)),
                FieldValue.Present(value),
            )
        return record.copy(generation = 1, work = record.work.copy(drafts = StudioList(listOf(draft))))
    }

    private fun pin(
        document: StudioDocument,
        project: String,
    ): StudioSessionRecord {
        val record = sessionRecord(document)
        val before =
            document.shared.mappings.records
                .single { it.key.name == "projectAccents" }
        val entries =
            assertIs<RawValue.OrderedMap>(
                before.value,
            ).entries.associate { it.key to it.value }.toMutableMap()
        entries[project] = RawValue.Text("#334455")
        val after =
            before.copy(
                value = RawValue.OrderedMap(StudioList(entries.map { RawValue.Entry(it.key, it.value) })),
            )
        return record.copy(
            generation = 1,
            work = record.work.copy(shared = StudioList(listOf(SharedIntent.Field(before, after)))),
        )
    }

    private fun accept(
        document: StudioDocument,
        record: StudioSessionRecord,
    ): StudioDocument {
        val context = sessionInputs(document, sessionState(document)).context
        val observed =
            context.copy(
                font =
                    context.font.copy(
                        requested =
                            record.work.drafts
                                .single()
                                .appearance.font,
                    ),
            )
        val result =
            StudioAcceptancePlanner.plan(
                AcceptanceRequest(record, "continuation-accept", 1, AcceptanceOrigin.SETTINGS),
                document,
                observed,
            )
        return assertIs<AcceptancePlan.Commit>(assertIs<AcceptancePlanResult.Ready>(result).plan).candidate
    }
}
