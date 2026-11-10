package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioCatalogPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioRuntimeHandoffTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `every participant receives the runtime captured before the first effect`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.effect(EffectOwner.SYSTEM).beforePrepare = {
                    assertEquals(
                        listOf(
                            EffectOwner.SYSTEM,
                            EffectOwner.ACCENT,
                            EffectOwner.FONT,
                            EffectOwner.SYNTAX,
                            EffectOwner.VCS,
                            EffectOwner.GLOW,
                            EffectOwner.WORKSPACE,
                        ),
                        fixture.acquiredEffects.single().toList(),
                    )
                    assertSame(fixture.leases.single(), fixture.effect(EffectOwner.SYSTEM).runtimes.single())
                }

                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.submit(fixture.allOwnersRequest()))

                val lease = fixture.leases.single()
                for (effect in fixture.effects) assertSame(lease, effect.runtimes.single())
                val workspaceInput = fixture.effect(EffectOwner.WORKSPACE).inputs.single()
                val workspaceField =
                    fixture.initial.shared.workspace.records
                        .first { it.value is RawValue.BooleanValue }
                assertEquals(listOf(FieldAddress(workspaceField.key)), workspaceInput.workspaceFields)
                val glow = fixture.effect(EffectOwner.GLOW).inputs.single()
                val expected =
                    glow.candidate.currents.global.appearance.glow.records
                        .map { FieldAddress(it.key) }
                assertEquals(
                    expected,
                    glow.glowSelection.fields.toList(),
                )
            }
        }

    @Test
    fun `catalog acceptance acquires a revision lease with no native effects`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val record = sessionRecord(fixture.initial)
                val staged =
                    assertIs<CatalogStage.Staged>(
                        StudioCatalogPolicy.stage(
                            fixture.initial,
                            record.work,
                            CatalogCommand.Create(record.work.selected, ProfileId("saved"), "Saved"),
                        ),
                    )
                val request = fixture.request().copy(session = record.copy(work = staged.work))

                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.submit(request))

                assertTrue(fixture.acquiredEffects.single().isEmpty())
                assertEquals(1, fixture.leases.size)
                assertTrue(fixture.effects.all { it.runtimes.isEmpty() && it.writes == 0 })
            }
        }

    @Test
    fun `reacquired operations receive the resumed lease and retain it for cleanup retries`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.failAfter = true
                glow.failRestore = true
                assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.submit(fixture.request()))
                val initialLease = fixture.leases.single()
                val resumed = StudioApplyCoordinator(fixture.store, fixture, fixture.effects, fixture.mutex)

                assertIs<AcceptanceResult.RecoveryRequired>(resumed.retryRecovery("accept"))

                val recoveryLease = fixture.leases.last()
                assertNotSame(initialLease, recoveryLease)
                assertEquals(2, glow.runtimes.size)
                assertSame(initialLease, glow.runtimes[0])
                assertSame(recoveryLease, glow.runtimes[1])
                val applied = glow.inputs.first().glowSelection
                val recovered = glow.inputs.last().glowSelection
                assertTrue(applied.fields.isNotEmpty())
                assertTrue(recovered.fields.isEmpty())
                assertTrue(recovered.dependencies.isEmpty())
                glow.failRestore = false
                assertIs<AcceptanceResult.NotAccepted>(resumed.retryRecovery("accept"))
                assertEquals(2, fixture.leases.size)
                assertEquals(2, glow.runtimes.size)
                assertEquals(1, glow.writes)
                assertEquals(0, glow.value)
            }
        }
}
