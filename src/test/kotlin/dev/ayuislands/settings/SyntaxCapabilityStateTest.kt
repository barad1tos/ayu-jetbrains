package dev.ayuislands.settings

import dev.ayuislands.syntax.PrimitiveCategory.KEYWORD
import dev.ayuislands.syntax.PrimitiveCategory.OPERATOR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyntaxCapabilityStateTest {
    @Test
    fun `selecting an uncached language starts one generation and hides controls`() {
        val transition = reduce(SyntaxCapabilityModel(), SyntaxCapabilityEvent.SelectLanguage("Swift"))

        val checking = assertIs<SyntaxCapabilityState.Checking>(transition.model.state)
        assertEquals("Swift", checking.languageId)
        assertEquals(1, checking.generation)
        assertTrue(transition.model.visibleCells.isEmpty())
        assertEquals(
            listOf(
                SyntaxCapabilityEffect.CancelProbe,
                SyntaxCapabilityEffect.StartProbe("Swift", 1),
                SyntaxCapabilityEffect.Render,
            ),
            transition.effects,
        )
    }

    @Test
    fun `current confirmation becomes visible and is the only cached outcome`() {
        val checking = selected("Swift")
        val evidence = swiftEvidence()

        val transition =
            reduce(
                checking,
                SyntaxCapabilityEvent.ProbeConfirmed("Swift", 1, evidence),
            )

        assertIs<SyntaxCapabilityState.Confirmed>(transition.model.state)
        assertEquals(setOf(KEYWORD), transition.model.visibleCells)
        assertEquals(evidence, transition.model.confirmedCache["Swift"])
        assertEquals(listOf(SyntaxCapabilityEffect.Render), transition.effects)
    }

    @Test
    fun `stale completion cannot expose rows for a newly selected language`() {
        val swift = selected("Swift")
        val kotlin = reduce(swift, SyntaxCapabilityEvent.SelectLanguage("Kotlin")).model

        val afterStale =
            reduce(
                kotlin,
                SyntaxCapabilityEvent.ProbeConfirmed("Swift", 1, swiftEvidence()),
            )

        assertEquals(kotlin, afterStale.model)
        assertTrue(afterStale.effects.isEmpty())
        val checking = assertIs<SyntaxCapabilityState.Checking>(afterStale.model.state)
        assertEquals("Kotlin", checking.languageId)
        assertEquals(2, checking.generation)
    }

    @Test
    fun `missing plugin exposes exact recovery only and retry is not cached`() {
        val checking = selected("Swift")
        val unavailable =
            reduce(
                checking,
                SyntaxCapabilityEvent.ProbeMissingPlugin("Swift", 1, PluginRecovery()),
            )

        val state = assertIs<SyntaxCapabilityState.PluginUnavailable>(unavailable.model.state)
        assertEquals(PLUGIN_INSTALL_INSTRUCTION, state.recovery.instruction)
        assertTrue(unavailable.model.visibleCells.isEmpty())
        assertTrue(unavailable.model.confirmedCache.isEmpty())

        val retry = reduce(unavailable.model, SyntaxCapabilityEvent.Retry)
        val retried = assertIs<SyntaxCapabilityState.Checking>(retry.model.state)
        assertEquals(2, retried.generation)
        assertEquals(
            listOf(
                SyntaxCapabilityEffect.CancelProbe,
                SyntaxCapabilityEffect.StartProbe("Swift", 2),
                SyntaxCapabilityEffect.Render,
            ),
            retry.effects,
        )
    }

    @Test
    fun `temporary failure hides controls while mismatch exposes confirmed subset`() {
        val checking = selected("Swift")
        val deferred =
            reduce(
                checking,
                SyntaxCapabilityEvent.ProbeDeferred("Swift", 1, "Indexing in progress"),
            )
        assertIs<SyntaxCapabilityState.TemporarilyUnavailable>(deferred.model.state)
        assertTrue(deferred.model.visibleCells.isEmpty())
        assertTrue(deferred.model.confirmedCache.isEmpty())

        val mismatch =
            reduce(
                checking,
                SyntaxCapabilityEvent.ProbeMismatch(
                    languageId = "Swift",
                    generation = 1,
                    confirmedCells = setOf(KEYWORD),
                    mismatches = listOf(CapabilityMismatch(OPERATOR, "No representative span")),
                ),
            )
        assertIs<SyntaxCapabilityState.Incompatible>(mismatch.model.state)
        assertEquals(setOf(KEYWORD), mismatch.model.visibleCells)
        assertTrue(mismatch.model.confirmedCache.isEmpty())
    }

    @Test
    fun `selecting a positively cached language renders it without probing`() {
        val swift = confirmed("Swift", swiftEvidence())
        val kotlin = reduce(swift, SyntaxCapabilityEvent.SelectLanguage("Kotlin")).model

        val cached = reduce(kotlin, SyntaxCapabilityEvent.SelectLanguage("Swift"))

        assertIs<SyntaxCapabilityState.Confirmed>(cached.model.state)
        assertEquals(setOf(KEYWORD), cached.model.visibleCells)
        assertEquals(
            listOf(SyntaxCapabilityEffect.CancelProbe, SyntaxCapabilityEffect.Render),
            cached.effects,
        )
    }

    @Test
    fun `semantic recovery invalidates only selected cache after returning`() {
        val swift = confirmed("Swift", swiftEvidence(hasConditionalAbsence = true))
        val withKotlinCache =
            swift.copy(
                confirmedCache = swift.confirmedCache + ("Kotlin" to kotlinEvidence()),
            )

        val opened = reduce(withKotlinCache, SyntaxCapabilityEvent.OpenHighlightingSettings)
        assertTrue(opened.model.isHighlightingRecheckArmed)
        assertEquals(listOf(SyntaxCapabilityEffect.OpenHighlightingSettings), opened.effects)

        val returned = reduce(opened.model, SyntaxCapabilityEvent.RecheckHighlighting)
        val checking = assertIs<SyntaxCapabilityState.Checking>(returned.model.state)
        assertEquals(2, checking.generation)
        assertFalse(returned.model.confirmedCache.containsKey("Swift"))
        assertEquals(kotlinEvidence(), returned.model.confirmedCache["Kotlin"])
        assertFalse(returned.model.isHighlightingRecheckArmed)
        assertEquals(
            listOf(
                SyntaxCapabilityEffect.CancelProbe,
                SyntaxCapabilityEffect.StartProbe("Swift", 2),
                SyntaxCapabilityEffect.Render,
            ),
            returned.effects,
        )
    }

    @Test
    fun `highlighting recovery stays inert without a conditional absence`() {
        val confirmed = confirmed("Swift", swiftEvidence())

        val transition = reduce(confirmed, SyntaxCapabilityEvent.OpenHighlightingSettings)

        assertEquals(confirmed, transition.model)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `plugin recovery navigation does not change state`() {
        val unavailable =
            reduce(
                selected("Swift"),
                SyntaxCapabilityEvent.ProbeMissingPlugin("Swift", 1, PluginRecovery()),
            ).model

        val transition = reduce(unavailable, SyntaxCapabilityEvent.OpenPluginSettings)

        assertEquals(unavailable, transition.model)
        assertEquals(listOf(SyntaxCapabilityEffect.OpenPluginSettings(null)), transition.effects)
    }

    @Test
    fun `closing cancels work clears cache and makes later events inert`() {
        val confirmed = confirmed("Swift", swiftEvidence())

        val closed = reduce(confirmed, SyntaxCapabilityEvent.CloseSettings)

        assertTrue(closed.model.isClosed)
        assertNull(closed.model.state)
        assertTrue(closed.model.confirmedCache.isEmpty())
        assertEquals(
            listOf(SyntaxCapabilityEffect.CancelProbe, SyntaxCapabilityEffect.ClearRenderer),
            closed.effects,
        )
        val afterClose = reduce(closed.model, SyntaxCapabilityEvent.SelectLanguage("Kotlin"))
        assertEquals(closed.model, afterClose.model)
        assertTrue(afterClose.effects.isEmpty())
    }

    private fun selected(languageId: String): SyntaxCapabilityModel =
        reduce(SyntaxCapabilityModel(), SyntaxCapabilityEvent.SelectLanguage(languageId)).model

    private fun confirmed(
        languageId: String,
        evidence: SyntaxCapabilityEvidence,
    ): SyntaxCapabilityModel {
        val checking = selected(languageId)
        return reduce(
            checking,
            SyntaxCapabilityEvent.ProbeConfirmed(languageId, 1, evidence),
        ).model
    }

    private fun swiftEvidence(hasConditionalAbsence: Boolean = false): SyntaxCapabilityEvidence =
        SyntaxCapabilityEvidence(
            languageId = "Swift",
            confirmedCells = setOf(KEYWORD),
            conditionalAbsences =
                if (hasConditionalAbsence) {
                    listOf(ConditionalAbsence(OPERATOR, "Semantic highlighting is disabled"))
                } else {
                    emptyList()
                },
        )

    private fun kotlinEvidence(): SyntaxCapabilityEvidence =
        SyntaxCapabilityEvidence(
            languageId = "Kotlin",
            confirmedCells = setOf(KEYWORD, OPERATOR),
        )

    private fun reduce(
        model: SyntaxCapabilityModel,
        event: SyntaxCapabilityEvent,
    ): SyntaxCapabilityTransition = SyntaxCapabilityReducer.reduce(model, event)
}
