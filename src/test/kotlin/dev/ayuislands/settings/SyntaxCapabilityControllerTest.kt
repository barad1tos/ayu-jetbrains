package dev.ayuislands.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        controller.replaceProbeForTest(
            object : SyntaxCapabilityProbe {
                override fun start(
                    specification: LanguageSpecification,
                    generation: Long,
                    parent: Disposable,
                    completed: (SyntaxProbeResult) -> Unit,
                ) {
                    val lifetime = ProbeLifetime()
                    lifetimes += lifetime
                    Disposer.register(parent) { lifetime.isDisposed = true }
                }
            },
        )

        controller.selectLanguage("Swift")
        controller.selectLanguage("Kotlin")

        assertTrue(lifetimes.first().isDisposed)
        assertFalse(lifetimes.last().isDisposed)

        controller.dispose()

        assertTrue(lifetimes.last().isDisposed)
    }

    private fun completingProbe(result: (LanguageSpecification, Long) -> SyntaxProbeResult): SyntaxCapabilityProbe =
        object : SyntaxCapabilityProbe {
            override fun start(
                specification: LanguageSpecification,
                generation: Long,
                parent: Disposable,
                completed: (SyntaxProbeResult) -> Unit,
            ) {
                completed(result(specification, generation))
            }
        }

    private data class ProbeLifetime(
        var isDisposed: Boolean = false,
    )
}
