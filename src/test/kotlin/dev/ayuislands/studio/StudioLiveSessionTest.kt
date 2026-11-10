package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioLiveSessionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `command admission observes settings edited while its draft flush was in flight`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val original = fixture.request()
                val request =
                    original.copy(
                        session = original.session.copy(id = "popup"),
                        origin = AcceptanceOrigin.QUICK_SWITCHER,
                    )
                fixture.beforeObserve = {
                    val dirty = requireNotNull(editedSession(fixture.initial).record)
                    fixture.context =
                        sessionInputs(fixture.initial, sessionState(fixture.initial)).context.copy(
                            liveSessions = StudioList(listOf(dirty)),
                        )
                }

                val result = fixture.coordinator.submit(request)

                assertEquals(
                    listOf<AcceptanceReview>(AcceptanceReview.LiveSession(original.session.id)),
                    assertIs<AcceptanceResult.ReviewRequired>(result).items,
                )
                assertEquals(listOf("flush"), fixture.events)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertEquals(listOf(request.session), fixture.document().sessions)
            }
        }

    @Test
    fun `quick command sees dirty settings before deferred draft persistence`() {
        val document = sessionDocument()
        val popup = sessionState(document)
        val request = AcceptanceRequest(requireNotNull(popup.record), "quick", 1, AcceptanceOrigin.QUICK_SWITCHER)
        val dirty = requireNotNull(editedSession(document).record).copy(id = "settings")
        val context = sessionInputs(document, popup).context.copy(liveSessions = StudioList(listOf(dirty)))

        val result = StudioAcceptancePlanner.plan(request, document, context)

        assertEquals(
            listOf<AcceptanceReview>(AcceptanceReview.LiveSession(dirty.id)),
            assertIs<AcceptancePlanResult.ReviewRequired>(result).items,
        )
        assertEquals(emptyList(), document.sessions)
    }

    @Test
    fun `live retirement supersedes stale durable dirty settings`() {
        val document = sessionDocument()
        val popup = sessionState(document)
        val dirty = requireNotNull(editedSession(document).record).copy(id = "settings")
        val stored = document.copy(sessions = StudioList(listOf(dirty)))
        val retired = dirty.copy(availability = SessionAvailability.RETIRED)
        val context = sessionInputs(document, popup).context.copy(liveSessions = StudioList(listOf(retired)))
        val request = AcceptanceRequest(requireNotNull(popup.record), "quick", 1, AcceptanceOrigin.QUICK_SWITCHER)

        val result = StudioAcceptancePlanner.plan(request, stored, context)

        assertEquals(AcceptancePlan.Unchanged, assertIs<AcceptancePlanResult.Ready>(result).plan)
        assertEquals(listOf(dirty), stored.sessions)
    }

    @Test
    fun `live dirty snapshot supersedes a clean durable snapshot once`() {
        val document = sessionDocument()
        val popup = sessionState(document)
        val clean = sessionRecord(document).copy(id = "settings")
        val dirty = requireNotNull(editedSession(document).record).copy(id = clean.id)
        val stored = document.copy(sessions = StudioList(listOf(clean)))
        val context = sessionInputs(document, popup).context.copy(liveSessions = StudioList(listOf(dirty)))
        val request = AcceptanceRequest(requireNotNull(popup.record), "quick", 1, AcceptanceOrigin.QUICK_SWITCHER)

        val result = StudioAcceptancePlanner.plan(request, stored, context)

        assertEquals(
            listOf<AcceptanceReview>(AcceptanceReview.LiveSession(dirty.id)),
            assertIs<AcceptancePlanResult.ReviewRequired>(result).items,
        )
    }
}
