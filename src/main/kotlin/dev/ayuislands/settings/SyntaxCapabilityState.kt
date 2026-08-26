package dev.ayuislands.settings

import dev.ayuislands.syntax.PluginRequirement
import dev.ayuislands.syntax.PrimitiveCategory

internal const val PLUGIN_INSTALL_INSTRUCTION =
    "To tune this language, please install its official plugin from JetBrains Marketplace."

internal data class PluginRecovery(
    val requirement: PluginRequirement? = null,
) {
    val instruction: String
        get() = PLUGIN_INSTALL_INSTRUCTION
}

internal data class ConditionalAbsence(
    val primitive: PrimitiveCategory,
    val reason: String,
)

internal data class CapabilityMismatch(
    val primitive: PrimitiveCategory,
    val reason: String,
)

internal data class SyntaxCapabilityEvidence(
    val languageId: String,
    val confirmedCells: Set<PrimitiveCategory>,
    val keysByPrimitive: Map<PrimitiveCategory, Set<String>> = emptyMap(),
    val conditionalAbsences: List<ConditionalAbsence> = emptyList(),
)

internal sealed interface SyntaxCapabilityState {
    val languageId: String

    data class Checking(
        override val languageId: String,
        val generation: Long,
    ) : SyntaxCapabilityState

    data class PluginUnavailable(
        override val languageId: String,
        val recovery: PluginRecovery,
    ) : SyntaxCapabilityState

    data class TemporarilyUnavailable(
        override val languageId: String,
        val reason: String,
    ) : SyntaxCapabilityState

    data class Incompatible(
        override val languageId: String,
        val confirmedCells: Set<PrimitiveCategory>,
        val mismatches: List<CapabilityMismatch>,
    ) : SyntaxCapabilityState

    data class Confirmed(
        override val languageId: String,
        val evidence: SyntaxCapabilityEvidence,
    ) : SyntaxCapabilityState
}

internal sealed interface SyntaxCapabilityEvent {
    data class SelectLanguage(
        val languageId: String,
    ) : SyntaxCapabilityEvent

    sealed interface ProbeCompletion : SyntaxCapabilityEvent {
        val languageId: String
        val generation: Long
    }

    data class ProbeConfirmed(
        override val languageId: String,
        override val generation: Long,
        val evidence: SyntaxCapabilityEvidence,
    ) : ProbeCompletion

    data class ProbeMissingPlugin(
        override val languageId: String,
        override val generation: Long,
        val recovery: PluginRecovery,
    ) : ProbeCompletion

    data class ProbeDeferred(
        override val languageId: String,
        override val generation: Long,
        val reason: String,
    ) : ProbeCompletion

    data class ProbeMismatch(
        override val languageId: String,
        override val generation: Long,
        val confirmedCells: Set<PrimitiveCategory>,
        val mismatches: List<CapabilityMismatch>,
    ) : ProbeCompletion

    data object Retry : SyntaxCapabilityEvent

    data object OpenPluginSettings : SyntaxCapabilityEvent

    data object OpenHighlightingSettings : SyntaxCapabilityEvent

    data object RecheckHighlighting : SyntaxCapabilityEvent

    data object CloseSettings : SyntaxCapabilityEvent
}

internal sealed interface SyntaxCapabilityEffect {
    data object CancelProbe : SyntaxCapabilityEffect

    data class StartProbe(
        val languageId: String,
        val generation: Long,
    ) : SyntaxCapabilityEffect

    data object Render : SyntaxCapabilityEffect

    data class OpenPluginSettings(
        val requirement: PluginRequirement?,
    ) : SyntaxCapabilityEffect

    data object OpenHighlightingSettings : SyntaxCapabilityEffect

    data object ClearRenderer : SyntaxCapabilityEffect
}

internal data class SyntaxCapabilityModel(
    val state: SyntaxCapabilityState? = null,
    val generation: Long = 0,
    val confirmedCache: Map<String, SyntaxCapabilityEvidence> = emptyMap(),
    val isHighlightingRecheckArmed: Boolean = false,
    val isClosed: Boolean = false,
) {
    val visibleCells: Set<PrimitiveCategory>
        get() =
            when (val current = state) {
                is SyntaxCapabilityState.Confirmed -> current.evidence.confirmedCells
                is SyntaxCapabilityState.Incompatible -> current.confirmedCells
                is SyntaxCapabilityState.Checking,
                is SyntaxCapabilityState.PluginUnavailable,
                is SyntaxCapabilityState.TemporarilyUnavailable,
                null,
                -> emptySet()
            }
}

internal data class SyntaxCapabilityTransition(
    val model: SyntaxCapabilityModel,
    val effects: List<SyntaxCapabilityEffect> = emptyList(),
)
