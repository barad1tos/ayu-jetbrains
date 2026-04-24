package dev.ayuislands.accent

/**
 * Result type for [ChromeDecorationsProbe.probe].
 *
 * Phase 40.3c Refactor 3 — the prior probe collapsed four OS / decoration
 * branches (native macOS title bar, GNOME SSD, Windows native header,
 * unknown OS) into a single boolean. Settings UI then had to re-derive the
 * reason code with its own per-OS `when` (`SystemInfo.isMac/isWindows/isLinux`)
 * to compose the user-visible "disabled" tooltip — a second source of truth
 * that could easily drift from the probe's decision.
 *
 * Modeling the outcome as a sealed hierarchy gives the probe a single place
 * to distinguish *why* chrome tinting is unavailable. Phase 40.4 R-3 then
 * lifted the user-visible display strings into the UI layer
 * ([dev.ayuislands.settings.AyuIslandsChromePanel]) — a sealed `when` on
 * the subtype is total, so the mapping is still compile-time exhaustive
 * but the display copy lives next to the UI code that owns its wording.
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
