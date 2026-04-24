package dev.ayuislands.accent

/**
 * Typed description of the live Swing peer a chrome element tints.
 *
 * Phase 40.3c Refactor 2 — before this type existed, [LiveChromeRefresher]
 * exposed six flat entry points (`refreshStatusBar`, `clearStatusBar`,
 * `refreshByClassName`, `clearByClassName`, `refreshByClassNameInsideAncestorClass`,
 * `clearByClassNameInsideAncestorClass`). Every chrome element had to remember
 * which of the six matched its peer topology and call it correctly.
 *
 * Modeling the target as a sealed type collapses the six-entry API into two
 * (`refresh(target, color)` + `clear(target)`), forces call sites to name the
 * peer strategy explicitly, and makes adding a new topology a compile-time
 * pattern-match failure instead of a silent new overload.
 *
 * ### Variants
 *
 *  - [StatusBar] — resolved via the public [com.intellij.openapi.wm.WindowManager]
 *    API per open [com.intellij.openapi.project.Project]. The only peer type not
 *    looked up via runtime class-name string match.
 *  - [ByClassName] — walks every showing AWT window and mutates any [javax.swing.JComponent]
 *    whose runtime class name equals [ByClassName.fqn]. Used for package-private
 *    platform peers (`com.intellij.toolWindow.Stripe`, internal `MainToolbar`,
 *    `MyNavBarWrapperPanel`) whose types we cannot import.
 *  - [ByClassNameInside] — ancestor-scoped variant. Mutates a matching [ByClassNameInside.target]
 *    only when its container chain includes a component whose runtime class name equals
 *    [ByClassNameInside.ancestor]. Used for `OnePixelDivider` (shared IDE-wide) so we don't
 *    leak panel-border tinting into editor splitters, diff gutter, Settings dialog, etc.
 *    See Phase 40 review Round 2 A-1.
 */
sealed interface ChromeTarget {
    /** StatusBar peer resolved via public [com.intellij.openapi.wm.WindowManager] API. */
    object StatusBar : ChromeTarget

    /** Peer located by runtime class-name string match across every showing window. */
    data class ByClassName(
        val fqn: ClassFqn,
    ) : ChromeTarget

    /** Ancestor-constrained class-name lookup — [target] must sit inside a [ancestor]. */
    data class ByClassNameInside(
        val target: ClassFqn,
        val ancestor: ClassFqn,
    ) : ChromeTarget
}
