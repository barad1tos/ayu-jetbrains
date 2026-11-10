package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.CapabilitySnapshot
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.StudioDomain
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioAcceptanceMerge
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioNativeFacts
import dev.ayuislands.studio.session.StudioRuntimeLease
import dev.ayuislands.studio.session.StudioVcsParticipant
import dev.ayuislands.studio.sessionInputs
import dev.ayuislands.studio.sessionState
import dev.ayuislands.theme.EditorSchemeOverrides
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class StudioCapabilityCleanupTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @After
    fun clearOwnership() {
        SwingUtilities.invokeAndWait { EditorSchemeOverrides.reset() }
    }

    @Test
    fun `advisory Pro restriction reaches guarded native VCS cleanup and retains requested profile`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val source =
                    assertIs<EditorColorsSchemeImpl>(
                        EditorColorsManager.getInstance().getScheme(AyuVariant.MIRAGE.editorSchemeResource),
                    )
                val scheme = participantScheme(source)
                val baseline = ParticipantSnapshot(scheme)
                val originalColors = scheme.directlyDefinedColors.toMap()
                val host = ParticipantSchemeHost(listOf(scheme))
                val participant = StudioVcsParticipant(host, EmptyCoroutineContext)
                val settings =
                    AyuIslandsState().apply {
                        vcsColorEnabled = true
                        vcsDiffPreset = "CUSTOM"
                        vcsDiffIntensity = 67
                    }
                val owned = participant.prepare(participantInput(settings), participantRuntime())
                owned.apply()
                owned.accept()
                assertNotEquals(originalColors, scheme.directlyDefinedColors)
                StudioApplyFixture(temporary.newFolder().toPath()).use { fixture ->
                    val request = fixture.allOwnersRequest("free-cleanup")
                    val selected =
                        request.session.work.drafts
                            .single { it.id == request.session.work.selected }
                    val limitations =
                        StudioList(
                            listOf(EffectOwner.GLOW, EffectOwner.VCS, EffectOwner.WORKSPACE).map {
                                EffectLimitation(it, LimitationReason.PRO_REQUIRED, blocksEffect = false)
                            },
                        )
                    val observed = sessionInputs(fixture.initial, sessionState(fixture.initial)).context
                    val licensed = observed.copy(font = observed.font.copy(requested = selected.appearance.font))
                    val expected =
                        assertIs<AcceptancePlan.Commit>(
                            assertIs<AcceptancePlanResult.Ready>(
                                StudioAcceptancePlanner.plan(request, fixture.initial, licensed),
                            ).plan,
                        ).candidate.currents.global.appearance
                    fixture.context = licensed.copy(capabilities = CapabilitySnapshot(2, limitations))
                    val environment =
                        object : StudioApplyEnvironment by fixture {
                            override suspend fun prepareAcceptance(
                                request: AcceptanceRequest,
                                plan: AcceptancePlan.Commit,
                            ): StudioAcceptancePreparation {
                                val prepared =
                                    assertIs<StudioAcceptancePreparation.Prepared>(
                                        fixture.prepareAcceptance(request, plan),
                                    )
                                return prepared.copy(
                                    lease =
                                        object : StudioRuntimeLease by prepared.lease {
                                            override val native = StudioNativeFacts(false, participantProcess)
                                        },
                                )
                            }
                        }
                    val coordinator =
                        StudioApplyCoordinator(
                            fixture.store,
                            environment,
                            fixture.effects.filterNot { it.owner == EffectOwner.VCS } + participant,
                            fixture.mutex,
                        )

                    val accepted = assertIs<AcceptanceResult.Accepted>(coordinator.submit(request))

                    baseline.assertCells()
                    assertEquals(limitations, accepted.limitations)
                    val actual =
                        fixture
                            .document()
                            .currents.global.appearance
                    assertEquals(expected, actual)
                    assertPreservedIntent(selected.appearance, actual, fixture.initial.currents.global.appearance)
                    assertEquals(1, fixture.effect(EffectOwner.GLOW).accepts)
                    assertEquals(1, fixture.effect(EffectOwner.WORKSPACE).accepts)
                }
            }
        }
    }

    private fun assertPreservedIntent(
        requested: AppearanceSnapshot,
        actual: AppearanceSnapshot,
        previous: AppearanceSnapshot,
    ) {
        for (domain in StudioDomain.entries) {
            val wanted = StudioAcceptanceMerge.fields(requested, domain)
            val accepted = StudioAcceptanceMerge.fields(actual, domain)
            val original = StudioAcceptanceMerge.fields(previous, domain)
            assertEquals(
                wanted.records.map { it.key to it.value },
                accepted.records.map { it.key to it.value },
                domain.name,
            )
            assertEquals(wanted.extensions, accepted.extensions, domain.name)
            for ((key, value, origin) in accepted.records) {
                val before = original.records.single { it.key == key }
                if (value == before.value) assertEquals(before.origin, origin, key.name)
            }
        }
    }
}
