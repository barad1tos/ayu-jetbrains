package dev.ayuislands.settings

import com.intellij.openapi.Disposable
import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.PrimitiveCategory

internal interface SyntaxCapabilityProbe {
    fun start(
        specification: LanguageSpecification,
        generation: Long,
        parent: Disposable,
        completed: (SyntaxProbeResult) -> Unit,
    )
}

internal sealed interface SyntaxProbeResult {
    val languageId: String
    val generation: Long

    data class Confirmed(
        override val languageId: String,
        override val generation: Long,
        val evidence: SyntaxCapabilityEvidence,
    ) : SyntaxProbeResult

    data class MissingPlugin(
        override val languageId: String,
        override val generation: Long,
        val recovery: PluginRecovery,
    ) : SyntaxProbeResult

    data class Deferred(
        override val languageId: String,
        override val generation: Long,
        val reason: String,
    ) : SyntaxProbeResult

    data class Mismatch(
        override val languageId: String,
        override val generation: Long,
        val confirmedCells: Set<PrimitiveCategory>,
        val mismatches: List<CapabilityMismatch>,
    ) : SyntaxProbeResult
}

internal fun SyntaxProbeResult.toEvent(): SyntaxCapabilityEvent =
    when (this) {
        is SyntaxProbeResult.Confirmed ->
            SyntaxCapabilityEvent.ProbeConfirmed(languageId, generation, evidence)
        is SyntaxProbeResult.MissingPlugin ->
            SyntaxCapabilityEvent.ProbeMissingPlugin(languageId, generation, recovery)
        is SyntaxProbeResult.Deferred ->
            SyntaxCapabilityEvent.ProbeDeferred(languageId, generation, reason)
        is SyntaxProbeResult.Mismatch ->
            SyntaxCapabilityEvent.ProbeMismatch(languageId, generation, confirmedCells, mismatches)
    }
