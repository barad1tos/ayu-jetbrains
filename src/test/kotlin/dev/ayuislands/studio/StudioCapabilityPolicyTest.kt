package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.CapabilitySnapshot
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioCapabilityPolicyTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `existing blocking limitations still exclude unsupported owners`() {
        StudioApplyFixture(directory).use { fixture ->
            val request = fixture.request()
            val limitation = EffectLimitation(EffectOwner.GLOW, LimitationReason.UNSUPPORTED_PLATFORM)
            val observed = sessionInputs(fixture.initial, sessionState(fixture.initial)).context
            val context = observed.copy(capabilities = CapabilitySnapshot(2, StudioList(listOf(limitation))))

            val plan =
                assertIs<AcceptancePlan.Commit>(
                    assertIs<AcceptancePlanResult.Ready>(
                        StudioAcceptancePlanner.plan(request, fixture.initial, context),
                    ).plan,
                )

            assertTrue(limitation.blocksEffect)
            assertFalse(EffectOwner.GLOW in plan.effects)
            assertEquals(listOf(limitation), plan.limitations)
        }
    }

    @Test
    fun `advisory limitation round trips with its participating owner in the pending journal`() {
        val document = sessionDocument()
        val advisory = EffectLimitation(EffectOwner.GLOW, LimitationReason.PRO_REQUIRED, blocksEffect = false)
        val recovery =
            RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.GLOW)), StudioList(listOf(advisory)))
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            assertEquals(StoreLoad.Empty, store.load())
            store.preserveSource(document.header.migration)
            assertIs<PrepareResult.Prepared>(store.prepare(-1, document, recovery))
        }

        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val pending = assertIs<StoreLoad.Recovering>(reopened.load())
            assertEquals(recovery, pending.journal.recovery)
            assertEquals(document, pending.journal.candidate)
        }
    }
}
