package dev.ayuislands.accent.toolbar

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentResolutionChain
import dev.ayuislands.accent.AccentResolutionChainBuilder
import dev.ayuislands.accent.AccentResolutionStep
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.StepOutcome
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.ImmediateExecutionResult
import dev.ayuislands.studio.session.StudioQuickCommand
import dev.ayuislands.studio.ui.StudioCommandFeedback
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Job

/** Native view tests inspect requests; the real coordinator/field merge is covered by entry-point parity tests. */
internal class QuickSwitcherFixture : AutoCloseable {
    val state = AyuIslandsState()
    val mappings = AccentMappingsState()
    val project = mockk<Project>(relaxed = true)
    val service = mockk<StudioService>()
    val target = TargetObservation(StudioTarget.Project(StudioProjectKey("/work/A")), 7, TargetPresence.ACTIVE)
    var context: AccentContext? = AccentContext.Ayu(AyuVariant.MIRAGE)
    var licensed = true
    var available = true
    var hex = "#FFCC66"
    var source = AccentResolver.Source.GLOBAL
    val requests = mutableListOf<Pair<TargetObservation, StudioQuickCommand>>()
    val completions = mutableListOf<(ImmediateExecutionResult) -> Unit>()

    init {
        mockkObject(StudioService.Companion)
        every { StudioService.getInstance() } returns service
        every { service.capture(any()) } answers { if (available) target else null }
        every { service.view(any()) } answers
            { if (available) StudioFixtures.legacyView(state, mappings = mappings) else null }
        every { service.submitQuick(any(), any(), any()) } answers {
            requests += firstArg<TargetObservation>() to secondArg<StudioQuickCommand>()
            completions += thirdArg<(ImmediateExecutionResult) -> Unit>()
            Job().apply { complete() }
        }
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns project
        mockkObject(AccentContext.Companion)
        every { AccentContext.detectQuickSwitcher() } answers { context }
        every { AccentContext.detect() } answers { context }
        every { AccentContext.isQuickSwitcherActive() } answers { context != null }
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } answers { licensed }
        mockkObject(AccentResolutionChainBuilder)
        every { AccentResolutionChainBuilder.resolve(any(), any(), any()) } answers { chain() }
        mockkObject(StudioCommandFeedback)
        every { StudioCommandFeedback.show(any(), any(), any()) } answers {
            val result = secondArg<ImmediateExecutionResult>()
            if (result is ImmediateExecutionResult.Complete &&
                (
                    result.acceptance is dev.ayuislands.studio.model.AcceptanceResult.Accepted ||
                        result.acceptance == dev.ayuislands.studio.model.AcceptanceResult.NoChanges
                )
            ) {
                thirdArg<() -> Unit>().invoke()
            }
        }
    }

    fun chain(): AccentResolutionChain {
        val winner = AccentResolutionStep(source, hex, StepOutcome.WON, "Captured test target")
        return AccentResolutionChain(listOf(winner), winner, null)
    }

    override fun close() = unmockkAll()
}
