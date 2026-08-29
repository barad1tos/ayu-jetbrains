package dev.ayuislands.settings

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyntaxCapabilityControllerTest {
    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `catalog languages are probed lazily once per session profile`() {
        val starts = mutableListOf<String>()
        val controller = SyntaxCapabilityController(project, rendered = {})
        controller.replaceProbeForTest(
            completingProbe { specification, generation ->
                starts += specification.storageId
                SyntaxProbeResult.Confirmed(
                    languageId = specification.storageId,
                    generation = generation,
                    evidence =
                        SyntaxCapabilityEvidence(
                            languageId = specification.storageId,
                            confirmedCells = emptySet(),
                        ),
                )
            },
        )
        val languages = SyntaxLanguageRegistry.specifications().map(LanguageSpecification::storageId)

        assertTrue(starts.isEmpty())
        languages.forEachIndexed { index, language ->
            controller.selectLanguage(language)
            assertEquals(index + 1, starts.size, language)
        }
        languages.forEach(controller::selectLanguage)

        assertEquals(languages, starts)
        controller.dispose()
    }

    @Test
    fun `language changes and disposal cancel only in-flight probes`() {
        val lifetimes = mutableListOf<ProbeLifetime>()
        val controller = SyntaxCapabilityController(project, rendered = {})
        controller.replaceProbeForTest { _, _, parent, _ ->
            val lifetime = ProbeLifetime()
            lifetimes += lifetime
            Disposer.register(parent) { lifetime.isDisposed = true }
        }

        controller.selectLanguage("Swift")
        controller.selectLanguage("Kotlin")

        assertTrue(lifetimes.first().isDisposed)
        assertFalse(lifetimes.last().isDisposed)

        controller.dispose()

        assertTrue(lifetimes.last().isDisposed)
    }

    @Test
    fun `probe failure renders terminal state and releases its lifetime`() {
        var lifetime: ProbeLifetime? = null
        val rendered = mutableListOf<SyntaxCapabilityState?>()
        val controller = SyntaxCapabilityController(project, rendered::add)
        controller.replaceProbeForTest { _, _, parent, _ ->
            val probeLifetime = ProbeLifetime()
            lifetime = probeLifetime
            Disposer.register(parent) { probeLifetime.isDisposed = true }
            error("NoClassDefFoundError: internal.plugin.Probe")
        }

        controller.selectLanguage("Swift")

        val state = assertIs<SyntaxCapabilityState.TemporarilyUnavailable>(rendered.last())
        assertEquals("Swift", state.languageId)
        assertEquals(
            "Could not check Swift support. Retry, or update or enable its language plugin if the problem continues.",
            state.reason,
        )
        assertTrue(requireNotNull(lifetime).isDisposed)
        controller.dispose()
    }

    @Test
    fun `probe cancellation releases its lifetime and preserves exception identity`() {
        var lifetime: ProbeLifetime? = null
        val cancellation = ProcessCanceledException()
        val controller = SyntaxCapabilityController(project, rendered = {})
        controller.replaceProbeForTest { _, _, parent, _ ->
            val probeLifetime = ProbeLifetime()
            lifetime = probeLifetime
            Disposer.register(parent) { probeLifetime.isDisposed = true }
            throw cancellation
        }

        val thrown = assertFails { controller.selectLanguage("Swift") }

        assertSame(cancellation, thrown)
        assertTrue(requireNotNull(lifetime).isDisposed)
        controller.dispose()
    }

    private fun completingProbe(result: (LanguageSpecification, Long) -> SyntaxProbeResult): SyntaxCapabilityProbe =
        SyntaxCapabilityProbe { specification, generation, _, completed ->
            completed(result(specification, generation))
        }

    private data class ProbeLifetime(
        var isDisposed: Boolean = false,
    )
}
