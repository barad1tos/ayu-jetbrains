package dev.ayuislands.accent

/**
 * Result type for [ChromeDecorationsProbe.probe].
 *
 * The outcome is modeled as a sealed hierarchy so the probe is the single place
 * that distinguishes *why* chrome tinting is unavailable. User-visible display
 * strings live in the UI layer ([dev.ayuislands.settings.AyuIslandsChromePanel])
 * — a sealed `when` on the subtype is total, so the mapping is still
 * compile-time exhaustive but the display copy lives next to the UI code that
 * owns its wording.
 *
 * Back-compat: [ChromeDecorationsProbe.isCustomHeaderActive] is kept as a
 * thin delegate (`probe() is Supported`) for call sites that only need the
 * boolean answer (the Settings row `.enabledIf` predicate).
 */
sealed interface ChromeSupport {
    /** JBR custom window decorations are active — chrome tinting will reach the title bar. */
    object Supported : ChromeSupport

    /**
     * Chrome tinting cannot reach the title bar. The subtype identity encodes
     * *why*; the UI layer pattern-matches on it to render a user-visible
     * message.
     */
    sealed interface Unsupported : ChromeSupport {
        /** macOS is painting its native title bar (IDE 2026.1+ removed the unified-title toggle). */
        object NativeMacTitleBar : Unsupported

        /** Linux with GNOME server-side decorations — the window manager paints the title bar. */
        object GnomeSsd : Unsupported

        /** Windows without the CustomWindowHeader LAF entry — IDE is using the native frame. */
        object WindowsNoCustomHeader : Unsupported

        /** Running on an OS the probe's dispatch enum does not cover. */
        object UnknownOs : Unsupported
    }
}
