package dev.ayuislands.accent

import java.awt.Color

/**
 * Typed wrapper for a validated `#RRGGBB` accent color string.
 *
 * ### Why a value class
 *
 * Accent hex strings flow through many call sites (settings panels,
 * rotation, LAF listener, startup, applicator, resolver) as raw `String`.
 * Phase 40.2 M-2 introduced a single shape check at the
 * [AccentApplicator.apply] boundary — but every other caller still had
 * to remember to respect the same contract. A single bad hex reaching
 * [Color.decode] throws [NumberFormatException] and aborts the first
 * painted frame.
 *
 * Lifting the validation into the type means [AccentHex] can only be
 * constructed through [of] (validated) or [ofTrusted] (explicit trust for
 * compile-time literals like [AyuVariant.defaultAccent] / [AccentColor.hex]).
 * The applicator's [toColor] is then total: there is no way to build an
 * `AccentHex` whose [value] fails `Color.decode`.
 *
 * ### Persistence
 *
 * The wrapper deliberately does NOT replace persisted `String` fields on
 * [dev.ayuislands.settings.AyuIslandsState]. `BaseState` requires `String`
 * property delegates for XML serialization; changing the stored type
 * would break forward/backward compat. Read-time wrappers
 * (e.g. [dev.ayuislands.settings.AyuIslandsState.effectiveLastAppliedAccentHex])
 * bridge the raw field to the typed accent surface.
 *
 * ### Validation
 *
 * The accepted shape is `#` + exactly six hex digits, with optional
 * leading/trailing whitespace (trimmed by [of]). Shorthand `#RGB` is
 * intentionally NOT accepted — every persisted value in `ayu-islands.xml`
 * is emitted as `#RRGGBB`, so expanding the pattern would silently forgive
 * a new corruption mode.
 */
@JvmInline
value class AccentHex private constructor(
    val value: String,
) {
    /** Decodes [value] into an AWT [Color]. Total by construction. */
    fun toColor(): Color = Color.decode(value)

    companion object {
        /**
         * Accepted shape: `#` + exactly six hex digits. Leading/trailing
         * whitespace is tolerated per the Phase 40.2 M-2 fix; [of] trims
         * before matching.
         */
        private val PATTERN = Regex("^\\s*#[0-9A-Fa-f]{6}\\s*$")

        /**
         * Returns an [AccentHex] for valid input (after `trim`), or `null`
         * when [raw] is null, empty, malformed, or contains non-hex chars.
         */
        fun of(raw: String?): AccentHex? = raw?.trim()?.takeIf { PATTERN.matches(it) }?.let(::AccentHex)

        /**
         * Throws [IllegalArgumentException] on invalid input. Prefer [of]
         * for user-provided / persisted values; use this for invariants
         * proven by a preceding validation step.
         */
        fun require(raw: String?): AccentHex = of(raw) ?: error("Invalid accent hex: '$raw' (expected #RRGGBB)")

        /**
         * Wraps a known-good literal (e.g. [AyuVariant.defaultAccent] or
         * an entry from [AYU_ACCENT_PRESETS]) without re-running validation.
         * Callers assert the input is a compile-time constant `#RRGGBB` —
         * use [of] for any runtime-sourced value.
         */
        fun ofTrusted(raw: String): AccentHex = AccentHex(raw.trim())
    }
}
