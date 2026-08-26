package dev.ayuislands.settings

internal object SyntaxCapabilityReducer {
    fun reduce(
        model: SyntaxCapabilityModel,
        event: SyntaxCapabilityEvent,
    ): SyntaxCapabilityTransition {
        if (model.isClosed) return SyntaxCapabilityTransition(model)
        return when (event) {
            is SyntaxCapabilityEvent.SelectLanguage -> selectLanguage(model, event.languageId)
            is SyntaxCapabilityEvent.ProbeConfirmed -> confirm(model, event)
            is SyntaxCapabilityEvent.ProbeMissingPlugin -> markPluginUnavailable(model, event)
            is SyntaxCapabilityEvent.ProbeDeferred -> defer(model, event)
            is SyntaxCapabilityEvent.ProbeMismatch -> markIncompatible(model, event)
            SyntaxCapabilityEvent.Retry -> retry(model)
            SyntaxCapabilityEvent.OpenPluginSettings -> openPluginSettings(model)
            SyntaxCapabilityEvent.OpenHighlightingSettings -> openHighlightingSettings(model)
            SyntaxCapabilityEvent.RecheckHighlighting -> recheckHighlighting(model)
            SyntaxCapabilityEvent.CloseSettings -> close(model)
        }
    }

    private fun selectLanguage(
        model: SyntaxCapabilityModel,
        languageId: String,
    ): SyntaxCapabilityTransition {
        val generation = model.generation + 1
        val cached = model.confirmedCache[languageId]
        val nextState =
            if (cached == null) {
                SyntaxCapabilityState.Checking(languageId, generation)
            } else {
                SyntaxCapabilityState.Confirmed(languageId, cached)
            }
        val effects =
            buildList {
                add(SyntaxCapabilityEffect.CancelProbe)
                if (cached == null) add(SyntaxCapabilityEffect.StartProbe(languageId, generation))
                add(SyntaxCapabilityEffect.Render)
            }
        return SyntaxCapabilityTransition(
            model.copy(
                state = nextState,
                generation = generation,
                isHighlightingRecheckArmed = false,
            ),
            effects,
        )
    }

    private fun confirm(
        model: SyntaxCapabilityModel,
        event: SyntaxCapabilityEvent.ProbeConfirmed,
    ): SyntaxCapabilityTransition {
        if (!model.accepts(event) || event.evidence.languageId != event.languageId) {
            return SyntaxCapabilityTransition(model)
        }
        return SyntaxCapabilityTransition(
            model.copy(
                state = SyntaxCapabilityState.Confirmed(event.languageId, event.evidence),
                confirmedCache = model.confirmedCache + (event.languageId to event.evidence),
            ),
            listOf(SyntaxCapabilityEffect.Render),
        )
    }

    private fun markPluginUnavailable(
        model: SyntaxCapabilityModel,
        event: SyntaxCapabilityEvent.ProbeMissingPlugin,
    ): SyntaxCapabilityTransition {
        if (!model.accepts(event)) return SyntaxCapabilityTransition(model)
        return completeProbe(
            model,
            SyntaxCapabilityState.PluginUnavailable(event.languageId, event.recovery),
        )
    }

    private fun defer(
        model: SyntaxCapabilityModel,
        event: SyntaxCapabilityEvent.ProbeDeferred,
    ): SyntaxCapabilityTransition {
        if (!model.accepts(event)) return SyntaxCapabilityTransition(model)
        return completeProbe(
            model,
            SyntaxCapabilityState.TemporarilyUnavailable(event.languageId, event.reason),
        )
    }

    private fun markIncompatible(
        model: SyntaxCapabilityModel,
        event: SyntaxCapabilityEvent.ProbeMismatch,
    ): SyntaxCapabilityTransition {
        if (!model.accepts(event)) return SyntaxCapabilityTransition(model)
        return completeProbe(
            model,
            SyntaxCapabilityState.Incompatible(
                languageId = event.languageId,
                confirmedCells = event.confirmedCells,
                mismatches = event.mismatches,
            ),
        )
    }

    private fun retry(model: SyntaxCapabilityModel): SyntaxCapabilityTransition {
        val current = model.state
        if (current !is SyntaxCapabilityState.PluginUnavailable &&
            current !is SyntaxCapabilityState.TemporarilyUnavailable &&
            current !is SyntaxCapabilityState.Incompatible
        ) {
            return SyntaxCapabilityTransition(model)
        }
        return startProbe(model, current.languageId, invalidateCache = false)
    }

    private fun openPluginSettings(model: SyntaxCapabilityModel): SyntaxCapabilityTransition {
        val current =
            model.state as? SyntaxCapabilityState.PluginUnavailable
                ?: return SyntaxCapabilityTransition(model)
        return SyntaxCapabilityTransition(
            model,
            listOf(SyntaxCapabilityEffect.OpenPluginSettings(current.recovery.requirement)),
        )
    }

    private fun openHighlightingSettings(model: SyntaxCapabilityModel): SyntaxCapabilityTransition {
        val current =
            model.state as? SyntaxCapabilityState.Confirmed
                ?: return SyntaxCapabilityTransition(model)
        if (current.evidence.conditionalAbsences.isEmpty()) return SyntaxCapabilityTransition(model)
        return SyntaxCapabilityTransition(
            model.copy(isHighlightingRecheckArmed = true),
            listOf(SyntaxCapabilityEffect.OpenHighlightingSettings),
        )
    }

    private fun recheckHighlighting(model: SyntaxCapabilityModel): SyntaxCapabilityTransition {
        val current =
            model.state as? SyntaxCapabilityState.Confirmed
                ?: return SyntaxCapabilityTransition(model)
        if (!model.isHighlightingRecheckArmed) return SyntaxCapabilityTransition(model)
        return startProbe(model, current.languageId, invalidateCache = true)
    }

    private fun startProbe(
        model: SyntaxCapabilityModel,
        languageId: String,
        invalidateCache: Boolean,
    ): SyntaxCapabilityTransition {
        val generation = model.generation + 1
        return SyntaxCapabilityTransition(
            model.copy(
                state = SyntaxCapabilityState.Checking(languageId, generation),
                generation = generation,
                confirmedCache =
                    if (invalidateCache) model.confirmedCache - languageId else model.confirmedCache,
                isHighlightingRecheckArmed = false,
            ),
            listOf(
                SyntaxCapabilityEffect.CancelProbe,
                SyntaxCapabilityEffect.StartProbe(languageId, generation),
                SyntaxCapabilityEffect.Render,
            ),
        )
    }

    private fun close(model: SyntaxCapabilityModel): SyntaxCapabilityTransition =
        SyntaxCapabilityTransition(
            model.copy(
                state = null,
                confirmedCache = emptyMap(),
                isHighlightingRecheckArmed = false,
                isClosed = true,
            ),
            listOf(SyntaxCapabilityEffect.CancelProbe, SyntaxCapabilityEffect.ClearRenderer),
        )

    private fun SyntaxCapabilityModel.accepts(event: SyntaxCapabilityEvent.ProbeCompletion): Boolean {
        val checking = state as? SyntaxCapabilityState.Checking ?: return false
        return checking.languageId == event.languageId && checking.generation == event.generation
    }

    private fun completeProbe(
        model: SyntaxCapabilityModel,
        nextState: SyntaxCapabilityState,
    ): SyntaxCapabilityTransition =
        SyntaxCapabilityTransition(
            model.copy(state = nextState),
            listOf(SyntaxCapabilityEffect.Render),
        )
}
