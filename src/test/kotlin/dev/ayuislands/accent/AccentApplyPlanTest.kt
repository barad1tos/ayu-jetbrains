package dev.ayuislands.accent

import dev.ayuislands.accent.AccentApplyStep.ApplyAlwaysOnEditorKeys
import dev.ayuislands.accent.AccentApplyStep.ApplyAlwaysOnUiKeys
import dev.ayuislands.accent.AccentApplyStep.ApplyElements
import dev.ayuislands.accent.AccentApplyStep.ApplyTabUnderline
import dev.ayuislands.accent.AccentApplyStep.ClearUiAndExtensions
import dev.ayuislands.accent.AccentApplyStep.MarkApplyClean
import dev.ayuislands.accent.AccentApplyStep.NotifyComponentTrees
import dev.ayuislands.accent.AccentApplyStep.PublishAccentChanged
import dev.ayuislands.accent.AccentApplyStep.RepaintWindows
import dev.ayuislands.accent.AccentApplyStep.RevertAlwaysOnEditorKeys
import dev.ayuislands.accent.AccentApplyStep.RevertCodeGlanceProViewport
import dev.ayuislands.accent.AccentApplyStep.RevertIndentRainbow
import dev.ayuislands.accent.AccentApplyStep.SyncCodeGlanceProViewport
import dev.ayuislands.accent.AccentApplyStep.SyncIndentRainbow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure list-equality locks on the accent apply/revert step ordering. This is
 * the behavioral replacement for the former source-regex symmetry test: the
 * plan below is what [AccentApplicator] actually executes (runner order
 * fidelity is locked by `AccentApplyPlanRunnerTest`, and the end-to-end apply
 * and revert tests confirm the applicator feeds these plans through the
 * runner), so together these locks are stronger than grepping call order out
 * of the source text.
 */
class AccentApplyPlanTest {
    @Test
    fun `apply plan for Ayu context runs every step in locked order`() {
        assertEquals(
            listOf(
                ApplyAlwaysOnUiKeys,
                ApplyElements,
                SyncIndentRainbow,
                SyncCodeGlanceProViewport,
                ApplyAlwaysOnEditorKeys,
                ApplyTabUnderline,
                NotifyComponentTrees,
                RepaintWindows,
                MarkApplyClean,
                PublishAccentChanged,
            ),
            applyPlanFor(AccentContext.Ayu(AyuVariant.DARK)).steps,
        )
    }

    @Test
    fun `apply plan for External context schedules elements and tab underline like Ayu`() {
        // Elements and underline gate on context SHAPE only; the External
        // chrome-tint allowance is enforced inside the applicator workers
        // with revert symmetry, so the plan is identical to the Ayu plan.
        assertEquals(
            listOf(
                ApplyAlwaysOnUiKeys,
                ApplyElements,
                SyncIndentRainbow,
                SyncCodeGlanceProViewport,
                ApplyAlwaysOnEditorKeys,
                ApplyTabUnderline,
                NotifyComponentTrees,
                RepaintWindows,
                MarkApplyClean,
                PublishAccentChanged,
            ),
            applyPlanFor(AccentContext.External).steps,
        )
    }

    @Test
    fun `apply plan without context also skips indent rainbow but keeps code glance sync`() {
        assertEquals(
            listOf(
                ApplyAlwaysOnUiKeys,
                SyncCodeGlanceProViewport,
                ApplyAlwaysOnEditorKeys,
                NotifyComponentTrees,
                RepaintWindows,
                MarkApplyClean,
                PublishAccentChanged,
            ),
            applyPlanFor(null).steps,
        )
    }

    @Test
    fun `revert plan clears ui and editor keys then integrations then notify`() {
        assertEquals(
            listOf(
                ClearUiAndExtensions,
                RevertAlwaysOnEditorKeys,
                RevertIndentRainbow,
                RevertCodeGlanceProViewport,
                NotifyComponentTrees,
            ),
            revertPlan().steps,
        )
    }

    @Test
    fun `apply plans always abort on first failure and revert plans always continue`() {
        // The policy pairing is structural: a torn apply base must not keep
        // painting, while a revert must unwind every surface independently.
        val contexts = listOf(AccentContext.Ayu(AyuVariant.DARK), AccentContext.External, null)
        for (context in contexts) {
            assertEquals(
                AccentApplyFailurePolicy.AbortOnFirstFailure,
                applyPlanFor(context).policy,
                "context=$context",
            )
        }
        assertEquals(AccentApplyFailurePolicy.ContinuePerStep, revertPlan().policy)
    }

    @Test
    fun `apply and revert order integrations identically`() {
        val applyIntegrations =
            applyPlanFor(AccentContext.Ayu(AyuVariant.DARK))
                .steps
                .mapNotNull(::integrationTokenOf)
        val revertIntegrations = revertPlan().steps.mapNotNull(::integrationTokenOf)
        assertEquals(listOf("IndentRainbow", "CodeGlancePro", "Notify"), applyIntegrations)
        assertEquals(applyIntegrations, revertIntegrations)
    }

    @Test
    fun `every integration apply step has a revert counterpart in the revert plan`() {
        val revertSteps = revertPlan().steps
        assertTrue(RevertIndentRainbow in revertSteps)
        assertTrue(RevertCodeGlanceProViewport in revertSteps)
        assertTrue(RevertAlwaysOnEditorKeys in revertSteps)
    }

    @Test
    fun `revert plan never repaints windows, marks clean, or publishes`() {
        val revertSteps = revertPlan().steps
        assertFalse(RepaintWindows in revertSteps)
        assertFalse(MarkApplyClean in revertSteps)
        assertFalse(PublishAccentChanged in revertSteps)
    }

    @Test
    fun `mark clean immediately precedes accent publish in every apply plan`() {
        val contexts = listOf(AccentContext.Ayu(AyuVariant.DARK), AccentContext.External, null)
        for (context in contexts) {
            val steps = applyPlanFor(context).steps
            assertEquals(MarkApplyClean, steps[steps.size - 2], "context=$context")
            assertEquals(PublishAccentChanged, steps.last(), "context=$context")
        }
    }

    @Test
    fun `outcome is applied when no step failed`() {
        val hex = requireNotNull(AccentHex.of("#FFCC66"))
        assertEquals(
            AccentApplyOutcome.Applied(hex),
            AccentApplyOutcome.of(hex, emptyList()),
        )
    }

    @Test
    fun `outcome is torn carrying the failures when any step failed`() {
        val hex = requireNotNull(AccentHex.of("#FFCC66"))
        val failure = AccentApplyStepFailure(ApplyAlwaysOnUiKeys, IllegalStateException("boom"))
        val outcome = AccentApplyOutcome.of(hex, listOf(failure))
        assertIs<AccentApplyOutcome.Torn>(outcome)
        assertEquals(listOf(failure), outcome.failures)
        assertEquals(hex, outcome.accentHex)
    }

    @Test
    fun `torn outcome refuses an empty failure list by construction`() {
        // AccentApplicator logs `failures.first()` on Torn — an empty-failures
        // Torn would crash the logging path, so the type rejects it outright.
        val hex = requireNotNull(AccentHex.of("#FFCC66"))
        assertFailsWith<IllegalArgumentException> {
            AccentApplyOutcome.Torn(hex, emptyList())
        }
    }

    private fun integrationTokenOf(step: AccentApplyStep): String? =
        when (step) {
            SyncIndentRainbow, RevertIndentRainbow -> "IndentRainbow"
            SyncCodeGlanceProViewport, RevertCodeGlanceProViewport -> "CodeGlancePro"
            NotifyComponentTrees -> "Notify"
            else -> null
        }
}
