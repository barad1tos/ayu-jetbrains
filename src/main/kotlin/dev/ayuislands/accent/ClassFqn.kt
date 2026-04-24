package dev.ayuislands.accent

/**
 * Typed wrapper for a Java/Kotlin fully-qualified class name used by [LiveChromeRefresher]
 * to look up Swing peers at runtime via `Class.getName()` string match.
 *
 * ### Why a value class
 *
 * The refresher's ancestor-scoped entry points previously took two adjacent
 * `String` parameters with identical type (`targetFqn`, `ancestorFqn`).
 * Swapping them compiles, unit tests still pass (both sides are just string
 * match + tree walk), but production silently over-tints the wrong peers —
 * exactly the positional-arg footgun Phase 40.3 was chartered to kill.
 * Wrapping the FQN in a dedicated type forces call sites to tag each
 * argument, so a swap becomes a compile error.
 *
 * ### Factories vs raw construction
 *
 * The primary constructor is private — callers must go through [of] or
 * [require] so an invalid string cannot enter the type:
 *
 *  - [of] returns `null` for blank / syntactically-invalid input; callers
 *    that can tolerate missing data use this one.
 *  - [require] throws [IllegalArgumentException] for invalid input; call
 *    sites that hardcode an FQN (the `PEER_CLASS` constants in every
 *    chrome element) use this one so a typo fails fast at class-init
 *    time instead of silently matching nothing at runtime.
 *
 * ### FQN pattern
 *
 * Matches `^[a-zA-Z_][\w.$]*$` — a Java identifier start followed by any
 * mix of identifier chars, dots, and `$` inner-class markers. Intentionally
 * conservative: rejects leading dot, leading digit, and embedded whitespace.
 * `\w` in Kotlin's regex engine is `[a-zA-Z0-9_]` and does NOT include `$`,
 * so the class separator must be listed explicitly.
 */
@JvmInline
value class ClassFqn private constructor(
    val value: String,
) {
    companion object {
        /**
         * Java-identifier FQN pattern — start char is letter or underscore,
         * remainder allows word chars (incl. `$` for inner classes) and dots.
         * See class-level KDoc for the rationale behind each exclusion.
         */
        private val FQN_PATTERN = Regex("^[a-zA-Z_][\\w.$]*$")

        /** Returns a [ClassFqn] when [raw] matches [FQN_PATTERN], otherwise `null`. */
        fun of(raw: String): ClassFqn? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (!FQN_PATTERN.matches(trimmed)) return null
            return ClassFqn(trimmed)
        }

        /**
         * Returns a [ClassFqn] when [raw] matches [FQN_PATTERN], otherwise throws
         * [IllegalArgumentException]. Used at hardcoded `PEER_CLASS` call sites so
         * a typo surfaces at class-init time instead of as a silent no-match at
         * runtime.
         */
        fun require(raw: String): ClassFqn =
            of(raw) ?: throw IllegalArgumentException(
                "Invalid class FQN: '$raw' — must match $FQN_PATTERN",
            )
    }
}
