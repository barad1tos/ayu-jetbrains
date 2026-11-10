package dev.ayuislands.studio

import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldResolution
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.NativeResolution
import dev.ayuislands.studio.model.NativeSurfaceChange
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.profiles.StudioProjectState
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioAcceptanceIsolationTest {
    @Test
    fun `accepting inactive project A leaves active project B and Global byte values untouched`() {
        val initial = sessionDocument()
        val a = StudioProjectKey("/a")
        val b = StudioProjectKey("/b")
        val withA = StudioProjectState.initialize(initial, a, "A", initial.currents.global)
        val document = StudioProjectState.initialize(withA, b, "B", initial.currents.global)
        val target = StudioTarget.Project(a)
        val record = changedSession(document).copy(target = target)
        val state = sessionState(document).copy(record = record)
        val context = sessionInputs(document, state).context
        val inactive = context.copy(target = context.target.copy(target = target, presence = TargetPresence.INACTIVE))
        val plan = commit(StudioAcceptancePlanner.plan(request(record), document, inactive))
        assertEquals(initial.currents.global, plan.candidate.currents.global)
        assertEquals(
            document.currents.projects.last(),
            plan.candidate.currents.projects
                .last(),
        )
        assertEquals(
            record.work.drafts
                .single()
                .appearance,
            StudioTargets.current(plan.candidate, target)?.appearance,
        )
        assertTrue(plan.effects.isEmpty())
    }

    @Test
    fun `same field review authorizes only exact latest values and supports both choices`() {
        val document = sessionDocument()
        val mine = changedSession(document)
        val foreign =
            changedSession(document, 2)
                .work.drafts
                .single()
                .appearance
        val latest =
            document.copy(
                currents =
                    document.currents.copy(
                        global = document.currents.global.copy(appearance = foreign, revision = 2),
                    ),
            )
        val context = sessionInputs(document, sessionState(document)).context
        val review =
            assertIs<AcceptancePlanResult.ReviewRequired>(
                StudioAcceptancePlanner.plan(request(mine), latest, context),
            )
        val conflict = assertIs<AcceptanceReview.Field>(review.items.single()).conflict
        for (choice in ConflictChoice.entries) {
            val choices = AcceptanceChoices(fields = StudioList(listOf(FieldResolution(conflict, choice))))
            val plan = commit(StudioAcceptancePlanner.plan(request(mine).copy(choices = choices), latest, context))
            val expected =
                if (choice == ConflictChoice.KEEP_LATEST) {
                    foreign
                } else {
                    mine.work.drafts
                        .single()
                        .appearance
                }
            assertEquals(expected, plan.candidate.currents.global.appearance)
            if (choice == ConflictChoice.KEEP_LATEST) {
                assertTrue(plan.glowSelection.fields.isEmpty())
                assertTrue(EffectOwner.GLOW !in plan.effects)
            } else {
                assertEquals(
                    listOf(FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "softIntensity"))),
                    plan.glowSelection.fields.toList(),
                )
            }
            val changedAgain =
                latest.copy(
                    currents =
                        latest.currents.copy(
                            global =
                                latest.currents.global.copy(
                                    appearance =
                                        changedSession(document, 3)
                                            .work.drafts
                                            .single()
                                            .appearance,
                                ),
                        ),
                )
            assertIs<AcceptancePlanResult.ReviewRequired>(
                StudioAcceptancePlanner.plan(request(mine).copy(choices = choices), changedAgain, context),
            )
        }
    }

    @Test
    fun `disjoint shared map additions preserve existing order and both sets of changes`() {
        val document = sessionDocument()
        val key = LegacyFieldKey(LegacyStore.MAPPINGS, "projectAccents")
        val field =
            document.shared.mappings.records
                .single { it.key == key }
        val base =
            RawValue.OrderedMap(
                StudioList(listOf(RawValue.Entry("b", RawValue.Text("B")), RawValue.Entry("a", RawValue.Text("A")))),
            )
        val before = field.copy(value = base)
        val after =
            before.copy(
                value =
                    RawValue.OrderedMap(
                        StudioList(
                            base.entries + RawValue.Entry("mine", RawValue.Text("M")),
                        ),
                    ),
            )
        val current =
            before.copy(
                value =
                    RawValue.OrderedMap(
                        StudioList(
                            base.entries + RawValue.Entry("foreign", RawValue.Text("F")),
                        ),
                    ),
            )
        val latest =
            document.copy(
                shared =
                    document.shared.copy(
                        mappings =
                            document.shared.mappings.copy(
                                records =
                                    StudioList(
                                        document.shared.mappings.records
                                            .map { if (it.key == key) current else it },
                                    ),
                            ),
                    ),
            )
        val initial = sessionRecord(document)
        val record =
            initial.copy(
                work = initial.work.copy(shared = StudioList(listOf(SharedIntent.Field(before, after)))),
            )
        val context = sessionInputs(document, sessionState(document)).context
        val plan = commit(StudioAcceptancePlanner.plan(request(record), latest, context))
        val merged =
            plan.candidate.shared.mappings.records
                .single { it.key == key }
                .value
        assertEquals(listOf("b", "a", "foreign", "mine"), assertIs<RawValue.OrderedMap>(merged).entries.map { it.key })
        assertEquals(
            current,
            latest.shared.mappings.records
                .single { it.key == key },
        )
    }

    @Test
    fun `deterministic capability limits retain requested intent without claiming the unavailable effect`() {
        val document = sessionDocument()
        val record = changedSession(document)
        val context = sessionInputs(document, sessionState(document)).context
        val reasons =
            listOf(
                LimitationReason.PRO_REQUIRED,
                LimitationReason.EXTERNAL_THEME,
                LimitationReason.UNSUPPORTED_PLATFORM,
                LimitationReason.MISSING_PLUGIN,
            )
        for (reason in reasons) {
            val limit = EffectLimitation(EffectOwner.GLOW, reason)
            val limited =
                context.copy(
                    capabilities = context.capabilities.copy(limitations = StudioList(listOf(limit))),
                )
            val plan = commit(StudioAcceptancePlanner.plan(request(record), document, limited))
            assertEquals(
                record.work.drafts
                    .single()
                    .appearance,
                plan.candidate.currents.global.appearance,
            )
            assertEquals(listOf(limit), plan.limitations)
            assertTrue(plan.effects.isEmpty())
        }
    }

    @Test
    fun `obsolete native approval is not forwarded to an unrelated effect`() {
        val document = sessionDocument()
        val record = changedSession(document)
        val context = sessionInputs(document, sessionState(document)).context
        val unrelated =
            NativeSurfaceChange(
                EffectOwner.FONT,
                "editor.size",
                FieldValue.Present(RawValue.Integer(12)),
                FieldValue.Present(RawValue.Integer(14)),
                FieldValue.Present(RawValue.Integer(16)),
            )
        val choices =
            AcceptanceChoices(native = StudioList(listOf(NativeResolution(unrelated, ConflictChoice.KEEP_LATEST))))
        val plan = commit(StudioAcceptancePlanner.plan(request(record).copy(choices = choices), document, context))
        assertTrue(plan.nativeChoices.isEmpty())
    }

    @Test
    fun `native approval expires when the proposed surface write changes`() {
        val document = sessionDocument()
        val record = changedSession(document)
        val context = sessionInputs(document, sessionState(document)).context
        val change =
            NativeSurfaceChange(
                EffectOwner.GLOW,
                "editor.glow",
                FieldValue.Present(RawValue.Integer(20)),
                FieldValue.Present(RawValue.Integer(30)),
                FieldValue.Present(RawValue.Integer(40)),
            )
        val reviewed = context.copy(native = StudioList(listOf(change)))
        val choices = AcceptanceChoices(native = StudioList(listOf(NativeResolution(change, ConflictChoice.USE_MINE))))
        val confirmed = request(record).copy(choices = choices)
        assertEquals(choices.native, commit(StudioAcceptancePlanner.plan(confirmed, document, reviewed)).nativeChoices)
        val replacement = change.copy(requested = FieldValue.Present(RawValue.Integer(50)))
        val result =
            StudioAcceptancePlanner.plan(
                confirmed,
                document,
                reviewed.copy(native = StudioList(listOf(replacement))),
            )
        assertEquals(
            listOf<AcceptanceReview>(AcceptanceReview.Native(replacement)),
            assertIs<AcceptancePlanResult.ReviewRequired>(result).items,
        )
    }

    private fun changedSession(
        document: StudioDocument,
        delta: Int = 1,
    ): StudioSessionRecord {
        val record = sessionRecord(document)
        val draft = record.work.drafts.single()
        val key = LegacyFieldKey(LegacyStore.SETTINGS, "softIntensity")
        val before = StudioAppearanceFields.field(draft.appearance, key).value as RawValue.Integer
        val edited =
            StudioDraftPolicy.edit(
                draft,
                FieldAddress(key),
                FieldValue.Present(
                    RawValue.Integer(
                        before.value + delta,
                    ),
                ),
            )
        return record.copy(work = record.work.copy(drafts = StudioList(listOf(edited))))
    }

    private fun request(record: StudioSessionRecord): AcceptanceRequest =
        AcceptanceRequest(record, "isolation", 1, AcceptanceOrigin.SETTINGS)

    private fun commit(result: AcceptancePlanResult): AcceptancePlan.Commit =
        assertIs<AcceptancePlan.Commit>(assertIs<AcceptancePlanResult.Ready>(result).plan)
}
