package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.session.IntegrationSurface
import dev.ayuislands.studio.session.StudioAccentResult
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioRuntimeLease
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioAccentCoordinatorTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `late owner failure restores the complete Accent journal without accepting candidate state`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                AccentParticipantFixture().use { native ->
                    val before = ParticipantSnapshot(native.scheme)
                    StudioApplyFixture(temporary.newFolder().toPath()).use { fixture ->
                        fixture.effect(EffectOwner.GLOW).apply {
                            beforePrepare = {
                                native.assertApplied()
                                val journal = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal
                                val accent = journal.receipts.filter { it.owner == EffectOwner.ACCENT }
                                assertTrue(accent.any { FontIdentityRules.canonicalId(it.identity.resource) != null })
                                assertTrue(
                                    accent.any { it.identity.resource == IntegrationSurface.CODE_GLANCE.resource },
                                )
                                assertTrue(accent.any { it.identity.field.startsWith("accent/ui/") })
                                assertTrue(accent.any { it.identity.field.startsWith("accent/chrome/") })
                                assertEquals(accent.size, accent.map { it.id }.distinct().size)
                            }
                            failAfter = true
                        }
                        val coordinator =
                            StudioApplyCoordinator(
                                fixture.store,
                                AccentCoordinatorEnvironment(fixture, native.runtime),
                                fixture.effects.map { effect ->
                                    if (effect.owner == EffectOwner.ACCENT) native.participant else effect
                                },
                                fixture.mutex,
                                EmptyCoroutineContext,
                            )

                        val result = coordinator.submit(accentRequest(fixture))

                        assertIs<AcceptanceResult.NotAccepted>(result)
                        assertEquals(1, fixture.effect(EffectOwner.GLOW).writes)
                        assertEquals(1, native.runtime.confirmations)
                        native.assertOriginal()
                        before.assertValues()
                        assertTrue(fixture.effects.all { it.accepts == 0 })
                        val accepted = assertIs<StoreLoad.Ready>(fixture.store.load()).document
                        assertEquals(fixture.initial.currents, accepted.currents)
                        assertEquals(fixture.initial.catalog, accepted.catalog)
                        assertEquals(fixture.initial.shared, accepted.shared)
                        assertEquals(fixture.initial.runtime, accepted.runtime)
                    }
                }
            }
        }
    }
}

private fun accentRequest(fixture: StudioApplyFixture): AcceptanceRequest {
    val request = fixture.allOwnersRequest("native-accent-abort")
    val work = request.session.work
    var draft = work.drafts.single { it.id == work.selected }
    val fields =
        mapOf(
            "mirageAccent" to RawValue.Text("#FFCC66"),
            "chromeTintingEnabled" to RawValue.BooleanValue(true),
            "chromeNavBar" to RawValue.BooleanValue(true),
            "chromeTintIntensity" to RawValue.Integer(30),
            "progressBar" to RawValue.BooleanValue(true),
            "caretRow" to RawValue.BooleanValue(true),
        )
    for ((name, value) in fields) {
        draft =
            StudioDraftPolicy.edit(
                draft,
                FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, name)),
                FieldValue.Present(value),
            )
    }
    val integration =
        fixture.initial.shared.plugins.records
            .single { it.key.name == "cgpIntegrationEnabled" }
    val shared =
        work.shared.filterNot { it is SharedIntent.Field && it.before.key == integration.key } +
            SharedIntent.Field(integration, integration.copy(value = RawValue.BooleanValue(true)))
    return request.copy(
        session =
            request.session.copy(
                work =
                    work.copy(
                        drafts = StudioList(work.drafts.map { if (it.id == draft.id) draft else it }),
                        shared = StudioList(shared),
                    ),
            ),
    )
}

/** The existing coordinator fixture owns admission; native identity enforcement has a separate real-lease suite. */
private class AccentCoordinatorEnvironment(
    private val delegate: StudioApplyEnvironment,
    private val runtime: StudioEffectRuntime,
) : StudioApplyEnvironment by delegate {
    override suspend fun prepareAcceptance(
        request: AcceptanceRequest,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation =
        when (val prepared = delegate.prepareAcceptance(request, plan)) {
            is StudioAcceptancePreparation.Prepared -> prepared.copy(lease = withNativeSurfaces(prepared.lease))
            is StudioAcceptancePreparation.Blocked -> prepared
        }

    private fun withNativeSurfaces(lease: StudioRuntimeLease): StudioRuntimeLease =
        object : StudioRuntimeLease by lease {
            override val native get() = runtime.native
            override val observation get() = runtime.observation
            override val accent get() = runtime.accent

            override suspend fun confirmAccent(result: StudioAccentResult) = runtime.confirmAccent(result)
        }
}
