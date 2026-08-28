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
        val transition = reduce(SyntaxCapabilityModel(), select("Swift"))

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
    fun `current confirmation becomes visible and is cached by profile identity`() {
        val checking = selected()
        val evidence = swiftEvidence()

        val transition =
            reduce(
                checking,
                SyntaxCapabilityEvent.ProbeConfirmed("Swift", 1, evidence),
            )

        assertIs<SyntaxCapabilityState.Confirmed>(transition.model.state)
        assertEquals(setOf(KEYWORD), transition.model.visibleCells)
        val cached = assertIs<SyntaxCapabilityState.Confirmed>(transition.model.terminalCache[key("Swift")])
        assertEquals(evidence, cached.evidence)
        assertEquals(listOf(SyntaxCapabilityEffect.Render), transition.effects)
    }

    @Test
    fun `stale completion cannot expose rows for a newly selected language`() {
        val swift = selected()
        val kotlin = reduce(swift, select("Kotlin")).model

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
    fun `unavailable support exposes recovery and retry starts a fresh probe`() {
        val checking = selected()
        val unavailable =
            reduce(
                checking,
                SyntaxCapabilityEvent.ProbeUnavailable("Swift", 1),
            )

        assertIs<SyntaxCapabilityState.SupportUnavailable>(unavailable.model.state)
        assertTrue(unavailable.model.visibleCells.isEmpty())
        assertIs<SyntaxCapabilityState.SupportUnavailable>(unavailable.model.terminalCache[key("Swift")])

        val retry = reduce(unavailable.model, SyntaxCapabilityEvent.Retry)
        val retried = assertIs<SyntaxCapabilityState.Checking>(retry.model.state)
        assertEquals(2, retried.generation)
        assertFalse(retry.model.terminalCache.containsKey(key("Swift")))
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
        val checking = selected()
        val deferred =
            reduce(
                checking,
                SyntaxCapabilityEvent.ProbeDeferred("Swift", 1, "Indexing in progress"),
            )
        assertIs<SyntaxCapabilityState.TemporarilyUnavailable>(deferred.model.state)
        assertTrue(deferred.model.visibleCells.isEmpty())
        assertTrue(deferred.model.terminalCache.isEmpty())

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
        assertIs<SyntaxCapabilityState.Incompatible>(mismatch.model.terminalCache[key("Swift")])
    }

    @Test
    fun `selecting a positively cached language renders it without probing`() {
        val swift = confirmed(swiftEvidence())
        val kotlin = reduce(swift, select("Kotlin")).model

        val cached = reduce(kotlin, select("Swift"))

        assertIs<SyntaxCapabilityState.Confirmed>(cached.model.state)
        assertEquals(setOf(KEYWORD), cached.model.visibleCells)
        assertEquals(
            listOf(SyntaxCapabilityEffect.CancelProbe, SyntaxCapabilityEffect.Render),
            cached.effects,
        )
    }

    @Test
    fun `stable unavailable and incompatible outcomes are reused within the session`() {
        val unavailable =
            reduce(
                selected(),
                SyntaxCapabilityEvent.ProbeUnavailable("Swift", 1),
            ).model
        val afterKotlin = reduce(unavailable, select("Kotlin")).model

        val cachedUnavailable = reduce(afterKotlin, select("Swift"))

        assertIs<SyntaxCapabilityState.SupportUnavailable>(cachedUnavailable.model.state)
        assertEquals(
            listOf(SyntaxCapabilityEffect.CancelProbe, SyntaxCapabilityEffect.Render),
            cachedUnavailable.effects,
        )

        val mismatch =
            reduce(
                selected(),
                SyntaxCapabilityEvent.ProbeMismatch(
                    languageId = "Swift",
                    generation = 1,
                    confirmedCells = setOf(KEYWORD),
                    mismatches = listOf(CapabilityMismatch(OPERATOR, "No representative span")),
                ),
            ).model
        val mismatchAfterKotlin = reduce(mismatch, select("Kotlin")).model

        val cachedMismatch = reduce(mismatchAfterKotlin, select("Swift"))

        assertIs<SyntaxCapabilityState.Incompatible>(cachedMismatch.model.state)
        assertEquals(
            listOf(SyntaxCapabilityEffect.CancelProbe, SyntaxCapabilityEffect.Render),
            cachedMismatch.effects,
        )
    }

    @Test
    fun `temporary outcome is probed again after switching away and back`() {
        val deferred =
            reduce(
                selected(),
                SyntaxCapabilityEvent.ProbeDeferred("Swift", 1, "Indexing"),
            ).model
        val afterKotlin = reduce(deferred, select("Kotlin")).model

        val retried = reduce(afterKotlin, select("Swift"))

        assertIs<SyntaxCapabilityState.Checking>(retried.model.state)
        assertTrue(retried.effects.any { it is SyntaxCapabilityEffect.StartProbe })
    }

    @Test
    fun `semantic recovery invalidates only selected cache after returning`() {
        val swift = confirmed(swiftEvidence(hasConditionalAbsence = true))
        val withKotlinCache =
            swift.copy(
                terminalCache =
                    swift.terminalCache +
                        (key("Kotlin") to SyntaxCapabilityState.Confirmed("Kotlin", kotlinEvidence())),
            )

        val opened = reduce(withKotlinCache, SyntaxCapabilityEvent.OpenHighlightingSettings)
        assertTrue(opened.model.isHighlightingRecheckArmed)
        assertEquals(listOf(SyntaxCapabilityEffect.OpenHighlightingSettings), opened.effects)

        val returned = reduce(opened.model, SyntaxCapabilityEvent.RecheckHighlighting)
        val checking = assertIs<SyntaxCapabilityState.Checking>(returned.model.state)
        assertEquals(2, checking.generation)
        assertFalse(returned.model.terminalCache.containsKey(key("Swift")))
        val cachedKotlin = assertIs<SyntaxCapabilityState.Confirmed>(returned.model.terminalCache[key("Kotlin")])
        assertEquals(kotlinEvidence(), cachedKotlin.evidence)
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
        val confirmed = confirmed(swiftEvidence())

        val transition = reduce(confirmed, SyntaxCapabilityEvent.OpenHighlightingSettings)

        assertEquals(confirmed, transition.model)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `language support navigation does not change state`() {
        val unavailable =
            reduce(
                selected(),
                SyntaxCapabilityEvent.ProbeUnavailable("Swift", 1),
            ).model

        val transition = reduce(unavailable, SyntaxCapabilityEvent.OpenLanguageSupport)

        assertEquals(unavailable, transition.model)
        assertEquals(
            listOf(SyntaxCapabilityEffect.OpenLanguageSupport("Swift")),
            transition.effects,
        )
    }

    @Test
    fun `closing cancels work clears cache and makes later events inert`() {
        val confirmed = confirmed(swiftEvidence())

        val closed = reduce(confirmed, SyntaxCapabilityEvent.CloseSettings)

        assertTrue(closed.model.isClosed)
        assertNull(closed.model.state)
        assertTrue(closed.model.terminalCache.isEmpty())
        assertEquals(
            listOf(SyntaxCapabilityEffect.CancelProbe, SyntaxCapabilityEffect.ClearRenderer),
            closed.effects,
        )
        val afterClose = reduce(closed.model, select("Kotlin"))
        assertEquals(closed.model, afterClose.model)
        assertTrue(afterClose.effects.isEmpty())
    }

    private fun selected(): SyntaxCapabilityModel = reduce(SyntaxCapabilityModel(), select("Swift")).model

    private fun select(languageId: String): SyntaxCapabilityEvent =
        SyntaxCapabilityEvent.SelectLanguage(
            key(languageId),
        )

    private fun key(languageId: String): SyntaxCapabilityKey =
        SyntaxCapabilityKey(languageId, setOf("$languageId:default"))

    private fun confirmed(evidence: SyntaxCapabilityEvidence): SyntaxCapabilityModel {
        val checking = selected()
        return reduce(
            checking,
            SyntaxCapabilityEvent.ProbeConfirmed("Swift", 1, evidence),
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
