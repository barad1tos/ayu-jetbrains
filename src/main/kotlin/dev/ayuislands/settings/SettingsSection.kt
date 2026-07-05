package dev.ayuislands.settings

/**
 * Pending/stored bookkeeping for a settings panel section over an immutable
 * snapshot [T].
 *
 * Every [AyuIslandsSettingsPanel] used to hand-roll the same machine: a
 * `pending*`/`stored*` field pair per setting, an OR-chain `isModified`, an
 * apply that copies pending into stored, and a reset that copies stored back
 * into pending. This class owns that machine once, over a whole-object
 * snapshot compared with `!=` (the `PluginSettingsSnapshot` shape).
 *
 * Contract:
 *  - [pending] is what the UI edits (via [update]); [stored] is the baseline
 *    the panel last loaded or committed. Both are read-only outside so every
 *    mutation goes through a named seam.
 *  - [load] re-reads the persisted baseline through [snapshot] and aligns
 *    [pending] with it — the "reset from persisted state" semantics used by
 *    `AyuIslandsChromePanel`/`VcsColorPanel`. Panels that clamp legacy
 *    persisted values follow [load] with an [update] so [stored] keeps the
 *    raw value while [pending] holds the normalized one (a deliberate diff
 *    that surfaces the Apply button).
 *  - [resetToStored] is the in-memory reset used by the remaining panels.
 *  - [commit] runs the panel's whole apply step — side-effects and
 *    persistence in whatever order that panel requires — and only converges
 *    [stored] onto [pending] afterwards. A throw from [commit]'s action
 *    aborts the commit: [stored] is guaranteed unchanged, so [isModified]
 *    keeps reporting `true` and the user can retry. [pending], however, keeps
 *    any [update] mutations the action made before throwing — actions that
 *    normalize [pending] mid-commit should do so before their first throwing
 *    operation. The action may normalize [pending] via [update] (e.g. folding
 *    preset values before persisting); [stored] converges onto the value of
 *    [pending] as observed AFTER the action returns.
 *
 * Named `commit` rather than `apply` on purpose: a zero-parameter trailing
 * lambda on an `apply(action: (T, T) -> Unit)` member would silently resolve
 * to the stdlib `apply` scope function instead of failing to compile.
 *
 * @param initial default snapshot used before the first [load] — panels are
 *   constructed before `buildPanel` runs, and the settings service must not
 *   be touched at construction time.
 * @param snapshot reads the persisted state into a fresh [T]; invoked by
 *   every [load].
 */
internal class SettingsSection<T>(
    initial: T,
    private val snapshot: () -> T,
) {
    var pending: T = initial
        private set

    var stored: T = initial
        private set

    fun isModified(): Boolean = pending != stored

    /** Rewrites [pending] through [transform]; [stored] is untouched. */
    fun update(transform: (T) -> T) {
        pending = transform(pending)
    }

    /** Re-reads the persisted baseline; [pending] and [stored] both take it. */
    fun load() {
        stored = snapshot()
        pending = stored
    }

    /** In-memory reset: [pending] returns to [stored]. Returns the restored value. */
    fun resetToStored(): T {
        pending = stored
        return pending
    }

    /**
     * Runs [action] with the current ([pending], [stored]) pair, then
     * converges [stored] onto [pending]. No-op when nothing diverged; a
     * throw from [action] propagates with [stored] unchanged — [pending]
     * keeps any [update] mutations made before the throw (see class KDoc).
     */
    fun commit(action: (pending: T, stored: T) -> Unit) {
        if (!isModified()) return
        action(pending, stored)
        stored = pending
    }
}
