package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyPreflight
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.FontObservation
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.NativeResolution
import dev.ayuislands.studio.model.NativeSurfaceChange
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.StudioApplyPreflight
import dev.ayuislands.studio.session.StudioDraftPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioApplyPreflightTest {
    @Test
    fun `an enabled font requires its current resource observation and offers the appropriate action`() {
        val document = catalogTestDocument()
        val request = enableFont(document)
        val expected =
            mapOf(
                FontAvailability.MISSING_CURATED to AcceptanceBlock.FONT_INSTALL,
                FontAvailability.MISSING_CUSTOM to AcceptanceBlock.FONT_SELECT,
                FontAvailability.CORRUPTED to AcceptanceBlock.FONT_REINSTALL,
                FontAvailability.RESTART_REQUIRED to AcceptanceBlock.FONT_RESTART,
                FontAvailability.UNCONFIRMED to AcceptanceBlock.FONT_REFRESH,
            )
        for ((availability, action) in expected) {
            val observed = fontEffects(request, availability)
            assertEquals(ApplyPreflight.Blocked(action), plan(request, document, observed))
        }

        val old = FontObservation(document.currents.global.appearance.font, FontAvailability.AVAILABLE)
        assertEquals(
            ApplyPreflight.Blocked(AcceptanceBlock.FONT_REFRESH),
            plan(request, document, EffectObservation(7, old)),
        )

        val ready =
            assertIs<ApplyPreflight.Ready>(plan(request, document, fontEffects(request, FontAvailability.AVAILABLE)))
        assertEquals(listOf(EffectOwner.FONT), ready.route.owners.toList())
        assertEquals(document.header.revision, ready.lease.documentRevision)
        assertEquals(1L, ready.lease.targetGeneration)
        assertEquals(7L, ready.lease.capabilityRevision)
    }

    @Test
    fun `a missing Font only matters when its enabled effect can run`() {
        val document = catalogTestDocument()
        val request = enableFont(document)
        val limit = EffectLimitation(EffectOwner.FONT, LimitationReason.UNSUPPORTED_PLATFORM)
        val unrelated = EffectLimitation(EffectOwner.VCS, LimitationReason.PRO_REQUIRED)
        val missing = fontEffects(request, FontAvailability.MISSING_CURATED)
        val blockedEffect = missing.copy(limitations = StudioList(listOf(limit, unrelated)))
        val limited = assertIs<ApplyPreflight.Ready>(plan(request, document, blockedEffect))
        assertEquals(listOf(limit), limited.limitations.toList())
        assertTrue(limited.route.owners.isEmpty())
        assertEquals(
            request.session.work.drafts
                .single()
                .appearance.font,
            limited.candidate.document.currents.global.appearance.font,
        )

        val glow = ApplyRequest(editedJournalRecord(document), 1)
        val ready = assertIs<ApplyPreflight.Ready>(plan(glow, document, missing))
        assertEquals(listOf(EffectOwner.GLOW), ready.route.owners.toList())
        assertTrue(ready.limitations.isEmpty())
    }

    @Test
    fun `native approval is tied to the exact observed value and precedes font actions`() {
        val document = catalogTestDocument()
        val request = enableFont(document)
        val changed = NativeSurfaceChange(EffectOwner.FONT, "editor-font", integer(13), integer(15), integer(16))
        val unrelated = changed.copy(owner = EffectOwner.WORKSPACE, identity = "project-width")
        val effects =
            fontEffects(
                request,
                FontAvailability.MISSING_CURATED,
            ).copy(native = StudioList(listOf(changed, unrelated)))
        assertEquals(
            listOf<AcceptanceReview>(AcceptanceReview.Native(changed)),
            assertIs<ApplyPreflight.Review>(plan(request, document, effects)).items.toList(),
        )

        val resolution = NativeResolution(changed, ConflictChoice.USE_MINE)
        val approved = request.copy(choices = AcceptanceChoices(native = StudioList(listOf(resolution))))
        assertEquals(ApplyPreflight.Blocked(AcceptanceBlock.FONT_INSTALL), plan(approved, document, effects))
        val revised = changed.copy(actual = integer(18))
        val latest = effects.copy(native = StudioList(listOf(revised)))
        assertEquals(
            listOf<AcceptanceReview>(AcceptanceReview.Native(revised)),
            assertIs<ApplyPreflight.Review>(plan(approved, document, latest)).items.toList(),
        )
        val readyEffects = fontEffects(request, FontAvailability.AVAILABLE).copy(native = effects.native)
        val ready = assertIs<ApplyPreflight.Ready>(plan(approved, document, readyEffects))
        assertEquals(listOf(resolution), ready.native.toList())

        val newer = ApplyRequest(editField(request.session, "fontPresetName", RawValue.Text("NEON")), 1)
        assertEquals(ApplyPreflight.Blocked(AcceptanceBlock.FONT_REFRESH), plan(newer, document, readyEffects))
    }

    @Test
    fun `an unavailable Accent does not send its palette change to Glow`() {
        val document = catalogTestDocument()
        val request = ApplyRequest(editField(journalRecord(document), "mirageAccent", RawValue.Text("#123456")), 1)
        val limit = EffectLimitation(EffectOwner.ACCENT, LimitationReason.EXTERNAL_THEME)
        val effects = fontEffects(request, FontAvailability.UNCONFIRMED).copy(limitations = StudioList(listOf(limit)))
        val ready = assertIs<ApplyPreflight.Ready>(plan(request, document, effects))
        assertTrue(ready.route.owners.isEmpty())
        assertTrue(
            ready.route.glow.dependencies
                .isEmpty(),
        )
        assertEquals(listOf(limit), ready.limitations.toList())
    }

    @Test
    fun `blocking Workspace removes its payload and a Glow advisory keeps its cleanup route`() {
        val document = catalogTestDocument()
        val glow = editedJournalRecord(document)
        val field =
            document.shared.workspace.records
                .single { it.key.name == "hideEditorVScrollbar" }
        val changed = field.copy(value = RawValue.BooleanValue(!(field.value as RawValue.BooleanValue).value))
        val combined = glow.copy(work = glow.work.copy(shared = StudioList(listOf(SharedIntent.Field(field, changed)))))
        val request = ApplyRequest(combined, 1)
        val workspace = EffectLimitation(EffectOwner.WORKSPACE, LimitationReason.UNSUPPORTED_PLATFORM)
        val advisory = EffectLimitation(EffectOwner.GLOW, LimitationReason.PRO_REQUIRED, blocksEffect = false)
        val effects =
            fontEffects(
                request,
                FontAvailability.AVAILABLE,
            ).copy(limitations = StudioList(listOf(workspace, advisory)))
        val ready = assertIs<ApplyPreflight.Ready>(plan(request, document, effects))
        assertEquals(listOf(EffectOwner.GLOW), ready.route.owners.toList())
        assertTrue(ready.route.workspace.isEmpty())
        assertFalse(
            ready.route.glow.fields
                .isEmpty(),
        )
        assertEquals(listOf(workspace, advisory), ready.limitations.toList())
        assertEquals(
            changed.value,
            ready.candidate.document.shared.workspace.records
                .single { it.key == field.key }
                .value,
        )
    }

    private fun enableFont(document: StudioDocument): ApplyRequest =
        ApplyRequest(editField(journalRecord(document), "fontPresetEnabled", RawValue.BooleanValue(true)), 1)

    private fun editField(
        session: StudioSessionRecord,
        key: String,
        value: RawValue,
    ): StudioSessionRecord {
        val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, key))
        val draft =
            StudioDraftPolicy.edit(
                session.work.drafts.single(),
                address,
                FieldValue.Present(value),
            )
        return session.copy(work = session.work.copy(drafts = StudioList(listOf(draft))))
    }

    private fun fontEffects(
        request: ApplyRequest,
        availability: FontAvailability,
    ): EffectObservation {
        val font =
            request.session.work.drafts
                .single()
                .appearance.font
        return EffectObservation(7, FontObservation(font, availability))
    }

    private fun plan(
        request: ApplyRequest,
        document: StudioDocument,
        effects: EffectObservation,
    ): ApplyPreflight = StudioApplyPreflight.plan(request, document, context(request.session), effects)

    private fun context(session: StudioSessionRecord): ApplyContext =
        ApplyContext(
            AcceptanceObservation(AuthorityStatus.READY, TargetObservation(session.target, 1, TargetPresence.ACTIVE)),
            StudioList(emptyList()),
        )

    private fun integer(value: Int): FieldValue = FieldValue.Present(RawValue.Integer(value))
}
