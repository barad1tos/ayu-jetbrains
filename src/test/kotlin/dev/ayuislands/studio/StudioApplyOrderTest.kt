package dev.ayuislands.studio

import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.persistence.StoreLoad
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioApplyOrderTest {
    private val owners =
        listOf(
            EffectOwner.SYSTEM,
            EffectOwner.ACCENT,
            EffectOwner.FONT,
            EffectOwner.SYNTAX,
            EffectOwner.VCS,
            EffectOwner.GLOW,
            EffectOwner.WORKSPACE,
        )

    @TempDir lateinit var directory: Path

    @Test
    fun `every owner fails before and after its setter without accepting a partial appearance`(): Unit =
        runBlocking {
            for (owner in owners) {
                for (after in listOf(false, true)) {
                    StudioApplyFixture(directory.resolve("$owner-$after")).use { fixture ->
                        val effect = fixture.effect(owner)
                        effect.failBefore = !after
                        effect.failAfter = after
                        val request = fixture.allOwnersRequest()
                        assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(request))
                        assertEquals(fixture.initial.currents, fixture.document().currents)
                        assertEquals(fixture.initial.shared, fixture.document().shared)
                        assertEquals(request.session, fixture.document().sessions.single())
                        assertTrue(fixture.effects.all { it.value == 0 && it.accepts == 0 })
                        val visited = owners.takeWhile { it != owner } + owner
                        assertEquals(visited.map { "apply-$it" }, fixture.events.filter { it.startsWith("apply-") })
                        assertEquals(
                            visited.reversed().map { "restore-$it" },
                            fixture.events.filter { it.startsWith("restore-") },
                        )
                        assertIs<StoreLoad.Ready>(fixture.store.load())
                    }
                }
            }
        }

    @Test
    fun `lease invalidation after each owner aborts before the next owner or durable decision`(): Unit =
        runBlocking {
            for (owner in owners) {
                StudioApplyFixture(directory.resolve(owner.name)).use { fixture ->
                    fixture.effect(owner).afterApply = { fixture.isLeaseValid = false }
                    assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(fixture.allOwnersRequest()))
                    val visited = owners.takeWhile { it != owner } + owner
                    assertEquals(visited.map { "apply-$it" }, fixture.events.filter { it.startsWith("apply-") })
                    assertEquals(fixture.initial.currents, fixture.document().currents)
                    assertEquals(fixture.initial.shared, fixture.document().shared)
                    assertTrue(fixture.effects.all { it.value == 0 && it.accepts == 0 })
                    assertIs<StoreLoad.Ready>(fixture.store.load())
                }
            }
        }

    @Test
    fun `accepted owner cleanup is idempotent when a later owner needs retry`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val syntax = fixture.effect(EffectOwner.SYNTAX)
                syntax.failAccept = true
                val request = fixture.allOwnersRequest()
                assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.submit(request))
                val accepted = fixture.document()
                val requested =
                    StudioAppearanceFields.fields(
                        request.session.work.drafts
                            .last()
                            .appearance,
                    )
                val actual = StudioAppearanceFields.fields(accepted.currents.global.appearance)
                assertEquals(requested.map { it.key to it.value }, actual.map { it.key to it.value })
                val initial =
                    StudioAppearanceFields
                        .fields(
                            fixture.initial.currents.global.appearance,
                        ).associateBy { it.key }
                for ((key, value, origin) in actual) {
                    val previous = initial.getValue(key)
                    if (previous.value == value) assertEquals(previous.origin, origin)
                }
                assertTrue(fixture.effects.all { it.value == 1 && it.writes == 1 })
                syntax.failAccept = false
                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.retryRecovery("all"))
                assertEquals(accepted, fixture.document())
                assertTrue(fixture.effects.all { it.accepts == 1 && it.writes == 1 })
                assertEquals(owners.map { "apply-$it" }, fixture.events.filter { it.startsWith("apply-") })
            }
        }

    @Test
    fun `older equal-valued write cannot cross a newer unresolved receipt on the same surface`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val shared = ApplyTestSurface("shared-scheme-field")
                val accent = fixture.effect(EffectOwner.ACCENT)
                val font = fixture.effect(EffectOwner.FONT)
                accent.surface = shared
                font.surface = shared
                font.failAfter = true
                font.failRestore = true
                assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.submit(fixture.allOwnersRequest()))
                assertEquals(1, shared.value, "Older restore must wait for the unresolved newer receipt")
                assertEquals(0, fixture.effect(EffectOwner.SYSTEM).value, "Independent restoration must continue")
                font.failRestore = false
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.retryRecovery("all"))
                assertEquals(0, shared.value)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }
}
