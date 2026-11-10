package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.rotation.RotationContextCommit
import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.fields.StudioFieldValues
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioEffectiveContinuation
import dev.ayuislands.studio.model.StudioGlowSources
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioTarget
import java.util.Locale

/** Effective continuity never promotes pins or generated colors into requested appearance preferences. */
internal object StudioContinuationPolicy {
    fun transfer(
        document: StudioDocument,
        operation: RotationContextCommit,
    ): StudioDocument {
        val outgoing = requireNotNull(StudioTargets.current(document, operation.before.inputs.owner.target))
        val incoming = outgoing.copy(revision = operation.incoming.inputs.owner.appearanceRevision)
        return document.copy(
            currents = document.currents.copy(global = incoming),
            runtime =
                document.runtime.copy(
                    effectiveContinuation =
                        StudioEffectiveContinuation(
                            operation.before.inputs.owner,
                            outgoing.appearance,
                            operation.accent,
                        ),
                ),
        )
    }

    fun accept(
        before: StudioDocument,
        candidate: StudioDocument,
        draft: DraftRecord,
        target: StudioTarget,
    ): StudioDocument {
        val held = before.runtime.effectiveContinuation ?: return candidate
        if (target != StudioTarget.Global) return candidate
        if (draft.tracking.intent == DraftIntent.FULL_PROFILE) {
            return candidate.copy(runtime = candidate.runtime.copy(effectiveContinuation = null))
        }
        val appearance = candidate.currents.global.appearance
        val accent = held.accent?.takeUnless { replaced(before, candidate, it) }
        return candidate.copy(
            runtime =
                candidate.runtime.copy(
                    effectiveContinuation = held.copy(requested = appearance, accent = accent),
                ),
        )
    }

    fun releaseAccent(runtime: StudioRuntimeState): StudioRuntimeState =
        runtime.copy(
            effectiveContinuation = runtime.effectiveContinuation?.copy(accent = null),
        )

    private fun replaced(
        before: StudioDocument,
        after: StudioDocument,
        accent: StudioAccentContinuation,
    ): Boolean {
        if (before.shared.systemAccentPolicy != after.shared.systemAccentPolicy) return true
        val previous = before.currents.global.appearance
        val incoming = after.currents.global.appearance
        if (previous.accent.records
                .single { it.key.name == "followSystemAccent" }
                .value !=
            incoming.accent.records
                .single { it.key.name == "followSystemAccent" }
                .value
        ) {
            return true
        }
        if (accent.dependencies.any { value(before, it.address) != value(after, it.address) }) return true
        if (accent.variant == null) {
            return before.shared.plugins.records
                .filter { it.key.name in StudioGlowSources.plugins } !=
                after.shared.plugins.records
                    .filter { it.key.name in StudioGlowSources.plugins }
        }
        if (accent.resolved.source != AccentResolver.Source.GLOBAL ||
            StudioAccentPolicy.followsSystem(incoming, after.shared.systemAccentPolicy)
        ) {
            return false
        }
        val key = "${accent.variant.name.lowercase(Locale.ROOT)}Accent"
        return previous.accent.records
            .single { it.key.name == key }
            .value !=
            incoming.accent.records
                .single { it.key.name == key }
                .value
    }

    private fun value(
        document: StudioDocument,
        address: FieldAddress,
    ): FieldValue {
        val fields =
            buildList {
                addAll(StudioAppearanceFields.fields(document.currents.global.appearance))
                addAll(document.shared.mappings.records)
                addAll(document.shared.plugins.records)
                addAll(document.shared.system.records)
            }
        val field = fields.singleOrNull { it.key == address.field } ?: return FieldValue.Absent
        return StudioFieldValues.value(field, address.part)
    }
}
