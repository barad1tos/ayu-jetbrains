package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.CapabilitySnapshot
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.FontObservation
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioCatalogPolicy
import dev.ayuislands.studio.session.StudioDraftPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioAcceptancePlannerTest {
    @Test
    fun `catalog only accepts definitions without applying appearance or requiring a font`() {
        val document = sessionDocument()
        val session = sessionRecord(document)
        val work =
            assertIs<CatalogStage.Staged>(
                StudioCatalogPolicy.stage(
                    document,
                    session.work,
                    CatalogCommand.Create(session.work.selected, ProfileId("new"), "Saved"),
                ),
            ).work
        val selected = session.copy(work = work)
        val plan =
            commit(
                StudioAcceptancePlanner.plan(
                    request(selected),
                    document,
                    context(selected, FontAvailability.MISSING_CURATED),
                ),
            )
        assertTrue(plan.effects.isEmpty())
        assertTrue(plan.glowSelection.fields.isEmpty())
        assertTrue(plan.glowSelection.dependencies.isEmpty())
        assertEquals(document.currents, plan.candidate.currents)
        assertEquals(
            ProfileId("new"),
            plan.candidate.catalog
                .last()
                .id,
        )
        assertTrue(
            plan.candidate.sessions
                .single()
                .work.catalog
                .isEmpty(),
        )
    }

    @Test
    fun `a hidden edited draft blocks acceptance until its exact snapshot is staged`() {
        val document = sessionDocument()
        val initial = sessionRecord(document)
        val profile =
            StudioDraftPolicy.select(
                initial.work,
                document.catalog.first(),
                DraftId("profile"),
                document.currents.global,
            )
        val edited = editGlow(profile.drafts.last())
        val hidden =
            initial.copy(
                work =
                    profile.copy(
                        selected = initial.work.selected,
                        drafts =
                            StudioList(
                                profile.drafts.map {
                                    if (it.id ==
                                        edited.id
                                    ) {
                                        edited
                                    } else {
                                        it
                                    }
                                },
                            ),
                    ),
            )
        val review =
            assertIs<AcceptancePlanResult.ReviewRequired>(
                StudioAcceptancePlanner.plan(request(hidden), document, context(hidden)),
            )
        assertEquals(listOf<AcceptanceReview>(AcceptanceReview.HiddenDraft(edited.id)), review.items)
        val saved =
            assertIs<CatalogStage.Staged>(
                StudioCatalogPolicy.stage(
                    document,
                    hidden.work,
                    CatalogCommand.Create(edited.id, ProfileId("saved"), "Saved"),
                ),
            ).work
        val ready = commit(StudioAcceptancePlanner.plan(request(hidden.copy(work = saved)), document, context(hidden)))
        assertTrue(ready.effects.isEmpty())
        val preserved =
            ready.candidate.sessions
                .single()
                .work.drafts
                .single { it.id == edited.id }
        assertEquals(edited.appearance, preserved.appearance)
        assertTrue(!StudioDraftPolicy.isDirty(preserved))
    }

    @Test
    fun `inactive captured target accepts its own appearance but any shared edit blocks the whole request`() {
        val document = sessionDocument()
        val initial = sessionRecord(document)
        val edited =
            initial.copy(
                work = initial.work.copy(drafts = StudioList(listOf(editGlow(initial.work.drafts.single())))),
            )
        val inactive = context(edited).copy(target = TargetObservation(StudioTarget.Global, 1, TargetPresence.INACTIVE))
        val plan = commit(StudioAcceptancePlanner.plan(request(edited), document, inactive))
        assertTrue(plan.effects.isEmpty())
        assertTrue(plan.glowSelection.fields.isEmpty())
        assertTrue(plan.glowSelection.dependencies.isEmpty())
        assertEquals(
            edited.work.drafts
                .single()
                .appearance,
            plan.candidate.currents.global.appearance,
        )
        val field =
            document.shared.workspace.records
                .first { it.value is RawValue.BooleanValue }
        val requested = field.copy(value = RawValue.BooleanValue(!(field.value as RawValue.BooleanValue).value))
        val shared =
            edited.copy(
                work = edited.work.copy(shared = StudioList(listOf(SharedIntent.Field(field, requested)))),
            )
        val blocked =
            assertIs<AcceptancePlanResult.Blocked>(StudioAcceptancePlanner.plan(request(shared), document, inactive))
        assertEquals(listOf(AcceptanceBlock.ACTIVATE_TARGET), blocked.reasons)
    }

    @Test
    fun `an unrelated field edit preserves newer accepted font intent and runtime metadata`() {
        val document = sessionDocument()
        val initial = sessionRecord(document)
        val edited =
            initial.copy(
                work = initial.work.copy(drafts = StudioList(listOf(editGlow(initial.work.drafts.single())))),
            )
        val latestAppearance =
            document.currents.global.appearance
                .copy(
                    font =
                        document.catalog
                            .first()
                            .appearance.font,
                )
        val latest =
            document.copy(
                currents =
                    document.currents.copy(
                        global = document.currents.global.copy(appearance = latestAppearance, revision = 4),
                    ),
                header = document.header.copy(revision = 8),
            )
        val plan =
            commit(
                StudioAcceptancePlanner.plan(
                    request(edited),
                    latest,
                    context(edited, FontAvailability.MISSING_CURATED),
                ),
            )
        assertEquals(latestAppearance.font, plan.candidate.currents.global.appearance.font)
        assertEquals(listOf(EffectOwner.GLOW), plan.effects)
        assertEquals(latest.runtime, plan.candidate.runtime)
        assertEquals(9, plan.candidate.header.revision)
    }

    @Test
    fun `captured live generation and store authority are validated before any candidate`() {
        val document = sessionDocument()
        val session = sessionRecord(document)
        val replaced = context(session).copy(target = TargetObservation(StudioTarget.Global, 2, TargetPresence.ACTIVE))
        assertEquals(
            listOf(AcceptanceBlock.TARGET_REPLACED),
            assertIs<AcceptancePlanResult.Blocked>(
                StudioAcceptancePlanner.plan(request(session), document, replaced),
            ).reasons,
        )
        for (authority in listOf(AuthorityStatus.READ_ONLY, AuthorityStatus.RECOVERY)) {
            assertIs<AcceptancePlanResult.Blocked>(
                StudioAcceptancePlanner.plan(request(session), document, context(session).copy(authority = authority)),
            )
        }
    }

    @Test
    fun `quick switcher refuses unresolved live settings but not recovered orphan work`() {
        val document = sessionDocument()
        val session = sessionRecord(document)
        val dirty =
            session.copy(
                work = session.work.copy(drafts = StudioList(listOf(editGlow(session.work.drafts.single())))),
            )
        val latest = document.copy(sessions = StudioList(listOf(dirty)))
        val popup = session.copy(id = "popup")
        val result =
            StudioAcceptancePlanner.plan(
                request(popup).copy(origin = AcceptanceOrigin.QUICK_SWITCHER),
                latest,
                context(popup),
            )
        assertEquals(
            listOf<AcceptanceReview>(AcceptanceReview.LiveSession(session.id)),
            assertIs<AcceptancePlanResult.ReviewRequired>(result).items,
        )
        val orphan =
            latest.copy(
                sessions = StudioList(listOf(dirty.copy(availability = SessionAvailability.RECOVERED))),
            )
        val clean =
            StudioAcceptancePlanner.plan(
                request(popup).copy(origin = AcceptanceOrigin.QUICK_SWITCHER),
                orphan,
                context(popup),
            )
        assertEquals(AcceptancePlan.Unchanged, assertIs<AcceptancePlanResult.Ready>(clean).plan)
    }

    @Test
    fun `saving a hidden appearance does not consume its separate reapply intent`() {
        val document = sessionDocument()
        val session = sessionRecord(document)
        val work =
            StudioDraftPolicy.select(
                session.work,
                document.catalog.first(),
                DraftId("hidden"),
                document.currents.global,
            )
        val draft = work.drafts.last()
        val reapply = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
        val pending = draft.copy(tracking = draft.tracking.copy(reapply = StudioList(listOf(reapply))))
        val hidden =
            work.copy(
                selected = session.work.selected,
                drafts = StudioList(work.drafts.map { if (it.id == pending.id) pending else it }),
            )
        val captured =
            assertIs<CatalogStage.Staged>(
                StudioCatalogPolicy.stage(
                    document,
                    hidden,
                    CatalogCommand.Create(pending.id, ProfileId("saved"), "Saved"),
                ),
            ).work
        val live = session.copy(work = captured)
        val result = StudioAcceptancePlanner.plan(request(live), document, context(live))
        assertEquals(
            listOf<AcceptanceReview>(AcceptanceReview.HiddenDraft(pending.id)),
            assertIs<AcceptancePlanResult.ReviewRequired>(result).items,
        )
    }

    @Test
    fun `system routing starts only when Follow appearance is newly enabled`() {
        val base = sessionDocument()
        for (wasEnabled in listOf(false, true)) {
            val system =
                base.shared.system.copy(
                    records =
                        StudioList(
                            base.shared.system.records.map {
                                if (it.key.name ==
                                    "followSystemAppearance"
                                ) {
                                    it.copy(value = RawValue.BooleanValue(wasEnabled))
                                } else {
                                    it
                                }
                            },
                        ),
                )
            val document = base.copy(shared = base.shared.copy(system = system))
            val requests =
                listOf(
                    "followSystemAppearance" to RawValue.BooleanValue(!wasEnabled),
                    "lastDarkAppearanceTheme" to RawValue.Text("Ayu Dark"),
                    "lastLightAppearanceTheme" to RawValue.Text("Ayu Light"),
                    "syncEditorScheme" to RawValue.BooleanValue(false),
                )
            for ((name, value) in requests) {
                val field = system.records.single { it.key.name == name }
                val replacement = field.copy(value = value, origin = FieldOrigin(SourcePresence.VALUE))
                val original = sessionRecord(document)
                val edits = StudioList(listOf(SharedIntent.Field(field, replacement)))
                val session = original.copy(work = original.work.copy(shared = edits))

                val plan = commit(StudioAcceptancePlanner.plan(request(session), document, context(session)))

                assertEquals(name == "followSystemAppearance" && !wasEnabled, plan.themeChange != null)
                assertTrue(EffectOwner.SYSTEM in plan.effects)
                if (plan.themeChange != null) {
                    assertEquals(listOf(EffectOwner.SYSTEM, EffectOwner.ACCENT, EffectOwner.GLOW), plan.effects)
                }
                assertEquals(document.currents, plan.candidate.currents)
                assertEquals(
                    system.records.map { if (it.key == field.key) replacement else it },
                    plan.candidate.shared.system.records,
                )
            }
        }
    }

    private fun request(session: StudioSessionRecord): AcceptanceRequest =
        AcceptanceRequest(session, "accept-1", 1, AcceptanceOrigin.SETTINGS)

    private fun context(
        session: StudioSessionRecord,
        availability: FontAvailability = FontAvailability.AVAILABLE,
    ): AcceptanceContext =
        AcceptanceContext(
            AuthorityStatus.READY,
            TargetObservation(session.target, 1, TargetPresence.ACTIVE),
            CapabilitySnapshot(1, StudioList(emptyList())),
            FontObservation(
                session.work.drafts
                    .single { it.id == session.work.selected }
                    .appearance.font,
                availability,
            ),
        )

    private fun commit(result: AcceptancePlanResult): AcceptancePlan.Commit =
        assertIs<AcceptancePlan.Commit>(assertIs<AcceptancePlanResult.Ready>(result).plan)

    private fun editGlow(draft: dev.ayuislands.studio.model.DraftRecord): dev.ayuislands.studio.model.DraftRecord {
        val key = LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled")
        val value =
            draft.appearance.glow.records
                .single { it.key == key }
                .value as RawValue.BooleanValue
        return StudioDraftPolicy.edit(draft, FieldAddress(key), FieldValue.Present(RawValue.BooleanValue(!value.value)))
    }
}
