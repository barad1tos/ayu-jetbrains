package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.FontObservation
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.QuickCommandPreparation
import dev.ayuislands.studio.session.SessionEffects
import dev.ayuislands.studio.session.SessionLifecycle
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioQuickCommand
import dev.ayuislands.studio.session.StudioQuickCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioFontAdmissionTest {
    @Test
    fun `Settings submits unresolved font facts to authoritative async admission`() {
        val document = sessionDocument()
        val state = sessionState(document)
        val record = requireNotNull(state.record)
        val work =
            StudioDraftPolicy.select(
                record.work,
                document.catalog.first(),
                DraftId("profile"),
                document.currents.global,
            )
        val selected = state.copy(record = record.copy(work = work))
        val observed = sessionInputs(document, selected)
        val inputs =
            observed.copy(
                context =
                    observed.context.copy(
                        font =
                            FontObservation(
                                work.drafts
                                    .single { it.id == work.selected }
                                    .appearance.font,
                                FontAvailability.UNCONFIRMED,
                            ),
                    ),
            )

        val transition = SessionLifecycle.accept(selected, "accept", inputs)

        assertIs<SessionPhase.Processing>(transition.state.phase)
        assertIs<SessionCommand.Submit>(transition.commands.single())
        assertEquals(FontAvailability.UNCONFIRMED, inputs.context.font.availability)
    }

    @Test
    fun `Quick Switcher submits unresolved font facts without claiming availability`() {
        val document = sessionDocument()
        val state = sessionState(document)
        val observed = sessionInputs(document, state)
        val inputs =
            observed.copy(
                context =
                    observed.context.copy(
                        font =
                            FontObservation(
                                document.catalog
                                    .first()
                                    .appearance.font,
                                FontAvailability.UNCONFIRMED,
                            ),
                    ),
            )

        val prepared =
            StudioQuickCommands.prepare(
                StudioQuickCommand.Profile(document.catalog.first().id, DraftId("profile")),
                state,
                inputs,
                "quick",
            )

        assertIs<QuickCommandPreparation.Ready>(prepared)
        assertEquals(FontAvailability.UNCONFIRMED, inputs.context.font.availability)
    }

    @Test
    fun `a confirmed missing font remains a local blocker in both entry points`() {
        val document = sessionDocument()
        val state = sessionState(document)
        val record = requireNotNull(state.record)
        val profile = document.catalog.first()
        val observed = sessionInputs(document, state)
        val inputs =
            observed.copy(
                context =
                    observed.context.copy(
                        font = FontObservation(profile.appearance.font, FontAvailability.MISSING_CURATED),
                    ),
            )
        val work = StudioDraftPolicy.select(record.work, profile, DraftId("profile"), document.currents.global)
        val selected = state.copy(record = record.copy(work = work))

        val settings = SessionLifecycle.accept(selected, "accept", inputs)
        val quick =
            StudioQuickCommands.prepare(
                StudioQuickCommand.Profile(profile.id, DraftId("profile")),
                state,
                inputs,
                "quick",
            )

        assertTrue(settings.commands.isEmpty())
        assertEquals(
            listOf(AcceptanceBlock.FONT_INSTALL),
            assertIs<SessionNotice.Blocked>(settings.state.notice).reasons,
        )
        assertEquals(listOf(AcceptanceBlock.FONT_INSTALL), assertIs<QuickCommandPreparation.Blocked>(quick).reasons)
    }

    @Test
    fun `authoritative unconfirmed font result still stops the accepted operation before native effects`() {
        val document = sessionDocument()
        val state = sessionState(document)
        val record = requireNotNull(state.record)
        val profile = document.catalog.first()
        val work = StudioDraftPolicy.select(record.work, profile, DraftId("profile"), document.currents.global)
        val selected = state.copy(record = record.copy(work = work))
        val observed = sessionInputs(document, selected)
        val inputs =
            SessionInputs(
                document,
                observed.context.copy(font = FontObservation(profile.appearance.font, FontAvailability.AVAILABLE)),
            )
        val submitted = SessionLifecycle.accept(selected, "accept", inputs)

        val stopped =
            SessionEffects.apply(
                submitted.state,
                SessionEffect.PreflightCompleted(
                    AcceptancePlanResult.Blocked(StudioList(listOf(AcceptanceBlock.FONT_REFRESH))),
                ),
                inputs,
            )

        assertEquals(SessionPhase.Editing, stopped.state.phase)
        assertEquals(
            listOf(AcceptanceBlock.FONT_REFRESH),
            assertIs<SessionNotice.Blocked>(stopped.state.notice).reasons,
        )
        assertTrue(stopped.commands.isEmpty())
    }
}
