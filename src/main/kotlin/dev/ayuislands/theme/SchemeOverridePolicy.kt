package dev.ayuislands.theme

import dev.ayuislands.theme.EditorSchemeOverrides.OverrideState

/** Selects desired ownership without reading native state, encoding metadata, or acquiring a claim. */
internal object SchemeOverridePolicy {
    fun plan(facts: SchemeOverrideFacts): SchemeOverridePlan {
        val current = facts.current
        val isOwned = current is OverrideState.Owned && current.owner == facts.owner
        if (isOwned) {
            check(facts.comparison == SchemeEquality.EQUAL || facts.comparison == SchemeEquality.DIFFERENT) {
                "Editor ownership comparison is unavailable"
            }
        }
        return if (facts.isManaged) managedPlan(facts, isOwned) else ordinaryPlan(facts)
    }

    private fun ordinaryPlan(facts: SchemeOverrideFacts): SchemeOverridePlan {
        val current = facts.current
        val shouldRetire = current?.owner == facts.owner
        val baseline =
            (current as? OverrideState.Owned)?.original.takeIf {
                shouldRetire && facts.comparison == SchemeEquality.EQUAL
            }
        return SchemeOverridePlan(
            facts.requested ?: baseline,
            current.takeUnless { shouldRetire },
            OverrideWriteResult.APPLIED,
        )
    }

    private fun managedPlan(
        facts: SchemeOverrideFacts,
        isOwned: Boolean,
    ): SchemeOverridePlan {
        val current = facts.current
        val requested = checkNotNull(facts.requested) { "Managed editor keys require a requested value" }
        return when {
            current is OverrideState.Relinquished -> {
                SchemeOverridePlan(null, current, OverrideWriteResult.RELINQUISHED)
            }

            current != null && !isOwned -> {
                SchemeOverridePlan(null, current, OverrideWriteResult.SKIPPED)
            }

            isOwned && facts.comparison == SchemeEquality.DIFFERENT -> {
                SchemeOverridePlan(null, OverrideState.Relinquished(facts.owner), OverrideWriteResult.RELINQUISHED)
            }

            else -> {
                val baseline =
                    (current as? OverrideState.Owned)?.original ?: checkNotNull(facts.baseline) {
                        "Editor ownership baseline is unavailable"
                    }
                SchemeOverridePlan(
                    requested,
                    OverrideState.Owned(facts.owner, baseline, requested),
                    OverrideWriteResult.APPLIED,
                )
            }
        }
    }
}
