package dev.ayuislands.vcs

import dev.ayuislands.settings.AyuIslandsState

/**
 * Immutable VCS color settings captured from the Settings panel's pending values.
 *
 * Mirrors the [dev.ayuislands.accent.ChromeTintSnapshot] pattern from Phase 40:
 * the panel commits state only after the re-apply succeeds, so applier code
 * needs access to the *pending* values during apply without us mutating the
 * persisted state mid-flight. The snapshot rides through [VcsColorContext] for
 * the duration of one apply pass.
 *
 * Phase 40.2b redesign — snapshot carries the resolved per-category intensity
 * map directly rather than a preset+per-category-overrides pair. The panel
 * materialises per-section preset choices into per-category intensities BEFORE
 * constructing the snapshot, so the applier and blender stay agnostic to
 * preset/Custom branching.
 *
 * @property enabled master kill-switch — when `false`, applier no-ops and
 *   surfaces revert to stock XML
 * @property perCategoryIntensities resolved intensity per category. Missing
 *   entries default to the no-op [VcsColorPreset.AMBIENT_SLIDER].
 */
internal data class VcsColorSnapshot(
    val enabled: Boolean,
    val perCategoryIntensities: Map<VcsColorCategory, Int>,
) {
    /**
     * Resolves the effective intensity for [category] under this snapshot. When
     * disabled the snapshot returns zero across every category so the applier
     * writes stock XML values back. Missing-entry fallback uses Ambient (no-op)
     * for the same defensive reasoning the state's `effectiveVcs*Preset()`
     * helpers apply.
     */
    fun intensityFor(category: VcsColorCategory): VcsIntensity {
        if (!enabled) return VcsIntensity.of(0)
        val raw = perCategoryIntensities[category] ?: VcsColorPreset.AMBIENT_SLIDER
        return VcsIntensity.of(raw)
    }

    companion object {
        /**
         * Captures the current state of [state] into an immutable snapshot.
         * Materialises every category's effective intensity upfront via
         * [AyuIslandsState.effectiveVcsIntensityFor], so per-section preset +
         * Custom-mode-slider resolution happens once at snapshot time rather
         * than on every applier read.
         */
        fun fromState(state: AyuIslandsState): VcsColorSnapshot {
            val intensities =
                VcsColorCategory.entries.associateWith { category ->
                    state.effectiveVcsIntensityFor(category).percent
                }
            return VcsColorSnapshot(
                enabled = state.vcsColorEnabled,
                perCategoryIntensities = intensities,
            )
        }
    }
}

/**
 * ThreadLocal scoped-override channel for the pending [VcsColorSnapshot].
 *
 * Settings apply path wraps the applier call in
 * `VcsColorContext.withSnapshot(pendingSnapshot) { applier.applyAll() }` so
 * every category read during that apply sees the panel's pending values, not
 * whatever the persisted state currently holds. If apply throws, the snapshot
 * is rolled back and the persisted state is never touched — same "state
 * untouched on throw" invariant the chrome tinting context defends.
 */
internal object VcsColorContext {
    private val current = ThreadLocal<VcsColorSnapshot?>()

    /**
     * Returns the effective intensity for [category] — reads from the pending
     * snapshot if one is active on this thread, otherwise reconstructs the
     * snapshot from [fallbackState].
     */
    fun currentIntensity(
        category: VcsColorCategory,
        fallbackState: AyuIslandsState,
    ): VcsIntensity = (current.get() ?: VcsColorSnapshot.fromState(fallbackState)).intensityFor(category)

    /**
     * Whether VCS color customization is enabled under the pending snapshot
     * (or the persisted state if no pending snapshot is active).
     */
    fun isEnabled(fallbackState: AyuIslandsState): Boolean = current.get()?.enabled ?: fallbackState.vcsColorEnabled

    /**
     * Runs [block] with [snapshot] installed as the current pending snapshot.
     * Restores the previous snapshot (or clears the ThreadLocal entry) on exit
     * even if [block] throws. Passing `null` is a no-op convenience for paths
     * that want the same call shape regardless of whether they have a pending
     * snapshot to install.
     */
    fun <T> withSnapshot(
        snapshot: VcsColorSnapshot?,
        block: () -> T,
    ): T {
        if (snapshot == null) return block()
        val previous = current.get()
        current.set(snapshot)
        return try {
            block()
        } finally {
            if (previous == null) {
                current.remove()
            } else {
                current.set(previous)
            }
        }
    }
}
