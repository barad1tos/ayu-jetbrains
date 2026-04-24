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
 * to distinguish *why* chrome tinting is unavailable, and lets callers
 * render a tailored message via [Unsupported.reason] without re-sampling
 * `SystemInfo` themselves.
 *
 * Back-compat: [ChromeDecorationsProbe.isCustomHeaderActive] is kept as a
 * thin delegate (`probe() is Supported`) for call sites that only need the
 * boolean answer (the Settings row `.enabledIf` predicate).
 */
sealed interface ChromeSupport {
    /** JBR custom window decorations are active — chrome tinting will reach the title bar. */
    object Supported : ChromeSupport

    /** Chrome tinting cannot reach the title bar; [reason] carries the user-visible explanation. */
    sealed interface Unsupported : ChromeSupport {
        val reason: String

        /** macOS is painting its native title bar (IDE 2026.1+ removed the unified-title toggle). */
        object NativeMacTitleBar : Unsupported {
            override val reason = "native macOS title bar"
        }

        /** Linux with GNOME server-side decorations — the window manager paints the title bar. */
        object GnomeSsd : Unsupported {
            override val reason = "GNOME server-side decorations"
        }

        /** Windows without the CustomWindowHeader LAF entry — IDE is using the native frame. */
        object WindowsNoCustomHeader : Unsupported {
            override val reason = "Windows native header"
        }

        /** Running on an OS the probe's dispatch enum does not cover. */
        object UnknownOs : Unsupported {
            override val reason = "unknown OS"
        }
    }
}
