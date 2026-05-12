package dev.ayuislands.util

/**
 * Installs [value] into this [ThreadLocal] for the duration of [block] and
 * restores the previous slot (or clears it when no prior value existed) on
 * exit, even if [block] throws.
 *
 * Passing `null` skips installation entirely and just runs [block] — useful
 * for callers that want a uniform call shape regardless of whether they have
 * a scoped value to install.
 *
 * Non-local returns from [block] are honoured by the `inline` modifier; the
 * slot is still restored on the way out.
 *
 * Shared between [dev.ayuislands.accent.ChromeTintContext] and
 * [dev.ayuislands.vcs.VcsColorContext] which both rely on the same
 * "state untouched on throw" invariant.
 */
internal inline fun <T, V : Any> ThreadLocal<V?>.withScopedValue(
    value: V?,
    block: () -> T,
): T {
    if (value == null) return block()
    val previous = get()
    set(value)
    return try {
        block()
    } finally {
        if (previous == null) {
            remove()
        } else {
            set(previous)
        }
    }
}
