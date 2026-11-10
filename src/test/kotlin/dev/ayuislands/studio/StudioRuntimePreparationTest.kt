package dev.ayuislands.studio

import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioRuntimePreparationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `runtime preparation is journaled before its added effects and duplicate submission cannot repeat it`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val request = fixture.request()
                var preparations = 0
                val environment =
                    PreparedEnvironment(fixture) { observed, plan ->
                        preparations++
                        assertSame(request, observed)
                        assertIs<StoreLoad.Ready>(fixture.store.load())
                        assertTrue(fixture.effects.all { it.writes == 0 })
                        prepared(plan)
                    }
                val coordinator = StudioApplyCoordinator(fixture.store, environment, fixture.effects, fixture.mutex)
                for (owner in listOf(EffectOwner.ACCENT, EffectOwner.GLOW)) {
                    fixture.effect(owner).beforePrepare = {
                        val journal = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal
                        val input = fixture.effect(owner).inputs.single()
                        assertEquals(input.candidate, journal.candidate)
                        assertEquals(checkpoint(), journal.candidate.runtime.rotation)
                        assertEquals(request.transactionId, journal.token.transactionId)
                        assertEquals(listOf(EffectOwner.ACCENT, EffectOwner.GLOW), journal.recovery.owners.toList())
                    }
                }

                val accepted = assertIs<AcceptanceResult.Accepted>(coordinator.submit(request))

                assertEquals(accepted, coordinator.submit(request))
                assertEquals(1, preparations)
                assertEquals(checkpoint(), fixture.document().runtime.rotation)
                assertEquals(fixture.initial.runtime.legacy, fixture.document().runtime.legacy)
                assertEquals(1, fixture.effect(EffectOwner.ACCENT).writes)
                assertEquals(1, fixture.effect(EffectOwner.GLOW).writes)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `blocked preparation keeps the saved draft without acquiring resources or writing native effects`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val failure = ApplyFailure("runtime-unavailable")
                val environment =
                    object : StudioApplyEnvironment by fixture {
                        override suspend fun prepareAcceptance(
                            request: AcceptanceRequest,
                            plan: AcceptancePlan.Commit,
                        ): StudioAcceptancePreparation = StudioAcceptancePreparation.Blocked(failure)
                    }
                val coordinator = StudioApplyCoordinator(fixture.store, environment, fixture.effects, fixture.mutex)
                val request = fixture.request()

                assertEquals(AcceptanceResult.NotAccepted(failure), coordinator.submit(request))

                assertTrue(fixture.leases.isEmpty())
                assertTrue(fixture.effects.all { it.writes == 0 })
                assertEquals(request.session, fixture.document().sessions.single())
                assertEquals(fixture.initial.runtime, fixture.document().runtime)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `failed prepared effect restores the previous checkpoint and leaves the user draft intact`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val environment = PreparedEnvironment(fixture) { _, plan -> prepared(plan) }
                val coordinator = StudioApplyCoordinator(fixture.store, environment, fixture.effects, fixture.mutex)
                fixture.effect(EffectOwner.ACCENT).failAfter = true
                val request = fixture.request()

                assertIs<AcceptanceResult.NotAccepted>(coordinator.submit(request))

                assertEquals(fixture.initial.runtime, fixture.document().runtime)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertEquals(request.session, fixture.document().sessions.single())
                assertEquals(0, fixture.effect(EffectOwner.ACCENT).value)
                assertEquals(0, fixture.effect(EffectOwner.GLOW).writes)
                assertEquals(0, fixture.effect(EffectOwner.ACCENT).accepts)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    private fun prepared(plan: AcceptancePlan.Commit): AcceptancePlan.Commit =
        plan.copy(
            candidate = plan.candidate.copy(runtime = plan.candidate.runtime.copy(rotation = checkpoint())),
            effects = StudioList(listOf(EffectOwner.ACCENT, EffectOwner.GLOW)),
        )

    private fun checkpoint(): RotationRuntimeCheckpoint =
        RotationRuntimeCheckpoint(RuntimeOwner(StudioTarget.Global, 1), 2, 1)
}

private class PreparedEnvironment(
    private val delegate: StudioApplyEnvironment,
    private val prepare: (AcceptanceRequest, AcceptancePlan.Commit) -> AcceptancePlan.Commit,
) : StudioApplyEnvironment by delegate {
    override suspend fun prepareAcceptance(
        request: AcceptanceRequest,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation {
        val prepared = prepare(request, plan)
        return delegate.prepareAcceptance(request, prepared)
    }
}
